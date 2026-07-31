package com.jimi.ai

import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler

object NoiseSuppressionHelper {

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    /**
     * Call this with the audioSessionId of your recording session
     * (e.g. from AudioRecord.getAudioSessionId(), or the session used
     * by SpeechRecognizer internally if you have access to it).
     */
    fun enable(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        noiseSuppressor?.release()
        echoCanceler?.release()
        gainControl?.release()
        noiseSuppressor = null
        echoCanceler = null
        gainControl = null
    }
}
