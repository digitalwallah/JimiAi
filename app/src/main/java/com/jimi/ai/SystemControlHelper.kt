package com.jimi.ai

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Direct system-level controls jo Accessibility ya app-launch se nahi ho sakte,
 * jaise flashlight — inhe seedha Android ke hardware/system APIs se control karna padta hai. */
object SystemControlHelper {

    private var isFlashOn = false

    /** Flashlight on/off karta hai. turnOn = true (on) ya false (off). */
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
}
