package com.sudisoft.lightoff

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * 简易音效：AudioTrack 现场合成短音（方波类），无需任何音频资源文件。
 */
class SoundKit {

    var enabled: Boolean = true

    private val handler = Handler(Looper.getMainLooper())

    /** 落子音：点亮用高音，熄灭用低音 */
    fun press(on: Boolean) = tone(if (on) 540.0 else 400.0, 60)

    fun undo() = tone(300.0, 90)

    fun bad() = tone(170.0, 160)

    /** 胜利小旋律 */
    fun win() {
        val notes = doubleArrayOf(523.0, 659.0, 784.0, 1047.0)
        notes.forEachIndexed { i, f ->
            handler.postDelayed({ tone(f, 180, 0.22) }, i * 110L)
        }
    }

    private fun tone(freq: Double, ms: Int, volume: Double = 0.22) {
        if (!enabled) return
        val sampleRate = 44100
        val count = (sampleRate * ms / 1000).coerceAtLeast(1)
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val env = 1.0 - i.toDouble() / count // 简单线性衰减，避免爆音
            val v = sin(2.0 * PI * freq * i / sampleRate) * volume * env * Short.MAX_VALUE
            samples[i] = v.toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.setNotificationMarkerPosition(count)
        track.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    t?.stop()
                    t?.release()
                }

                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            }
        )
        track.write(samples, 0, samples.size)
        track.play()
    }
}
