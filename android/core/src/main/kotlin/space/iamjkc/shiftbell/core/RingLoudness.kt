package space.iamjkc.shiftbell.core

import java.time.Duration

/**
 * How loud the tone plays, as a share of the phone's alarm volume, for how long it has been ringing.
 * It starts soft so it never blasts the owner's ears (the PC version's first ring did), then climbs to the
 * alarm volume the owner chose, so a phone in a bag still gets heard.
 */
object RingLoudness {
    private val steps: List<Pair<Duration, Float>> = listOf(
        Duration.ZERO to 0.15f,
        Duration.ofSeconds(15) to 0.35f,
        Duration.ofSeconds(30) to 0.6f,
        Duration.ofSeconds(60) to 1.0f,
    )

    fun gain(ringing: Duration): Float = steps.last { ringing >= it.first }.second
}
