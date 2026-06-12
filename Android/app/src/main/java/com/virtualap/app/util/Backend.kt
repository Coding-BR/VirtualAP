package com.virtualap.app.util

import android.content.Context
import java.io.File

/**
 * Backend scripts live in the app's private files dir and are re-extracted from
 * APK assets on every launch, so the running scripts are always exactly the
 * version the installed APK shipped. An APK update can never leave stale
 * scripts behind (unlike the old copy-to-/data/local/virtualap approach, where
 * the installer only ran once and updates silently kept executing old code).
 *
 * Scripts are invoked via `sh <path>` under root, so no exec permission is
 * needed on app-data files. The heavy, rarely-changing payload (busybox,
 * rootfs) and all runtime state stay in /data/local/virtualap.
 */
object Backend {
    /** Full root-shell command prefix, e.g. "sh /data/data/<pkg>/files/backend/start-ap" */
    lateinit var startAp: String
        private set
    lateinit var vapSh: String
        private set

    fun install(context: Context) {
        val dir = File(context.filesDir, "backend").apply { mkdirs() }
        for (name in listOf("vap.sh", "start-ap")) {
            val out = File(dir, name)
            context.assets.open("backend/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        startAp = "sh ${File(dir, "start-ap").absolutePath}"
        vapSh = "sh ${File(dir, "vap.sh").absolutePath}"
    }
}
