package space.iamjkc.shiftbell

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The same beep, beep, high-beep pattern as the PC app, synthesised and looped in memory.
 * It plays as an ALARM sound, on the alarm volume, which the ringer's silent and vibrate modes do not touch.
 */
class AlarmTone {
    private var track: AudioTrack? = null

    fun start(gain: Float) {
        stop()
        val samples = pattern()
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
                setVolume(gain)
                play()
            }
    }

    fun setGain(gain: Float) {
        track?.setVolume(gain)
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 22050

        /** About 1.6 s per loop, with short fades so the edges never click. */
        fun pattern(): ShortArray {
            val steps = listOf(880.0 to 0.16, 0.0 to 0.08, 880.0 to 0.16, 0.0 to 0.08, 1318.5 to 0.34, 0.0 to 0.78)
            val out = ArrayList<Short>()
            for ((hz, seconds) in steps) {
                val count = (seconds * SAMPLE_RATE).toInt()
                val fade = min(count / 2, SAMPLE_RATE / 200).coerceAtLeast(1)
                for (i in 0 until count) {
                    if (hz == 0.0) {
                        out.add(0)
                        continue
                    }
                    val envelope = min(1.0, min(i, count - 1 - i) / fade.toDouble())
                    val t = i / SAMPLE_RATE.toDouble()
                    // A touch of the octave cuts through street noise better than a pure sine.
                    val value = sin(2 * PI * hz * t) * 0.8 + sin(4 * PI * hz * t) * 0.2
                    out.add((value * envelope * Short.MAX_VALUE * 0.9).toInt().toShort())
                }
            }
            return out.toShortArray()
        }
    }
}
