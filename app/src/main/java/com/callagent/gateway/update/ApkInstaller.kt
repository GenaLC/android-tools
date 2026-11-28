package com.callagent.gateway.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.callagent.gateway.RootShell
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Putting a downloaded build over the running one.
 *
 * The application is installed by the Magisk module, so it is a system app.
 * An update lands beside it and keeps the privileges the module granted —
 * Android grants an updated system app the privileged permissions its system
 * copy already holds — so the audio bridge and the modem rights survive the
 * update.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** How long the download and the install may take. */
    private const val INSTALL_TIMEOUT_MS = 180_000L

    /** What happened. */
    sealed class Result {
        /** The build is over the old one; the phone restarts the application. */
        data class Installed(val version: String) : Result()

        /** The build is downloaded and checked; the system installer waits. */
        data class InstallerOpened(val version: String) : Result()

        /** Nothing was changed. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Download the release and put it over the running application.
     *
     * The build is checked before it is installed: it has to be the same
     * application, signed with the same key.  A file that is not is not
     * installed, whatever it says on the feed.
     */
    fun install(
        context: Context,
        release: UpdateChecker.Release,
        log: (String) -> Unit
    ): Result {
        val apk = File(context.cacheDir, "update-${AppVersion.normalize(release.version)}.apk")
        try {
            log("Update: downloading ${release.version} (${release.sizeBytes / 1024} KB)")
            download(release.apkUrl, apk)
            log("Update: downloaded ${apk.length() / 1024} KB")
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            return Result.Failed("the build could not be downloaded (${e.message})")
        }

        val refusal = verify(context, apk)
        if (refusal != null) {
            log("Update: refused — $refusal")
            return Result.Failed(refusal)
        }

        // Root: the build goes over the running one without a screen in the
        // way.  The application is a system app, so the update is put beside
        // the module's copy and the privileges survive it.
        //
        // The build is copied out of the application's own storage first:
        // a package manager that is asked to replace a package with a file
        // that lives inside that package's own data directory is a shape it
        // is not expected to see, and /data/local/tmp is where such a file
        // belongs.
        val staged = "/data/local/tmp/gateway-update.apk"
        RootShell.execForOutput(
            "cp ${apk.absolutePath} $staged && chmod 644 $staged",
            INSTALL_TIMEOUT_MS
        )
        val stagedOk = RootShell.execForOutput("ls -l $staged", 20_000)
            .contains("gateway-update.apk")
        if (!stagedOk) {
            log("Update: the build could not be staged for the install")
            return Result.Failed("the build could not be staged for the install")
        }

        val output = RootShell.execForOutput(
            "pm install -r $staged",
            INSTALL_TIMEOUT_MS
        )
        RootShell.exec("rm -f $staged", 20_000)
        if (output.contains("Success", ignoreCase = true)) {
            log("Update: ${release.version} installed over the running build")
            return Result.Installed(release.version)
        }
        log("Update: the install did not go through (${output.take(120)})")

        // Without root the system's own installer takes over: the build is
        // handed to it and the user confirms.  That path needs the
        // "install unknown apps" permission for this application.
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            log("Update: ${release.version} handed to the system installer")
            Result.InstallerOpened(release.version)
        } catch (e: Exception) {
            Log.w(TAG, "The system installer could not be opened: ${e.message}")
            Result.Failed("the system installer could not be opened (${e.message})")
        }
    }

    /** Fetch the build to a file. */
    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "devicetools")
        try {
            conn.getInputStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            if (conn is java.net.HttpURLConnection) conn.disconnect()
        }
    }

    /**
     * Whether the downloaded build is the same application, signed the same way.
     *
     * Returns null when it is, or the reason it is not.  The signing key is
     * the check that matters: it is what says the file came from the same
     * builder as the application being replaced, and the platform would
     * refuse the install anyway if the keys differed.
     */
    private fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archive = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return "the downloaded file is not a package"

        if (archive.packageName != context.packageName) {
            return "the downloaded build is a different application (${archive.packageName})"
        }

        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            null
        } ?: return "the running application could not be read"

        val a = signersOf(archive)
        val b = signersOf(installed)
        if (a.isEmpty() || b.isEmpty()) return "the signing key could not be read"
        if (a != b) return "the build is signed with another key — refused"
        return null
    }

    /** The signing certificates of a package, as text to compare. */
    private fun signersOf(info: PackageInfo): List<String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            val array = signing?.apkContentsSigners ?: signing?.signingCertificateHistory
            array?.map { it.toCharsString() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toCharsString() } ?: emptyList()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Signing certificates could not be read: ${e.message}")
        emptyList()
    }
}
