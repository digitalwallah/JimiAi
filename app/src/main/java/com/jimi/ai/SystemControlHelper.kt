package com.jimi.ai

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent

object SystemControlHelper {

    private var isFlashOn = false

    fun setFlashlight(context: Context, turnOn: Boolean?): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false

            val newState = turnOn ?: !isFlashOn
            cameraManager.setTorchMode(cameraId, newState)
            isFlashOn = newState
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- VOLUME ----------

    fun adjustVolume(context: Context, direction: String): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (direction.lowercase()) {
                "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> {
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                }
                else -> return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setVolumePercent(context: Context, percent: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (maxVol * percent.coerceIn(0, 100) / 100.0).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- BRIGHTNESS ----------

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun requestWriteSettingsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun adjustBrightness(context: Context, direction: String? = null, percent: Int? = null): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val newValue = when {
                percent != null -> (255 * percent.coerceIn(4, 100) / 100.0).toInt()
                direction == "up" -> (current + 51).coerceAtMost(255)
                direction == "down" -> (current - 51).coerceAtLeast(10)
                else -> current
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, newValue)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- ROTATION LOCK ----------

    fun setRotationLock(context: Context, locked: Boolean): Boolean {
        if (!canWriteSettings(context)) return false
        return try {
            val target = if (locked) 0 else 1
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                target
            )
            val actual = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                -1
            )
            actual == target
        } catch (e: Exception) {
            false
        }
    }

    // ---------- MEDIA CONTROL ----------

    fun controlMedia(context: Context, action: String): Boolean {
        val keyCode = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- ALARM ----------

    var lastAlarmError: String? = null
        private set

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String = "Jimi Alarm"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                lastAlarmError = "Koi Clock/Alarm app is device pe nahi mila jo alarm set karne ka intent handle kare"
                return false
            }
            context.startActivity(intent)
            lastAlarmError = null
            true
        } catch (e: Exception) {
            lastAlarmError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    // ---------- TIMER ----------

    fun setTimer(context: Context, seconds: Int, message: String = "Jimi Timer"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
