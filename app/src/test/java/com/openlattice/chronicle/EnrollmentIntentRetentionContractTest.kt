package com.openlattice.chronicle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnrollmentIntentRetentionContractTest {
    @Test
    fun everyViewIntentIsScrubbedBeforePendingOrCompleteEarlyReturns() {
        val source = locateEnrollmentSource().readText()
        val onNewIntent = source.substringAfter("override fun onNewIntent").substringBefore(
            "private fun resumeIssuedEnrollmentOrHandleIntent",
        )
        val resume = source.substringAfter("private fun resumeIssuedEnrollmentOrHandleIntent").substringBefore(
            "/** Removes a one-time invitation capability",
        )
        val detach = source.substringAfter("private fun detachEnrollmentCredential").substringBefore(
            "private fun handleIntent",
        )

        assertTrue(onNewIntent.indexOf("detachEnrollmentCredential(intent)") < onNewIntent.indexOf("pendingPreview"))
        assertTrue(resume.contains("detachedAccessCode: String? = detachEnrollmentCredential(sourceIntent)"))
        assertTrue(detach.contains("intent.data = data.buildUpon().fragment(null).build()"))
        assertTrue(resume.contains("showEnrollmentSuccess()"))
        assertTrue(resume.contains("showExistingEnrollmentRejection()"))
        assertTrue(source.contains("R.string.enrollment_existing_study_rejection"))
        assertTrue(
            locateAppFile("app/src/main/res/values/strings.xml").readText()
                .contains("Uninstall and reinstall Chronicle before opening another study invitation."),
        )
    }

    private fun locateEnrollmentSource(): File =
        locateAppFile("app/src/main/java/com/openlattice/chronicle/Enrollment.kt")

    private fun locateAppFile(relativePath: String): File {
        var directory: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = directory?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        error("Could not locate $relativePath")
    }
}
