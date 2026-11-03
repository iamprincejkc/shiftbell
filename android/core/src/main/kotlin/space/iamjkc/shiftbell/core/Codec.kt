package space.iamjkc.shiftbell.core

import kotlinx.serialization.json.Json
import java.time.LocalDateTime

/** Reads and writes settings and state as JSON. A file that cannot be read never stops the alarm: defaults win. */
object Codec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 0.3 fields are read for migration, then left null and so never written back.
        explicitNulls = false
        prettyPrint = true
    }

    fun encodeSettings(settings: ShiftbellSettings): String = json.encodeToString(ShiftbellSettings.serializer(), settings)

    fun decodeSettings(text: String?): ShiftbellSettings = decode(text) {
        json.decodeFromString(ShiftbellSettings.serializer(), it)
    }?.normalized() ?: ShiftbellSettings().normalized()

    fun encodeState(state: ShiftbellState): String = json.encodeToString(ShiftbellState.serializer(), state)

    /** A missing or broken state file arms from [now], so nothing scheduled before it rings late. */
    fun decodeState(text: String?, now: LocalDateTime): ShiftbellState = decode(text) {
        json.decodeFromString(ShiftbellState.serializer(), it).migrate()
    } ?: ShiftbellState(armedSince = now)

    private fun <T> decode(text: String?, read: (String) -> T): T? {
        if (text.isNullOrBlank()) return null
        return try {
            read(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one of these.
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
