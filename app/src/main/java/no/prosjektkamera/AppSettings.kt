package no.prosjektkamera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * App-wide settings, persisted in SharedPreferences and backed by Compose state
 * so both the settings screen and the camera react to changes immediately.
 */
class AppSettings private constructor(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Projects
    var sortByRecent by pref("sortByRecent", false)

    // Camera
    var cameraMode by pref("cameraMode", "PHOTO")
    var flash by pref("flash", ImageCapture.FLASH_MODE_AUTO)
    var wide by pref("wide", false) // 16:9 instead of 4:3
    var grid by pref("grid", true)
    var videoQuality by pref("videoQuality", "FHD")
    var volumeShutter by pref("volumeShutter", true)

    // Audio
    var highAudioQuality by pref("highAudioQuality", false)

    @Suppress("UNCHECKED_CAST")
    private fun <T> pref(key: String, default: T) = object : ReadWriteProperty<Any?, T> {
        val state = mutableStateOf(
            when (default) {
                is Boolean -> p.getBoolean(key, default)
                is Int -> p.getInt(key, default)
                else -> p.getString(key, default as String)
            } as T
        )

        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            p.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> putString(key, value as String)
                }
            }.apply()
        }
    }

    companion object {
        val videoQualities = listOf("HD" to Quality.HD, "FHD" to Quality.FHD, "4K" to Quality.UHD)

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
