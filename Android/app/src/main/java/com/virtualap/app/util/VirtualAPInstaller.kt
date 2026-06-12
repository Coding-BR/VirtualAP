package com.virtualap.app.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VirtualAPInstaller {

    /** Name of the rootfs tarball shipped in the installed APK, or null if missing. */
    fun bundledRootfsName(context: Context): String? =
        runCatching {
            context.assets.list("rootfs")?.firstOrNull { it.endsWith(".tar.xz") }
        }.getOrNull()

    /**
     * True when the APK ships a different rootfs tarball than the one last
     * extracted (the filename embeds the build date). Drives the re-run of the
     * setup flow after an app update - this is the whole "update backend"
     * mechanism; scripts themselves can never go stale (see Backend).
     */
    fun rootfsUpdateAvailable(context: Context): Boolean {
        val bundled = bundledRootfsName(context) ?: return false
        return PreferencesManager.getInstance(context).rootfsVersion != bundled
    }

    suspend fun install(
        context: Context,
        onProgress: (Int, String) -> Unit  // level, message
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val assetManager = context.assets
            val cacheDir = context.cacheDir

            // Step 0: A re-install (backend update) must not race a live backend.
            // The namespace holder keeps busybox executing - cp over a running
            // binary fails with ETXTBSY - and a running chroot keeps rootfs
            // binaries busy the same way. Best-effort stop of both.
            onProgress(Log.INFO, "Stopping any running AP...")
            Shell.cmd("${Backend.startAp} stop", "${Backend.vapSh} stop").exec()

            // Step 1: Create directories. Scripts are NOT deployed here anymore -
            // they run straight from the app's files dir (see Backend) so APK
            // updates always take effect. Remove stale copies from old versions.
            onProgress(Log.INFO, "Creating directories...")
            Shell.cmd(
                "mkdir -p ${Constants.VAP_DIR}/bin ${Constants.VAP_DIR}/logs ${Constants.VAP_DIR}/rootfs",
                "rm -f ${Constants.VAP_DIR}/vap.sh ${Constants.VAP_DIR}/start-ap"
            ).exec()

            // Step 2: Deploy busybox
            onProgress(Log.INFO, "Installing busybox...")
            deployAsset(context, "bin/busybox", "${Constants.VAP_DIR}/bin/busybox", cacheDir)
                ?.let { return@withContext Result.failure(it) }
            Shell.cmd("chmod 755 ${Constants.VAP_DIR}/bin/busybox").exec()

            // Step 3: Extract rootfs tarball
            onProgress(Log.INFO, "Extracting Alpine rootfs (this takes a moment)...")
            val tarAsset = bundledRootfsName(context)
                ?: return@withContext Result.failure(Exception("No rootfs tarball found in assets/rootfs/. Run build_rootfs.sh first and rebuild the APK."))

            val tempTar = File(cacheDir, tarAsset)
            assetManager.open("rootfs/$tarAsset").use { input ->
                tempTar.outputStream().use { input.copyTo(it) }
            }
            onProgress(Log.INFO, "Unpacking $tarAsset...")
            // Android's system tar has no xz support; use shipped busybox for both
            // decompression (xzcat) and extraction (tar), piped to avoid a temp .tar file.
            val extractResult = Shell.cmd(
                "${Constants.BUSYBOX} xzcat '${tempTar.absolutePath}'" +
                " | ${Constants.BUSYBOX} tar xf - -C '${Constants.VAP_DIR}/rootfs/' 2>&1"
            ).exec()
            tempTar.delete()
            if (!extractResult.isSuccess) {
                val errLines = (extractResult.out + extractResult.err)
                    .joinToString("\n").ifBlank { "no output — check busybox xzcat/tar support" }
                return@withContext Result.failure(Exception("rootfs extraction failed:\n$errLines"))
            }

            // Step 4: Verify
            onProgress(Log.INFO, "Verifying installation...")
            val ok = Shell.cmd(
                "test -x ${Constants.BUSYBOX} && test -f ${Constants.VAP_DIR}/rootfs/etc/alpine-release && echo ok"
            ).exec().out.any { it.contains("ok") }
            if (!ok) return@withContext Result.failure(Exception("Verification failed: busybox or rootfs missing after install"))

            // Record which rootfs is now deployed so future APK updates with a
            // newer tarball can trigger a re-install.
            PreferencesManager.getInstance(context).rootfsVersion = tarAsset

            onProgress(Log.INFO, "Installation complete!")
            Result.success(Unit)
        } catch (e: Exception) {
            onProgress(Log.ERROR, "Installation failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun deployAsset(context: Context, assetPath: String, destPath: String, cacheDir: File): Exception? {
        return try {
            val tmpFile = File(cacheDir, File(assetPath).name)
            context.assets.open(assetPath).use { input ->
                tmpFile.outputStream().use { input.copyTo(it) }
            }
            // Unlink first: overwriting a binary some process still executes
            // fails with ETXTBSY, but unlink+create always succeeds (the
            // running process keeps the old inode).
            val copyResult = Shell.cmd("rm -f $destPath && cp ${tmpFile.absolutePath} $destPath 2>&1").exec()
            tmpFile.delete()
            // FLAG_REDIRECT_STDERR sends stderr to .out - .err is always empty.
            if (!copyResult.isSuccess) Exception("Failed to deploy $assetPath: ${copyResult.out.joinToString("\n")}")
            else null
        } catch (e: Exception) {
            e
        }
    }
}
