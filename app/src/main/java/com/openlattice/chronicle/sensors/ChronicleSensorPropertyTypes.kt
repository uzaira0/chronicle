package com.openlattice.chronicle.sensors

import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.util.UUID

// Legacy olingo `FullQualifiedName` property-type constants. The `ChronicleSensor`
// interface that used to share this file now lives in `:collection-base`
// (`sensors/ChronicleSensor.kt`) — these constants stay in `:app` because the olingo
// dependency is excluded from `:collection-base`'s chronicle-models dependency.

val IMPORTANCE = FullQualifiedName("ol.recordtype")
val GENERAL_NAME = FullQualifiedName("general.fullname")
val APP_NAME = FullQualifiedName("ol.title")
val TIMESTAMP = FullQualifiedName("ol.datelogged")
val DURATION = FullQualifiedName("general.Duration")
val START_TIME = FullQualifiedName("ol.datetimestart")
val END_TIME = FullQualifiedName("general.EndTime")
val ALTITUDE = FullQualifiedName("location.altitude")
val LONGITUDE = FullQualifiedName("location.longitude")
val LATITUDE = FullQualifiedName("location.latitude")
val ID = FullQualifiedName("general.stringid")
val TIMEZONE = FullQualifiedName("ol.timezone")
val RECURRENCE_RULE = FullQualifiedName("ol.rrule")
val NAME = FullQualifiedName("ol.name")
val ACTIVE = FullQualifiedName("ol.active")
val USER = FullQualifiedName("ol.user")

val PROPERTY_TYPES = setOf(
    IMPORTANCE,
    GENERAL_NAME,
    APP_NAME,
    TIMESTAMP,
    ALTITUDE,
    LONGITUDE,
    LATITUDE,
    ID,
    DURATION,
    START_TIME,
    END_TIME,
    TIMEZONE,
    ACTIVE,
    RECURRENCE_RULE,
    NAME,
    USER
)

val PROPERTY_TYPE_IDS = mapOf(
    RECURRENCE_RULE to UUID.fromString("2d7e9eaf-8404-42b6-ba98-4287eab4901d"),
    TIMESTAMP to UUID.fromString("e90a306c-ee37-4cd1-8a0e-71ad5a180340"),
    ID to UUID.fromString("ee3a7573-aa70-4afb-814d-3fad27cda988"),
    GENERAL_NAME to UUID.fromString("70d2ff1c-2450-4a47-a954-a7641b7399ae"),
    IMPORTANCE to UUID.fromString("285e6bfc-2a73-49ae-8cb2-b112244ed85d"),
    TIMEZONE to UUID.fromString("071ba832-035f-4b04-99e4-d11dc4fbe0e8"),
    USER to UUID.fromString("188b754c-bd92-4f4a-8d01-a57fe94adc6d"),
    APP_NAME to UUID.fromString("f0373614-c607-43b2-99b0-1cd32ff4f921"),
    START_TIME to UUID.fromString("92a6a5c5-b4f1-40ce-ace9-be232acdce2a"),
    END_TIME to UUID.fromString("00e5c55f-f1ef-4538-8d48-c08d5bcfe4c7"),
    DURATION to UUID.fromString("c106ee75-f18e-48ed-bc85-b75702bfe802"),
    NAME to UUID.fromString("ddb5d841-4c82-407c-8fcb-58f04ffc20fe"),
    ACTIVE to UUID.fromString("54fa6acb-bd3e-4849-85b7-4eadaf33e112"),
    LATITUDE to UUID.fromString("06083695-aebe-4a56-9b98-da6013e93a5e"),
    LONGITUDE to UUID.fromString("e8f9026a-2494-4749-84bb-1499cb7f215c"),
    ALTITUDE to UUID.fromString("90203091-5efd-40c4-9372-9782746cd427")
)
