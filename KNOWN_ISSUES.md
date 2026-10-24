# Known issues

Found in a code review on 2026-10-24. GitHub issues are disabled on uzaira0/chronicle, so these are tracked here.

## Room sample tables page and prune by timestamp without an index

### What is wrong

Eleven Room sample tables have no index on `timestamp`, but their DAOs page and prune by it. Every upload batch and every retention pass is a full table scan plus a sort.

Tables without any index (each entity is declared as a bare `@Entity(tableName = ...)`, and no migration adds one):

`activity_recognition_samples`, `app_network_usage_samples`, `audio_activity_samples`, `audio_content_samples`, `battery_samples`, `connectivity_state_samples`, `device_settings_samples`, `health_metric_samples`, `interaction_samples`, `notification_activity_samples`, `sleep_samples`.

Example, [`InteractionSampleEntry.kt:22-25`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/InteractionSampleEntry.kt#L22-L25):

```kotlin
@Entity(tableName = "interaction_samples")
data class InteractionSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
```

Queries that need the index, e.g. [`InteractionSampleDao.kt:18`, `:30`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/InteractionSampleDao.kt#L18-L30):

```kotlin
@Query("SELECT * FROM interaction_samples ORDER BY timestamp ASC LIMIT :limit")
fun getOldest(limit: Int): List<InteractionSampleEntry>
...
@Query("DELETE FROM interaction_samples WHERE timestamp < :cutoffTimestamp")
fun deleteOlderThan(cutoffTimestamp: String): Int
```

The same `getOldest` / `deleteOlderThan` pair exists in all eleven DAOs. The two tables that were fixed show the intended pattern: [`SensorSampleEntry.kt:7-10`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/SensorSampleEntry.kt#L7-L10) has `Index(value = ["timestamp", "id"])` (added in `Migration20to21.kt`), and [`QueueEntry.kt:8-10`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/QueueEntry.kt#L8-L10) indexes `(writeTimestamp, id)`.

### Failure scenario

The interaction uploader asks for up to 5,000 rows per pass ([`InteractionUploadWorker.kt:46`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionUploadWorker.kt#L46), used at [L121](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionUploadWorker.kt#L121)). With no index, SQLite reads every row in `interaction_samples` and sorts them to return the first 5,000. A device that cannot upload for several days (no network, server down, study paused) builds a backlog of hundreds of thousands of interaction rows; each 15-minute worker run then scans and sorts the whole table, and so does each retention `DELETE`. On low-end phones this means long WorkManager runs, battery drain and a growing risk of the worker being stopped before it finishes, which keeps the backlog growing. The cost grows with exactly the condition (a backlog) where uploads most need to be fast.

### Suggested fix

Add `indices = [Index(value = ["timestamp", "id"])]` to the eleven entities, matching `sensor_samples`, and add a Room migration (32 → 33) with `CREATE INDEX IF NOT EXISTS index_<table>_timestamp_id ON <table>(timestamp, id)` for each. Change `getOldest` to `ORDER BY timestamp ASC, id ASC` so ties are deterministic, as `SensorSampleDao` already does. Add a migration test that checks every sample table has the index, and an `EXPLAIN QUERY PLAN` test that `getOldest` uses it rather than `USE TEMP B-TREE FOR ORDER BY`.

## Interaction timestamps stored as OffsetDateTime.toString() do not sort as text

### What is wrong

Interaction sample timestamps are stored as `OffsetDateTime.toString()`, which is not fixed-width, so ordering and comparing them as text gives the wrong order.

[`InteractionCollectionService.kt:394-398`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionCollectionService.kt#L394-L398):

```kotlin
private fun anchoredWallClock(eventUptime: Long): String {
    val wallMillis = System.currentTimeMillis() - (SystemClock.uptimeMillis() - eventUptime)
    return Instant.ofEpochMilli(wallMillis).atOffset(ZoneOffset.UTC).toString()
}
```

`OffsetDateTime.toString()` omits seconds and milliseconds when they are zero:

| instant | stored string |
|---|---|
| 12:00:00.000 | `2026-10-24T12:00Z` |
| 12:00:00.500 | `2026-10-24T12:00:00.500Z` |
| 12:00:05.000 | `2026-10-24T12:00:05Z` |
| 12:00:05.100 | `2026-10-24T12:00:05.100Z` |

`timestamp` is a `TEXT` column and all queries compare it as text. `'Z'` (0x5A) sorts after `':'` (0x3A) and `'.'` (0x2E), so `12:00Z` sorts after every event in 12:00:00.001-12:00:59.999, and `12:00:05Z` sorts after 12:00:05.001-12:00:05.999.

Affected queries in [`InteractionSampleDao.kt`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/InteractionSampleDao.kt#L18-L37):

- `getOldest`: `ORDER BY timestamp ASC LIMIT :limit` (L18) returns events out of time order around exact seconds and minutes.
- `deleteOlderThan`: `WHERE timestamp < :cutoffTimestamp` (L30) keeps or deletes the wrong rows near the cutoff, depending on how the cutoff string was formatted.
- `getEntriesAfterTimestamp` (`>`, L33) and `deleteEntriesBeforeTimestamp` (`<=`, L36) are currently unused, but as an upload cursor they would lose data: if a batch ends at a row stored as `…T12:00Z`, an event at 12:00:30 written while that batch is uploading is stored as `…T12:00:30Z`, which sorts before `…T12:00Z`, so `deleteEntriesBeforeTimestamp('…T12:00Z')` deletes it without it ever having been uploaded, and `getEntriesAfterTimestamp('…T12:00Z')` would never return it either. The upload path currently deletes with `deleteByIds`, which is correct.

### Failure scenario

Two taps at 12:00:00.000 and 12:00:00.400 are stored as `…T12:00Z` and `…T12:00:00.400Z`. `getOldest` returns the 12:00:00.400 event first. If a batch boundary falls between them, the later event is uploaded in an earlier batch than the earlier one, and the server receives them out of order. About 1 in 1,000 events lands on an exact second (millisecond 000), and 1 in 60,000 on an exact minute, so over a study this happens regularly. If anyone switches the uploader to the timestamp cursor methods, rows on those boundaries are deleted without being uploaded.

### Suggested fix

Store a fixed-width UTC string, e.g. `DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(instant)`, or better store epoch milliseconds as an `INTEGER` column and format only at upload. Use the same formatter for every module and for retention cutoffs. Add a migration that rewrites existing `interaction_samples.timestamp` values to the fixed-width form. Remove the unused `getEntriesAfterTimestamp` / `deleteEntriesBeforeTimestamp` pair (also in `BatterySampleDao`) or change it to a `(timestamp, id)` cursor. Add a unit test that formats 12:00:00.000 and 12:00:00.400 and asserts the stored strings sort in time order.

## Changes from the 24 Oct skills alignment pass

The two issues above are specific instances. The alignment pass between the engineering skills and the product repos (items 2, 3 and 15 of its ranked list) turned them into rules for the whole local store, plus one measurement. All three were re-checked at `origin/develop` `1f1bb7b`.

### Index policy for every Room table sorted or filtered by time (item 3)

**What to change.** Beyond adding `(timestamp, id)` to the eleven sample tables listed above:

1. Make the rule explicit: every Room table that a DAO orders or filters by a time column carries an index whose leading columns are that time column plus `id`. This includes `sensor_sample_dead_letters` ([`SensorSampleDeadLetterEntity.kt`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/SensorSampleDeadLetterEntity.kt#L12-L17), no index today) as soon as any query reads it by `timestamp` or `quarantinedAt`; its only query today deletes by `sensorType` (`SensorSampleDao.kt:101`).
2. Add one schema test that loads the exported schema (`collection-base/schemas/com.openlattice.chronicle.storage.ChronicleDb/33.json` after the migration), runs `EXPLAIN QUERY PLAN` for **every** `@Query` that contains `ORDER BY timestamp`, `WHERE timestamp` or `writeTimestamp`, and fails on `SCAN <table>` or `USE TEMP B-TREE FOR ORDER BY`. New DAOs are then covered without anyone remembering to add a test.
3. Guard or delete the uncalled watermark methods `getEntriesAfterTimestamp` / `deleteEntriesBeforeTimestamp` in [`InteractionSampleDao.kt:34-37`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/InteractionSampleDao.kt#L34-L37) and [`BatterySampleDao.kt:34-37`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/collection-base/src/main/java/com/openlattice/chronicle/storage/BatterySampleDao.kt#L34-L37). Deleting them is simplest. If they stay, extend [`NoPrematureModulePurgeTest`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/test/java/com/openlattice/chronicle/services/upload/NoPrematureModulePurgeTest.kt), which already forbids `.deleteOlderThan(cutoff)` in the upload workers, to forbid `deleteEntriesBeforeTimestamp(` in production sources too.

**Why.** `software-design-checklist` Y8 (indexes match the queries that run); `data-structure-algorithm-selection` DSA-DATA-038 (mobile local-queue indexing for Room and Core Data) and DSA-DATA-037 (sortable timestamps).

**Evidence (formal-bug-hunt Chronicle upload-queue recipe, run 2026-10-24).** A TLA+ model of collect → select (persisted pending batch) → upload → delete, with retries and WorkManager kills, each action citing the code lines above; 28 configurations, expectations written before the run, all matched (tla2tools 1.7.4, pinned digest):

| configuration | NoLoss |
|---|---|
| current code: delete by id, unsortable keys, ties, kills, failures | holds (5,825 states; 473,637 at N=4 with 2 kills and 2 failures) |
| watermark delete, strictly increasing keys | holds |
| watermark delete, keys that can tie (same millisecond) | violated in 6 steps |
| watermark delete, `toString()` keys | violated in 6 steps (liveness also violated) |

The counterexamples were turned into `test_upload_queue_trace.py`, which runs Room's exported DDL (schema v32) and the DAO's `@Query` strings on SQLite with timestamps produced by the production JDK expression:

| test | result |
|---|---|
| `test_delete_by_ids_keeps_unuploaded_rows` | pass (the protocol the workers use is safe) |
| `test_watermark_delete_keeps_unuploaded_rows` | FAIL: row B lost |
| `test_oldest_first_follows_time` | FAIL: `getOldest` returns `…T10:00Z` after `…T10:00:00.001Z` |
| `test_oldest_first_is_served_by_an_index` | FAIL: `SCAN interaction_samples` / `USE TEMP B-TREE FOR ORDER BY` |
| `test_watermark_delete_with_tied_timestamps` | FAIL |

With fixed-width UTC text and a `(timestamp, id)` index applied to the scratch database, every test passes except the tie case, as the model predicts: delete-by-id stays the rule. Assets: `~/agent-skills/skills/formal-bug-hunt/assets/chronicle-upload-queue/` (`ChronicleUploadQueue.tla`, `run_tlc.py`, `tlc-results.txt`, `test_upload_queue_trace.py`), recipe `references/recipe-chronicle-upload-queue.md`.

### One timestamp encoding across Android, iOS and the server (item 2)

**What to change.** Store time as UTC epoch milliseconds (`Long`, through a Room `TypeConverter`, with the offset or zone in its own column) or, where text must stay, one fixed-width UTC formatter shared by every module. Every `*SampleEntry.kt` in `collection-base/.../storage/` declares `timestamp: String`; writers include [`InteractionCollectionService.kt:394-398`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionCollectionService.kt#L394-L398) (`Instant…atOffset(UTC).toString()`) and [`SensorUploadWorkerDelegate.kt:337`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/services/sensors/SensorUploadWorkerDelegate.kt#L337) (`deliveredAt = OffsetDateTime.now().toString()`, which carries the device's local offset, so two rows written either side of a timezone or DST change compare wrongly as text). The migration rewrites 12 tables.

**Shared vectors** (the same file on Android, iOS and the server): exact minute `2026-10-24T12:00:00.000Z` = 1792843200000; exact second `…12:00:05.000Z` = 1792843205000; sub-second `…12:00:05.100Z` = 1792843205100; negative offset `2026-10-24T07:00:05.100-05:00` = 1792843205100; DST fall-back `2026-11-01T01:30:00-04:00` = 1793511000000 and `…-05:00` = 1793514600000. Assert that each round-trips to the same millis and that stored values sort in time order.

**Why.** `software-design-checklist` Y9, Y10 (stored timestamps sort as time); DSA-DATA-037 (RFC 3339 §5.1: string order equals time order only with one fixed format and one offset; JDK `OffsetDateTime.toString()` omits zero seconds and milliseconds).

**Other platforms.** iOS sends whole-second `.iso8601` timestamps next to millisecond fields: uzaira0/chronicle-ios https://github.com/uzaira0/chronicle-ios/issues/2. Server side: the chronicle-server known-issues file on branch `docs/known-issues-20261024` (`docs/known-issues.md`).

### Interaction upload batch size against the background window (item 15)

**What to change.** Measure one `InteractionUploadWorker` pass at `INTERACTION_UPLOAD_MAX_BATCH = 5000` ([`InteractionUploadWorker.kt:46`](https://github.com/uzaira0/chronicle/blob/1f1bb7bcac659b632c04957710362c1377604555/app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/InteractionUploadWorker.kt#L46), used at L122) on a slow network (throttled emulator or a low-end device on 3G) with a backlog large enough to fill the batch. WorkManager stops a regular worker after ten minutes; if a pass gets near that, upload and commit in smaller batches inside one run so a stopped worker keeps the progress it made. Without the timestamp index above, each pass also scans and sorts the whole table first.

**Why.** DSA-RUN-039 (draining a durable queue within Android/iOS background limits: the WorkManager ten-minute limit for regular workers, Android 15's `dataSync` foreground-service limit of 6 hours per 24 hours).

**Test.** An instrumented test with a throttled fake server that records the time per pass at 5,000 rows, and asserts a pass that is stopped part-way leaves every committed sub-batch deleted and every uncommitted row pending.
