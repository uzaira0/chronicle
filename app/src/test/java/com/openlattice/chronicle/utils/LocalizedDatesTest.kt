package com.openlattice.chronicle.utils

import com.openlattice.chronicle.uploadStatDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dates a parent in Santiago reads: the phone's zone and the phone locale's order, never the server's offset or ISO text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalizedDatesTest {
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before fun santiagoPhone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"))
        Locale.setDefault(Locale.forLanguageTag("es-CL"))
    }

    @After fun restore() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    @Test fun uploadTimeFromTheServerShowsSantiagoWallTime() {
        // 18:05 UTC on 29 October 2026 is 15:05 in Santiago (UTC-3 in summer time).
        val shown = Utils.formatAsMediumDateTime("2026-10-29T18:05:00Z")
        // Compare the known wall time in the locale's clock style (12/24-hour and punctuation vary by JDK).
        val expected = LocalDateTime.of(2026, 10, 29, 15, 5)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag("es-CL")))
        assertEquals(expected, shown)
    }

    @Test fun policyEffectiveDateIsALocalizedLongDate() {
        assertEquals("1 de octubre de 2026", Utils.formatPolicyDate(OffsetDateTime.parse("2026-10-01T09:00:00-03:00")))
        // The researcher's calendar day, not the phone's: midnight UTC on 1 October stays 1 October in Santiago.
        assertEquals("1 de octubre de 2026", Utils.formatPolicyDate(OffsetDateTime.parse("2026-10-01T00:00:00Z")))
    }

    @Test fun uploadHistoryDayFollowsTheLocaleOrder() {
        val spanish = uploadStatDay(LocalDate.of(2026, 10, 29))
        assertTrue(spanish, spanish.startsWith("29")) // es-CL: "29-10-2026"
        assertEquals("Oct 29, 2026", uploadStatDay(LocalDate.of(2026, 10, 29), Locale.US))
    }
}
