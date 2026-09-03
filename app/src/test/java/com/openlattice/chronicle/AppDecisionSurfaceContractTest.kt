package com.openlattice.chronicle

import com.openlattice.chronicle.android.AndroidSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppDecisionSurfaceContractTest {

    @Test
    fun playDistributionExcludesHealthWhileOpenRetainsTheResearchContract() {
        val playManifest = File("src/play/AndroidManifest.xml").readText()
        val openManifest = File("src/open/AndroidManifest.xml").readText()

        assertTrue(!playManifest.contains("permission.health."))
        assertTrue(!playManifest.contains("HealthConnectRationaleActivity"))
        assertTrue(openManifest.contains("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"))
    }

    @Test
    fun allParticipantFacingDecisionButtonsExistInLayouts() {
        val expectedIds = setOf(
            "button",
            "doneButton",
            "uploadNowButton",
            "mainBottomNav",
            "mainFragmentContainer",
            "overviewScroll",
            "uploadsScroll",
            "dataSharingScroll",
            "settingsHomeScroll",
            "identifyUserSwitch",
            "deviceUserGroup",
            "deviceUserUnset",
            "deviceUserChild",
            "deviceUserOther",
            "notificationAccessSwitch",
            "openServerSettingsButton",
            "open_settings_btn",
            "open_notification_settings_btn",
            "child_user_btn",
            "other_user_btn",
            "serverSaveButton",
            "serverDeleteButton",
            "orientationAccept",
            "orientationDecline",
        )
        val actualIds = File("src/main/res/layout")
            .walk()
            .filter { it.extension == "xml" }
            .flatMap { layout -> elements(layout).mapNotNull { it.androidAttr("id") } }
            .mapNotNull { it.substringAfter("@+id/", missingDelimiterValue = "").ifBlank { null } }
            .toSet()

        assertEquals(expectedIds, expectedIds.intersect(actualIds))
    }

    @Test
    fun bottomNavigationPartitionsEnrolledAppIntoFourTopLevelPages() {
        val menuIds = elements(File("src/main/res/menu/bottom_nav_menu.xml"))
            .mapNotNull { it.androidAttr("id") }
            .mapNotNull { it.substringAfter("@+id/", missingDelimiterValue = "").ifBlank { null } }
            .toSet()

        assertEquals(
            setOf("nav_overview", "nav_uploads", "nav_data_sharing", "nav_settings"),
            menuIds,
        )

        val mainActivity = File("src/main/java/com/openlattice/chronicle/MainActivity.kt").readText()
        assertTrue(mainActivity.contains("OverviewFragment()"))
        assertTrue(mainActivity.contains("UploadsFragment()"))
        assertTrue(mainActivity.contains("DataSharingFragment()"))
        assertTrue(mainActivity.contains("SettingsHomeFragment()"))

        val mainLayout = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(mainLayout.contains("@color/bottom_nav_item_tint"))
        assertTrue(File("src/main/res/color/bottom_nav_item_tint.xml").readText().contains("state_checked=\"true\""))
    }

    @Test
    fun withdrawalRemainsCollectionBlockingAcrossRestartAndResetsWithReenrollment() {
        val mainActivity = normalizedSource("src/main/java/com/openlattice/chronicle/MainActivity.kt")
        val withdrawalGuard = normalizeKotlin(
            "if (ParticipantWithdrawalManager.collectionMustRemainStopped(this)) { " +
                "ParticipantWithdrawalManager.resumePending(this)",
        )
        assertTrue(
            "MainActivity must reassert withdrawal shutdown before starting enrolled services.",
            mainActivity.contains(withdrawalGuard) &&
                mainActivity.indexOf(withdrawalGuard) < mainActivity.indexOf("startEnrolledServices()"),
        )
        val onResume = mainActivity.substringAfter(normalizeKotlin("override fun onResume()"))
        val resumeWithdrawalCheck = normalizeKotlin(
            "ParticipantWithdrawalManager.collectionMustRemainStopped(this)",
        )
        val resumeShutdown = normalizeKotlin(
            "ParticipantWithdrawalManager.resumePending(this)",
        )
        assertTrue(
            "MainActivity.onResume must not schedule enrolled work during withdrawal.",
            onResume.contains(resumeWithdrawalCheck) &&
                onResume.indexOf(resumeWithdrawalCheck) < onResume.indexOf("maybeTriggerForegroundSync()") &&
                onResume.indexOf(resumeShutdown) < onResume.indexOf("maybeTriggerForegroundSync()"),
        )

        val withdrawalStore = File(
            "src/main/java/com/openlattice/chronicle/services/withdrawal/ParticipantWithdrawalManager.kt",
        ).readText()
        assertTrue(withdrawalStore.contains("fun completeReenrollment(studyId: UUID, participantId: String)"))
        assertTrue(withdrawalStore.contains(".putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name)"))
        assertTrue(withdrawalStore.contains(".remove(KEY_STATE)"))
        assertTrue(withdrawalStore.contains(".remove(KEY_ACKNOWLEDGED_SERVERS)"))
        assertTrue(withdrawalStore.contains(".commit()"))

        val enrollment = File("src/main/java/com/openlattice/chronicle/Enrollment.kt").readText()
        val recovery = File("src/main/java/com/openlattice/chronicle/EnrollmentRecoveryManager.kt").readText()
        assertTrue(
            normalizeKotlin(recovery).contains(
                normalizeKotlin(
                    ".completeReenrollment(recovery.manifest.studyId, recovery.manifest.participantId,",
                ),
            ),
        )
        assertTrue(!enrollment.contains("enrollmentSettings.setStudyId(studyId)"))
        assertTrue(!enrollment.contains("enrollmentSettings.setParticipantId(participantId)"))
    }

    @Test
    fun settingsHomeDoesNotDuplicateTopLevelTabsOrEnrollmentGates() {
        val settingsLayout = File("src/main/res/layout/fragment_settings_home.xml").readText()
        assertTrue(!settingsLayout.contains("openSensorControlsButton"))
        assertTrue(!settingsLayout.contains("openUsagePermissionButton"))
        assertTrue(!settingsLayout.contains("open_sensor_controls"))
        assertTrue(!settingsLayout.contains("open_usage_permission"))

        val settingsFragment = File("src/main/java/com/openlattice/chronicle/ui/SettingsHomeFragment.kt").readText()
        assertTrue(!settingsFragment.contains("selectTab(R.id.nav_sensors)"))
        assertTrue(!settingsFragment.contains("PermissionActivity"))
    }

    @Test
    fun usageAccessIsRequestedOnlyAfterStudyConsentFromDataSharing() {
        val enrollment = File("src/main/java/com/openlattice/chronicle/Enrollment.kt").readText()
        val mainActivity = File("src/main/java/com/openlattice/chronicle/MainActivity.kt").readText()
        val dataSharing = File(
            "src/main/java/com/openlattice/chronicle/ui/DataSharingFragment.kt",
        ).readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(!enrollment.contains("PermissionActivity::class.java"))
        assertTrue(!mainActivity.contains("PermissionActivity::class.java"))
        assertTrue(dataSharing.contains("PermissionKind.USAGE_ACCESS"))
        assertTrue(dataSharing.contains("confirmUsageAccessDisclosure()"))
        assertTrue(dataSharing.contains("Settings.ACTION_USAGE_ACCESS_SETTINGS"))
        assertTrue(strings.contains("name=\"usage_access_disclosure_body\""))
        assertTrue(strings.contains("uploaded to the enrolled study server shown during enrollment"))
        assertTrue(!strings.contains("uploaded to the BCM Chronicle study server"))
    }

    @Test
    fun enrollmentNeverFallsBackToAPublisherServerWhenInvitationOriginIsMissing() {
        val enrollment = File("src/main/java/com/openlattice/chronicle/Enrollment.kt").readText()
        val layout = File("src/main/res/layout/activity_enrollment.xml").readText()

        assertTrue(!enrollment.contains("ifBlank { PRODUCTION }"))
        assertTrue(!enrollment.contains("services.upload.PRODUCTION"))
        assertTrue(enrollment.contains("R.string.enrollment_missing_server_url"))
        assertTrue(
            File("src/main/res/values/strings.xml").readText()
                .contains("Study invitation is missing its public HTTPS server."),
        )
        assertTrue(
            !layout.contains(
                "android:text=\"https://chronicle-screentime-app.research.bcm.edu\"",
            ),
        )
        val availabilityReporter = normalizedSource(
            "src/main/java/com/openlattice/chronicle/services/sensors/SensorAvailabilityReporter.kt",
        )
        assertTrue(!availabilityReporter.contains("serverUrl: String = PRODUCTION"))
    }

    @Test
    fun collectionOrientationActionButtonsHaveFixedTapTargetHeight() {
        val layout = File("src/main/res/layout/activity_collection_orientation.xml")
        val layoutSource = layout.readText()
        assertTrue(
            "Collection orientation runs under an ActionBar; reserve its height so content does not render under the setup banner.",
            layoutSource.contains("android:paddingTop=\"?attr/actionBarSize\""),
        )
        assertTrue(
            "Scrollable orientation content must not clip system-inset padding around the first/last controls.",
            layoutSource.contains("android:clipToPadding=\"false\""),
        )
        val byId = elements(layout)
            .associateBy { it.androidAttr("id")?.substringAfter("@+id/") }

        assertEquals("0dp", byId.getValue("orientationScrollView").androidAttr("layout_height"))
        assertEquals("1", byId.getValue("orientationScrollView").androidAttr("layout_weight"))
        assertEquals("wrap_content", byId.getValue("orientationActions").androidAttr("layout_height"))
        assertTrue(
            "The runtime wizard must show the source-study plan so every step is labeled required or optional.",
            byId.containsKey("orientationRequirementSummaryHeader") &&
                byId.containsKey("orientationRequirementSummary"),
        )

        listOf("orientationAccept", "orientationDecline").forEach { id ->
            val button = byId.getValue(id)
            assertEquals(
                "Consent action buttons must live in the fixed bottom action bar, outside the scrolling body.",
                "orientationActions",
                (button.parentNode as Element).androidAttr("id")?.substringAfter("@+id/"),
            )
            assertEquals("56dp", button.androidAttr("layout_height"))
            assertEquals("56dp", button.androidAttr("minHeight"))
            assertEquals("1", button.androidAttr("maxLines"))
            assertEquals("end", button.androidAttr("ellipsize"))
            assertEquals("0dp", button.androidAttr("insetTop"))
            assertEquals("0dp", button.androidAttr("insetBottom"))
        }

        val orientationSource = File(
            "src/main/java/com/openlattice/chronicle/collection/state/CollectionOrientationActivity.kt",
        ).readText()
        assertTrue(!orientationSource.contains("can't allow"))
        assertTrue(orientationSource.contains("declineLabel = \"Don't allow\""))
        assertTrue(
            "Orientation summary must be bound from the same source-study plan that drives the wizard steps.",
            orientationSource.contains("orientationRequirementSummary") &&
                orientationSource.contains("plan.requirementSummaryLines(currentModule = step.moduleId, copy = planCopy)"),
        )
    }

    @Test
    fun userIdentificationDecisionsUseResponsiveScrollableLayout() {
        val layout = File("src/main/res/layout/activity_user_identification.xml")
        val layoutSource = layout.readText()
        val byId = elements(layout)
            .associateBy { it.androidAttr("id")?.substringAfter("@+id/") }

        val scroll = byId.getValue("user_identification_scroll")
        assertEquals("true", scroll.androidAttr("fillViewport"))
        assertEquals("false", scroll.androidAttr("clipToPadding"))
        assertEquals("?attr/actionBarSize", scroll.androidAttr("paddingTop"))
        assertTrue(
            "The notification destination needs a real scrolling fallback on short screens.",
            layoutSource.contains("<androidx.core.widget.NestedScrollView"),
        )

        val prompt = byId.getValue("select_user_prompt")
        val child = byId.getValue("child_user_btn")
        val other = byId.getValue("other_user_btn")
        val content = byId.getValue("user_identification_content")
        assertEquals("vertical", content.androidAttr("orientation"))
        assertEquals("center", content.androidAttr("gravity"))
        assertEquals("560dp", prompt.androidAttr("maxWidth"))

        listOf(prompt, child, other).forEach { decisionSurface ->
            assertEquals(
                "Prompt and decisions must remain in one content-sized vertical flow.",
                "user_identification_content",
                (decisionSurface.parentNode as Element).androidAttr("id")?.substringAfter("@+id/"),
            )
        }
        assertTrue(
            "The prompt must precede the child decision in the vertical flow.",
            layoutSource.indexOf("@+id/select_user_prompt") < layoutSource.indexOf("@+id/child_user_btn"),
        )
        assertTrue(
            "The child and other decisions must remain vertically ordered.",
            layoutSource.indexOf("@+id/child_user_btn") < layoutSource.indexOf("@+id/other_user_btn"),
        )

        listOf(child, other).forEach { button ->
            assertEquals(
                "Decision buttons must size within the viewport instead of competing in a fixed row.",
                "wrap_content",
                button.androidAttr("layout_width"),
            )
            assertEquals("56dp", button.androidAttr("minHeight"))
            assertEquals(
                "A fixed minimum width can clip the decisions in compact or split-screen windows.",
                null,
                button.androidAttr("minWidth"),
            )
            assertEquals("480dp", button.androidAttr("maxWidth"))
            assertEquals("2", button.androidAttr("maxLines"))
        }

        val activity = File("src/main/java/com/openlattice/chronicle/UserIdentificationActivity.kt").readText()
        assertTrue(
            "The activity must preserve system-bar clearance under targetSdk edge-to-edge behavior.",
            activity.contains("padViewForSystemBars(R.id.user_identification_scroll)"),
        )
        assertTrue(
            "A notification destination must route encrypted-local-store failures to explicit recovery.",
            activity.contains("catch (error: LocalStoreRecoveryRequiredException)") &&
                activity.contains("LocalStoreRecoveryActivity.intent(") &&
                activity.contains("error.recoveryReason"),
        )
        assertTrue(
            "The decision screen must not report success or close unless persistence succeeded.",
            activity.contains("if (result is ModuleResult.Ok)") &&
                activity.indexOf("scheduleChronicleSyncWork(") > activity.indexOf("if (result is ModuleResult.Ok)"),
        )
    }

    @Test
    fun interactionAccessibilityServiceIsResearchOnlyAndDoesNotRequestFloatingShortcutButton() {
        assertTrue(!File("src/main/res/xml/interaction_accessibility_service_config.xml").exists())
        assertTrue(!File("src/main/res/xml-v31/interaction_accessibility_service_config.xml").exists())
        val config = File("src/googleServices/res/xml/interaction_accessibility_service_config.xml").readText()
        assertTrue(config.contains("android:accessibilityFlags=\"flagDefault\""))
        assertTrue(!config.contains("flagRequestAccessibilityButton"))
    }

    @Test
    fun frameworkDrivenCollectionGateReadsStayOffMainThread() {
        val notificationListener = normalizedSource("src/main/java/com/openlattice/chronicle/services/notifications/NotificationListener.kt")
        assertTrue(
            notificationListener.contains(
                normalizeKotlin(
                    "executeIo(\"notification-activity capture\") { runCatching {",
                ),
            ) && notificationListener.contains(
                normalizeKotlin(
                    "ResearchPersistenceGate.persistIfCollecting(applicationContext, CollectionModuleId.NOTIFICATION_ACTIVITY,",
                ),
            ) && notificationListener.contains(normalizeKotlin("ioExecutor.execute { runCatching(block)")),
        )

        val interactionService = normalizedSource("src/main/java/com/openlattice/chronicle/collection/interaction/InteractionCollectionService.kt")
        val interactionWriteBoundary = interactionService.indexOf(normalizeKotlin("writeExecutor.execute { try {"))
        assertTrue(
            interactionWriteBoundary >= 0 &&
                !interactionService.substring(0, interactionWriteBoundary).contains(
                    normalizeKotlin("CollectionGate.collects(ctx, CollectionModuleId.INTERACTION_EVENTS)"),
                ) &&
                interactionService.contains(
                    normalizeKotlin(
                        "ResearchPersistenceGate.persistIfCollecting(ctx, CollectionModuleId.INTERACTION_EVENTS,",
                    ),
                ) && interactionService.contains(normalizeKotlin("executor.execute(task)")),
        )
    }

    @Test
    fun interactionDisplayLookupToleratesIncompleteOemFrameworks() {
        val interactionService = normalizedSource(
            "src/main/java/com/openlattice/chronicle/collection/interaction/InteractionCollectionService.kt",
        )
        assertTrue(interactionService.contains("getMethod(\"getDisplayId\")"))
        assertTrue(interactionService.contains("method.invoke(event)"))
        assertTrue(!interactionService.contains("event.displayId"))
        assertTrue(
            interactionService.contains(
                normalizeKotlin("eventDisplayId ?: Display.DEFAULT_DISPLAY"),
            ),
        )
    }

    @Test
    fun topLevelPagesKeepContentOutFromUnderSystemBarsAndBottomNavigation() {
        // System-bar insets are applied PROGRAMMATICALLY (edge-to-edge, enforced from
        // targetSdk 35+): MainActivity is NoActionBar and pads mainRoot for the status bar
        // (top=true), and each page pads its own content for the remaining bars via
        // padForSystemBars/padViewForSystemBars. So the pages no longer carry a static
        // paddingTop="?attr/actionBarSize" (that reserved space for an ActionBar this app
        // does not host). The static content offsets — 32dp top for tablet toolbar text
        // bounds, 96dp bottom to clear the bottom nav — are retained.
        val pageSources = mapOf(
            "fragment_overview.xml" to "ui/OverviewFragment.kt",
            "fragment_uploads.xml" to "ui/UploadsFragment.kt",
            "fragment_data_sharing.xml" to "ui/DataSharingFragment.kt",
            "fragment_settings_home.xml" to "ui/SettingsHomeFragment.kt",
            "activity_server_enrollment.xml" to "ServerEnrollmentActivity.kt",
        )
        pageSources.forEach { (layoutName, sourceName) ->
            val layout = File("src/main/res/layout/$layoutName").readText()
            assertTrue("$layoutName must keep final controls above bottom nav.", layout.contains("android:paddingBottom=\"96dp\""))
            assertTrue("$layoutName needs enough top content offset for tablet toolbar text bounds.", layout.contains("android:paddingTop=\"32dp\""))
            val source = File("src/main/java/com/openlattice/chronicle/$sourceName").readText()
            assertTrue(
                "$sourceName must apply system-bar insets programmatically (edge-to-edge).",
                source.contains("padForSystemBars") || source.contains("padViewForSystemBars"),
            )
        }
    }

    @Test
    fun androidThemeUsesLocalEqualAccessibilityDesignTokens() {
        val colorXml = File("src/main/res/values/colors.xml").readText()
        // iOS-parity light palette (2026-07-16): purple #6D49FE on white surfaces,
        // matching the iOS app's PrimaryAppColor. Token names stay the vendored eq_*
        // scheme; values are pinned here so the palette stays local and reproducible.
        val requiredLightTokens = mapOf(
            "eq_bg" to "#F4F3FA",
            "eq_surface" to "#FFFFFF",
            "eq_surface_muted" to "#EFECF8",
            "eq_ink" to "#1C1B22",
            "eq_muted" to "#66626F",
            "eq_border" to "#E3E0EE",
            "eq_primary" to "#6D49FE",
            "eq_primary_ink" to "#FFFFFF",
            "eq_focus" to "#5433D6",
        )

        requiredLightTokens.forEach { (androidToken, value) ->
            assertTrue(
                "Android theme must expose vendored Equal token $androidToken.",
                colorXml.contains("<color name=\"$androidToken\">$value</color>", ignoreCase = true),
            )
        }
        assertTrue(colorXml.contains("<color name=\"chronicle_bg\">@color/eq_bg</color>"))
        assertTrue(colorXml.contains("<color name=\"chronicle_text_primary\">@color/eq_ink</color>"))

        val stylesXml = File("src/main/res/values/styles.xml").readText()
        assertTrue(stylesXml.contains("@font/atkinson_hyperlegible"))
        assertTrue(stylesXml.contains("@font/ibm_plex_mono"))
        assertTrue(File("src/main/res/font/atkinson_hyperlegible_regular.ttf").exists())
        assertTrue(File("src/main/res/font/atkinson_hyperlegible.xml").exists())
    }

    @Test
    fun sensorsPageExposesSensorStatusAndPerSensorModuleToggles() {
        val sensorLayout = File("src/main/res/layout/fragment_data_sharing.xml").readText()
        assertTrue(sensorLayout.contains("@+id/dataSharingPageTitle"))
        assertTrue(sensorLayout.contains("@+id/sensorsSectionTitle"))
        assertTrue(sensorLayout.contains("@+id/sensorsSummary"))
        assertTrue(sensorLayout.contains("@+id/sensorToggleList"))

        val sensorsFragment = File("src/main/java/com/openlattice/chronicle/ui/DataSharingFragment.kt").readText()
        assertTrue("configured sensors should not show explanatory filler", !sensorsFragment.contains("Each sensor below is its own choice"))
        assertTrue(sensorsFragment.contains("visibility = View.GONE"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(sensorsFragment.contains("R.string.ds_no_sensor_collection"))
        assertTrue(strings.contains("No compatible sensor collection is configured on this device."))
        assertTrue(sensorsFragment.contains("visibility = View.VISIBLE"))
        // Each sensor is its own consent-gated module row (per-sensor consent redesign), rendered
        // in the canonical relevance order shared with the wizard + web form (2026-06-12).
        assertTrue(sensorsFragment.contains("SensorCollectionModules.sensorDisplayOrder"))
        assertTrue("each sensor maps back to its type", sensorsFragment.contains("SensorCollectionModules.sensorTypeOf"))
        // Per-sensor status copy.
        assertTrue(sensorsFragment.contains("Not available on this tablet"))
        assertTrue(sensorsFragment.contains("R.string.ds_status_not_collected"))
        assertTrue(strings.contains("Not collected by this study"))
        assertTrue(sensorsFragment.contains("R.string.ds_status_on"))
        assertTrue(strings.contains("On — collecting"))
        assertTrue(sensorsFragment.contains("R.string.ds_status_off_share"))
        assertTrue(strings.contains("Off — turn on to share"))
        assertTrue(sensorsFragment.contains("R.string.ds_sensor_toggle_cd"))
        assertTrue(strings.contains("sensor collection toggle"))
        assertTrue(sensorsFragment.contains("SwitchMaterial(requireContext())"))
        // The sensor's study-set sampling rate + duty cycle are shown READ-ONLY (mobile cannot edit).
        assertTrue("per-sensor Hz/duty must be shown read-only", sensorsFragment.contains("R.string.ds_sensor_study_setting") && strings.contains("Study setting:"))
        assertTrue(sensorsFragment.contains("rateHzByType"))
        // Toggling a sensor accepts/declines that single sensor module — no local opt-in store.
        assertTrue(
            "a sensor toggle must drive the per-module accept/decline decision path",
            sensorsFragment.contains("onToggleOptional"),
        )
        assertTrue(
            "the retired local per-sensor opt-in store must be gone",
            !sensorsFragment.contains("setSensorLocallyEnabled"),
        )
        assertTrue(sensorsFragment.contains("minimumHeight = resources.getDimensionPixelSize(R.dimen.chronicle_sensor_row_min_height)"))
        assertTrue(
            "Programmatic sensor rows must use dp dimensions, not raw px padding that clips text on tablets.",
            !sensorsFragment.contains("setPadding(16, 12, 16, 12)"),
        )
        assertTrue(
            "Contract sanity check: this test must fail loudly if the known sensor surface collapses.",
            AndroidSensorType.values().size >= 10,
        )
    }

    @Test
    fun android16HardwareSensorForegroundServiceUsesSpecialUseType() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".services.sensors.HardwareSensorService\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }

    @Test
    fun playManifestRemovesEveryPrivilegedCollectorExceptUnlockIdentification() {
        val manifest = File("src/play/AndroidManifest.xml").readText()
        listOf(
            "HardwareSensorService",
            "NotificationListener",
            "InteractionCollectionService",
            "LockedBootReceiver",
        ).forEach { component ->
            assertTrue(
                "Play must explicitly remove $component",
                manifest.contains(component) && manifest.contains("tools:node=\"remove\""),
            )
        }
        assertTrue(manifest.contains("android.permission.HIGH_SAMPLING_RATE_SENSORS"))
        assertTrue(manifest.contains("android.permission.ACTIVITY_RECOGNITION"))
    }

    @Test
    fun minifiedBuildTypesHaveVariantFallbacks() {
        val buildGradle = File("build.gradle").readText()
        assertTrue(buildGradle.contains("debugMinified"))
        assertTrue(buildGradle.contains("matchingFallbacks = ['debug']"))
        assertTrue(buildGradle.contains("releaseMinified"))
        assertTrue(buildGradle.contains("matchingFallbacks = ['release']"))
        assertTrue(buildGradle.contains("dogfood {"))
        assertTrue(buildGradle.contains("outputs/apk/\${flavorName}/\${buildTypeName}"))
        assertTrue(buildGradle.contains("app-\${flavorName}-\${buildTypeName}.apk"))
        assertTrue(buildGradle.contains("16kbNativeLibs"))
    }

    @Test
    fun uploadNowCoalescesImmediateSyncWorkAndRequiresNetwork() {
        val syncWorker = File("src/main/java/com/openlattice/chronicle/services/sync/ChronicleSyncWorker.kt").readText()
        assertTrue(syncWorker.contains("CHRONICLE_SYNC_IMMEDIATE_WORK_NAME"))
        assertTrue(syncWorker.contains("IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY = ExistingWorkPolicy.KEEP"))
        assertTrue(syncWorker.contains("MANUAL_SYNC_STRATEGY = ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD"))
        assertTrue(syncWorker.contains(".setConstraints(UPLOAD_NETWORK_CONSTRAINT)"))
    }

    @Test
    fun combinedUploadSchedulingReplacesStaleImmediateWorkAndUpdatesPeriodicWork() {
        val combinedWorker = File("src/main/java/com/openlattice/chronicle/services/upload/CombinedUploadWorker.kt").readText()
        assertTrue(combinedWorker.contains("LEGACY_USAGE_UPLOAD_WORK_NAME"))
        assertTrue(combinedWorker.contains("LEGACY_SENSOR_UPLOAD_WORK_NAME"))
        assertTrue(combinedWorker.contains("wm.cancelUniqueWork(LEGACY_USAGE_UPLOAD_WORK_NAME)"))
        assertTrue(combinedWorker.contains("wm.cancelUniqueWork(LEGACY_SENSOR_UPLOAD_WORK_NAME)"))
        assertTrue(combinedWorker.contains("COMBINED_UPLOAD_WORK_NAME"))
        assertTrue(combinedWorker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertTrue(combinedWorker.contains("UPLOAD_NETWORK_CONSTRAINT"))

        val uploadWorker = File("src/main/java/com/openlattice/chronicle/services/upload/UploadWorker.kt").readText()
        assertTrue(uploadWorker.contains("COMBINED_UPLOAD_IMMEDIATE_WORK_NAME"))
        assertTrue(uploadWorker.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(uploadWorker.contains("OneTimeWorkRequestBuilder<CombinedUploadWorker>()"))
        assertTrue(uploadWorker.contains(".setConstraints(UPLOAD_NETWORK_CONSTRAINT)"))
    }

    @Test
    fun perServerNetworkPathsPropagateSigningOverride() {
        val expected = mapOf(
            "services/upload/UploadExecutor.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "services/sensors/SensorUploadWorkerDelegate.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "collection/battery/BatteryUploadWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "collection/audio/AudioUploadWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "collection/interaction/InteractionUploadWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "collection/device/ExpansionUploadWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "collection/state/CollectionLoopCoordinator.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
                "syncEncryptionSetting(studyId, server.url, server.mobileSigningSecretOverride)",
                "UploadWorker.getChronicleStudyApi(serverUrl, mobileSigningSecretOverride)",
            ),
            "services/sensors/SensorSettingsRefreshWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(primary.url, primary.mobileSigningSecretOverride)",
            ),
            "services/sensors/SensorAvailabilityReporter.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(serverUrl, mobileSigningSecretOverride)",
            ),
            "services/sensors/SensorSettingsRefreshProductionSeams.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "services/enrollment/EnrollmentMonitoringWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "services/notifications/NotificationsWorker.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)",
            ),
            "Enrollment.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(serverUrl, mobileSigningSecretOverride)",
            ),
            "ServerEnrollmentActivity.kt" to listOf(
                "UploadWorker.getChronicleStudyApi(url, mobileSigningSecretOverride)",
            ),
        )

        expected.forEach { (sourceName, snippets) ->
            val source = normalizedSource("src/main/java/com/openlattice/chronicle/$sourceName")
                .replace(",)", ")")
            snippets.forEach { snippet ->
                assertTrue(
                    "$sourceName must preserve per-server mobileSigningSecretOverride in: $snippet",
                    source.contains(normalizeKotlin(snippet)),
                )
            }
        }

        val mainSources = File("src/main/java/com/openlattice/chronicle")
            .walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .joinToString("\n") { it.readText() }
        assertTrue(
            "No per-server fanout path should call getChronicleStudyApi(server.url) without the override.",
            !normalizeKotlin(mainSources).contains("getChronicleStudyApi(server.url)"),
        )
    }

    @Test
    fun usageMetadataFallbackUsesTheEnrolledServerInsteadOfBcmProduction() {
        listOf(
            "services/usage/UsageMonitoringWorker.kt",
            "services/usage/UsageModuleCollectionDelegate.kt",
        ).forEach { sourceName ->
            val source = normalizedSource("src/main/java/com/openlattice/chronicle/$sourceName")
            assertTrue(!source.contains(normalizeKotlin("createRetrofitAdapter(PRODUCTION)")))
            assertTrue(
                source.contains(
                    normalizeKotlin(
                        "UploadWorker.getChronicleStudyApi(primary.url, primary.mobileSigningSecretOverride)"
                    )
                )
            )
        }
    }

    @Test
    fun collectionAckReportsToTheExactActiveEnrollmentServer() {
        val coordinator = normalizedSource("src/main/java/com/openlattice/chronicle/collection/state/CollectionLoopCoordinator.kt")
        assertTrue(
            "Collection decisions must resolve the database-authorized enabled study server.",
            coordinator.contains(normalizeKotlin("servers: List<UploadServerEntity> = enabledServers()")),
        )
        assertTrue(
            "The enabled-server resolver must bind the one authorized server to the active enrollment identity.",
            coordinator.contains(
                normalizeKotlin(
                    "exactActiveEnrollmentServer(appContext, ChronicleDb.getInstance(appContext))"
                )
            ),
        )
        assertTrue(
            "The exact enrollment resolver must return at most one acknowledgment destination.",
            coordinator.contains(normalizeKotlin("listOfNotNull(primaryServer())")),
        )
        assertTrue(
            "Collection decisions must deliver the acknowledgment to the resolved study server.",
            coordinator.contains(normalizeKotlin("servers.forEach { server ->")),
        )
        assertTrue(
            "Collection-ack must preserve each server's per-server signing override.",
            coordinator.contains(normalizeKotlin("UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride).reportCollectionAck")),
        )
        assertTrue(
            "Collection-ack fanout must be covered by a pure helper, not only source-scanned.",
            coordinator.contains(normalizeKotlin("fun reportCollectionAckToServers(")),
        )
        assertTrue(
            "Collection-ack must report partial failure instead of treating one successful destination as enough.",
            coordinator.contains(normalizeKotlin("allSucceeded = false")),
        )
        assertTrue(
            "A decision with no exact enrollment destination must not be marked durably reported.",
            coordinator.contains(normalizeKotlin("var retryDurable = succeeded")),
        )
        assertTrue(
            "Failed collection-ack reports must be persisted for retry, not dropped after best-effort fanout.",
            coordinator.contains(normalizeKotlin("CollectionAckRetryQueue.of(appContext).enqueue(failedReports)")),
        )
        assertTrue(
            "Collection settings sync must retry pending collection-ack reports.",
            coordinator.contains(normalizeKotlin("val pendingAcksReported = retryPendingCollectionAcks()")),
        )
        val retryStore = normalizedSource("src/main/java/com/openlattice/chronicle/collection/state/CollectionAckRetryStore.kt")
        assertTrue(
            "Pending collection-ack retry state must use encrypted preferences.",
            retryStore.contains(normalizeKotlin("EncryptedPrefsHelper.getEncryptedPrefs(context)")),
        )
    }

    private fun elements(file: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String? =
        getAttribute("android:$name").ifBlank { null }

    private fun normalizedSource(path: String): String {
        val source = File(path).takeIf(File::isFile)
            ?: File(path.replace("src/main/java/", "src/googleServices/java/"))
        return normalizeKotlin(source.readText())
    }

    /**
     * Every participant-visible label resolves through `values/strings.xml`, so a
     * `values-<lang>` table can override it. The English text lives only in the resource
     * table (or, for a JVM-tested presenter, in its `englishCopy` fallback).
     */
    @Test
    fun participantVisibleCopyResolvesThroughStringResources() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val repository = File(
            "src/main/java/com/openlattice/chronicle/ui/DashboardDataRepository.kt",
        ).readText()
        val dataSharing = File(
            "src/main/java/com/openlattice/chronicle/ui/DataSharingFragment.kt",
        ).readText()
        val disclosure = File(
            "src/main/java/com/openlattice/chronicle/StudyDisclosureActivity.kt",
        ).readText()
        val questionnaire = File(
            "src/main/java/com/openlattice/chronicle/collection/notifications/" +
                "QuestionnaireModuleHolder.kt",
        ).readText()

        // Activity titles shown by the system are resource ids, never inline text.
        assertTrue(!manifest.contains("android:label=\"Server Settings\""))
        assertTrue(!manifest.contains("android:label=\"Set up data collection\""))
        assertTrue(!manifest.contains("android:label=\"Study information\""))
        assertTrue(manifest.contains("android:label=\"@string/title_server_settings\""))
        assertTrue(manifest.contains("android:label=\"@string/orientation_action_title\""))
        assertTrue(manifest.contains("android:label=\"@string/title_study_information\""))
        assertTrue(strings.contains("<string name=\"title_server_settings\">Server Settings</string>"))
        assertTrue(strings.contains("<string name=\"title_study_information\">Study information</string>"))

        // Overview / Uploads status lines.
        assertTrue(repository.contains("R.string.collection_status_waiting"))
        assertTrue(repository.contains("R.string.collection_status_counts"))
        assertTrue(repository.contains("R.string.collection_status_unavailable"))
        assertTrue(repository.contains("R.string.server_health_status"))
        assertTrue(repository.contains("R.string.health_status_unknown"))
        assertTrue(repository.contains("R.string.upload_stats_daily"))
        listOf(
            "<string name=\"collection_status_waiting\">Waiting for study collection settings</string>",
            "<string name=\"collection_status_unavailable\">Collection status unavailable</string>",
            "<string name=\"server_health_healthy\">Study server healthy</string>",
            "<string name=\"health_status_unknown\">No uploads yet</string>",
        ).forEach { entry -> assertTrue("Missing string entry: $entry", strings.contains(entry)) }
        listOf(
            "\"Waiting for study collection settings\"",
            "\"Collection status unavailable\"",
            "\"No upload servers configured\"",
            "\"Study server healthy\"",
            "healthStatus().label()",
        ).forEach { leak ->
            assertTrue("Hardcoded dashboard copy: $leak", !repository.contains(leak))
        }

        // Data Sharing module rows use the localized module label + a resource description.
        assertTrue(dataSharing.contains("R.string.ds_module_toggle_cd"))
        assertTrue(!dataSharing.contains("\" data collection toggle\""))
        assertTrue(dataSharing.contains("CollectionConsentCopy.localizedConsentTemplate"))
        assertTrue(strings.contains("<string name=\"ds_module_toggle_cd\">%1\$s data collection toggle</string>"))

        // Study-disclosure enabled-function lines have resource ids for the live screen.
        assertTrue(disclosure.contains("R.string.disclosure_function_upload_telemetry"))
        assertTrue(disclosure.contains("R.string.disclosure_function_sensor_availability"))
        assertTrue(disclosure.contains("R.string.disclosure_function_questionnaire"))
        assertTrue(strings.contains("name=\"disclosure_function_upload_telemetry\""))
        assertTrue(strings.contains("Upload delivery diagnostics"))

        // The questionnaire reminder notification reads its tap text from resources.
        assertTrue(questionnaire.contains("R.string.reminder_tap_questionnaire"))
        assertTrue(!questionnaire.contains("\"Tap to complete questionnaire\""))
    }

    private fun normalizeKotlin(source: String): String =
        source.replace(Regex("\\s+"), "")
}
