package com.openlattice.chronicle.collection.state

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

private const val PREFS_NAME = "minimal_play_artifact_state"
private const val KEY_BOUNDARY_REGISTRY_SHA256 = "boundary_registry_sha256"
private const val KEY_POLICY_REGISTRY_SHA256 = "policy_registry_sha256"

internal fun minimalPlayArtifactReady(
    distribution: String,
    expectedRegistrySha256: String,
    boundaryRegistrySha256: String?,
    policyRegistrySha256: String?,
    runtimePolicyClosed: Boolean = false,
): Boolean = distribution !in setOf("PLAY", "AMAZON") ||
    !runtimePolicyClosed &&
    expectedRegistrySha256.isNotBlank() &&
    boundaryRegistrySha256 == expectedRegistrySha256 &&
    policyRegistrySha256 == expectedRegistrySha256

/**
 * Fail-closed compatibility state for the minimized Play artifact.
 *
 * Collection and upload stay stopped until both conditions are true for the exact compiled
 * approved-module registry: legacy restricted state has been erased, and the active study's
 * authoritative settings have been accepted by the Play distribution policy. A registry change
 * automatically invalidates both proofs without relying on a versionCode comparison.
 */
object MinimalPlayArtifactState {
    /*
     * SharedPreferences.Editor.commit() can fail while leaving the previous compatible token on
     * disk. Close the current process before attempting that write and reopen it only after a
     * later compatible token is durably committed. This makes storage failure fail closed instead
     * of letting an already-running collector reuse the old token.
     */
    private val runtimePolicyClosed = AtomicBoolean(false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun isReady(context: Context): Boolean {
        if (!isMinimalPublicDistribution()) return true
        return try {
            val prefs = prefs(context)
            minimalPlayArtifactReady(
                distribution = BuildConfig.DISTRIBUTION_CHANNEL,
                expectedRegistrySha256 = BuildConfig.APPROVED_MODULE_REGISTRY_SHA256,
                boundaryRegistrySha256 = prefs.getString(KEY_BOUNDARY_REGISTRY_SHA256, null),
                policyRegistrySha256 = prefs.getString(KEY_POLICY_REGISTRY_SHA256, null),
                runtimePolicyClosed = runtimePolicyClosed.get(),
            )
        } catch (_: RuntimeException) {
            false
        }
    }

    fun markBoundaryApplied(context: Context) {
        if (!isMinimalPublicDistribution()) return
        check(
            prefs(context).edit()
                .putString(KEY_BOUNDARY_REGISTRY_SHA256, BuildConfig.APPROVED_MODULE_REGISTRY_SHA256)
                .commit(),
        ) { "Failed to persist the Play artifact boundary" }
    }

    fun markPolicyCompatible(context: Context) {
        if (!isMinimalPublicDistribution()) return
        check(
            prefs(context).edit()
                .putString(KEY_POLICY_REGISTRY_SHA256, BuildConfig.APPROVED_MODULE_REGISTRY_SHA256)
                .commit(),
        ) { "Failed to persist the Play study-policy boundary" }
        runtimePolicyClosed.set(false)
    }

    fun markPolicyIncompatible(context: Context) {
        if (!isMinimalPublicDistribution()) return
        runtimePolicyClosed.set(true)
        check(prefs(context).edit().remove(KEY_POLICY_REGISTRY_SHA256).commit()) {
            "Failed to close the Play study-policy boundary"
        }
    }

    private fun isMinimalPublicDistribution(): Boolean =
        BuildConfig.DISTRIBUTION_CHANNEL in setOf("PLAY", "AMAZON")
}
