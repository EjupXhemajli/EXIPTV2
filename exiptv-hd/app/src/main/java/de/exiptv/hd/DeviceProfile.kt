package de.exiptv.hd

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecList
import android.os.Build
import de.exiptv.hd.core.Diagnostics

/**
 * Was für ein Gerät ist das?
 *
 * Die Antwort bestimmt Kachelgrößen, Abstände, die Bedienlogik und wie viel
 * gepuffert werden darf. Ermittelt wird sie genau einmal und im Hintergrund:
 * Die Abfrage der Codec-Liste dauert auf günstigen Boxen mehrere hundert
 * Millisekunden — im Hauptthread beim Start wäre das direkt sichtbar, und genau
 * dort stand sie in der Vorgängerversion.
 */
object DeviceProfile {

    @Volatile var isTelevision: Boolean = false
        private set

    @Volatile var isLowMemory: Boolean = false
        private set

    @Volatile var totalMemoryMb: Int = 0
        private set

    @Volatile var supportsHevc: Boolean = false
        private set

    @Volatile var supportsAv1: Boolean = false
        private set

    @Volatile var detected: Boolean = false
        private set

    /**
     * Die Gerätebauart allein — sofort verfügbar und ohne nennenswerte Kosten.
     * Wird schon beim ersten Bildaufbau gebraucht, deshalb getrennt von der
     * teuren Codec-Abfrage.
     */
    fun quickDetect(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val byUiMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val byConfig =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        val pm = context.packageManager
        val byFeature = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        isTelevision = byUiMode || byConfig || byFeature || noTouch
        return isTelevision
    }

    /** Die vollständige Ermittlung inklusive Codec-Abfrage. Gehört in den Hintergrund. */
    fun detect(context: Context) {
        quickDetect(context)

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            totalMemoryMb = (info.totalMem / (1024L * 1024L)).toInt()
            isLowMemory = am?.isLowRamDevice == true || (totalMemoryMb in 1..1279)
        }

        runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (codec in codecs) {
                if (codec.isEncoder) continue
                for (type in codec.supportedTypes) {
                    when (type.lowercase()) {
                        "video/hevc" -> supportsHevc = true
                        "video/av01" -> supportsAv1 = true
                    }
                }
            }
        }

        detected = true
        Diagnostics.info(
            "Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                "${totalMemoryMb} MB RAM" +
                (if (isTelevision) ", Fernseher" else "") +
                (if (isLowMemory) ", wenig Speicher" else "") +
                (if (supportsHevc) ", HEVC" else "") +
                (if (supportsAv1) ", AV1" else "")
        )
    }
}
