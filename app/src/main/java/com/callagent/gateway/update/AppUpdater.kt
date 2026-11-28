package com.callagent.gateway.update

import android.content.Context
import android.util.Log

/**
 * The whole update: ask, and when there is something newer, put it over.
 *
 * The check and the install are one thing so that the automatic path (the
 * gateway asks on its own at start-up) and the manual path (the operator
 * presses the button) cannot drift apart.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    /** What the check found. */
    sealed class Outcome {
        /** What to tell the operator. */
        abstract fun describe(): String

        /** Nothing newer is published. */
        object UpToDate : Outcome() {
            override fun describe(): String = "Up to date"
        }

        /** A newer build is there, but nothing was done with it. */
        data class Available(val version: String) : Outcome() {
            override fun describe(): String = "$version is available"
        }

        /** The newer build is over the old one. */
        data class Installed(val version: String) : Outcome() {
            override fun describe(): String = "$version installed"
        }

        /** The newer build is waiting for a tap in the system installer. */
        data class InstallerOpened(val version: String) : Outcome() {
            override fun describe(): String = "$version is waiting in the installer"
        }

        /** Nothing was changed, and here is why. */
        data class Failed(val reason: String) : Outcome() {
            override fun describe(): String = "Update failed: $reason"
        }
    }

    /**
     * Check the feed and, when there is something newer, put it over.
     *
     * [autoInstall] says whether the newer build is installed on its own or
     * only reported — the operator may want to see it first.
     */
    fun run(
        context: Context,
        currentVersion: String,
        autoInstall: Boolean,
        log: (String) -> Unit
    ): Outcome {
        val release = UpdateChecker.fetch(log)
            ?: return Outcome.Failed("the release feed could not be read")

        if (!AppVersion.isNewer(release.version, currentVersion)) {
            log("Update: ${release.version} is not newer than $currentVersion")
            return Outcome.UpToDate
        }

        log("Update: ${release.version} is newer than $currentVersion")
        if (!autoInstall) return Outcome.Available(release.version)

        return when (val result = ApkInstaller.install(context, release, log)) {
            is ApkInstaller.Result.Installed -> Outcome.Installed(result.version)
            is ApkInstaller.Result.InstallerOpened -> Outcome.InstallerOpened(result.version)
            is ApkInstaller.Result.Failed -> {
                Log.w(TAG, "The update did not go through: ${result.reason}")
                Outcome.Failed(result.reason)
            }
        }
    }
}
