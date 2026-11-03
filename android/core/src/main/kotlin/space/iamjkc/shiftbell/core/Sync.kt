package space.iamjkc.shiftbell.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One thing that happened on one device, sent to the other. The wire format is in docs/sync.md and is
 * shared with the PC app: fields are plain strings and numbers in a fixed format, in this order.
 */
@Serializable
data class SyncEvent(
    val v: Int = 1,
    /** `ack`, `snooze`, `dayoff`, `alarm`, `test` or `testend`. */
    val type: String,
    /** 0.3's `TimeIn` / `TimeOut`; still written for the presets so a 0.3 device understands. */
    val kind: String? = null,
    /** The alarm's id, for `ack`, `snooze` and `test`. */
    val alarm: String? = null,
    val date: String,
    val at: String,
    val until: String? = null,
    val snoozes: Int? = null,
    val off: Boolean? = null,
    /** `test` and `testend` only: which test alarm, so stopping it on one device stops that one elsewhere. */
    val id: String? = null,
    /** `alarm` only: the whole definition, added, edited or deleted. */
    val def: AlarmWire? = null,
    val from: String = "phone",
) {
    /** The alarm an event is about: its id, or for a 0.3 event, the preset its kind names. */
    val alarmId: String?
        get() = alarm ?: when (kind) {
            "TimeIn" -> Alarm.TIME_IN_ID
            "TimeOut" -> Alarm.TIME_OUT_ID
            else -> null
        }

    /** A test from the other device that should ring here now. */
    fun isFreshTestFrom(otherDevice: String, now: LocalDateTime): Boolean {
        if (type != "test" || from != otherDevice || id.isNullOrEmpty()) return false
        val sent = try {
            LocalDateTime.parse(at, TIME)
        } catch (_: DateTimeParseException) {
            return false
        }
        return Duration.between(sent, now).abs() <= TEST_FRESH_FOR
    }

    companion object {
        /** A test alarm older than this when it arrives is not rung: it was over long ago. */
        val TEST_FRESH_FOR: Duration = Duration.ofMinutes(2)

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        private fun legacyKind(alarm: Alarm): String? = when (alarm.id) {
            Alarm.TIME_IN_ID -> "TimeIn"
            Alarm.TIME_OUT_ID -> "TimeOut"
            else -> null
        }

        /** A test alarm started here; the other device rings it too. Never recorded in the state. */
        fun test(alarm: Alarm, at: LocalDateTime, id: String) = SyncEvent(
            type = "test", kind = legacyKind(alarm), alarm = alarm.id, date = DATE.format(at), at = TIME.format(at), id = id,
        )

        /** The test alarm with this id was stopped here, so stop it there too. */
        fun testEnd(id: String, at: LocalDateTime) =
            SyncEvent(type = "testend", date = DATE.format(at), at = TIME.format(at), id = id)

        fun newTestId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun ack(alarm: Alarm, date: LocalDate, at: LocalDateTime) =
            SyncEvent(type = "ack", kind = legacyKind(alarm), alarm = alarm.id, date = DATE.format(date), at = TIME.format(at))

        fun snoozed(alarm: Alarm, date: LocalDate, at: LocalDateTime, until: LocalDateTime, snoozes: Int) = SyncEvent(
            type = "snooze", kind = legacyKind(alarm), alarm = alarm.id, date = DATE.format(date), at = TIME.format(at),
            until = TIME.format(until), snoozes = snoozes,
        )

        fun dayOff(date: LocalDate, at: LocalDateTime, off: Boolean) =
            SyncEvent(type = "dayoff", date = DATE.format(date), at = TIME.format(at), off = off)

        /** An alarm was added, edited or deleted here. */
        fun changed(alarm: Alarm) = SyncEvent(
            type = "alarm", date = DATE.format(alarm.updatedAt), at = TIME.format(alarm.updatedAt), def = AlarmWire.from(alarm),
        )
    }
}

/**
 * An alarm definition as it travels between devices: plain strings in fixed formats, the same field names and
 * order as the PC app, so both sides read each other byte for byte (docs/decisions/0002-alarm-list.md).
 */
@Serializable
data class AlarmWire(
    val id: String,
    val role: String = AlarmRole.Custom.name,
    val name: String = "",
    val enabled: Boolean = true,
    /** `HH:mm`. */
    val ringAt: String,
    val deadline: String? = null,
    val days: List<String> = emptyList(),
    /** `yyyy-MM-dd` for a one-off alarm. */
    val date: String? = null,
    val strict: Boolean = true,
    val link: String = "",
    val armedAt: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
) {
    /** Null for a definition that does not parse; it is skipped, never fatal. */
    fun toAlarm(): Alarm? = try {
        val role = AlarmRole.entries.firstOrNull { it.name == role }
        if (id.isEmpty() || role == null) {
            null
        } else {
            Alarm(
                id = id,
                role = role,
                name = name,
                enabled = enabled,
                ringAt = LocalTime.parse(ringAt, CLOCK),
                deadline = deadline?.let { LocalTime.parse(it, CLOCK) },
                days = days.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name.equals(day, ignoreCase = true) } },
                date = date?.let { LocalDate.parse(it, SyncEvent.DATE) },
                strict = strict,
                link = link,
                armedAt = runCatching { LocalDateTime.parse(armedAt, SyncEvent.TIME) }.getOrDefault(Alarm.NEVER_ARMED),
                updatedAt = LocalDateTime.parse(updatedAt, SyncEvent.TIME),
                deleted = deleted,
            ).normalized()
        }
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Day names as the PC writes them: `Monday`, not `MONDAY`. */
        private fun dayName(day: DayOfWeek) = day.name.lowercase().replaceFirstChar { it.uppercase() }

        fun from(alarm: Alarm) = AlarmWire(
            id = alarm.id,
            role = alarm.role.name,
            name = alarm.name,
            enabled = alarm.enabled,
            ringAt = CLOCK.format(alarm.ringAt),
            deadline = alarm.deadline?.let { CLOCK.format(it) },
            days = alarm.days.map(::dayName),
            date = alarm.date?.let { SyncEvent.DATE.format(it) },
            strict = alarm.strict,
            link = alarm.link,
            armedAt = SyncEvent.TIME.format(alarm.armedAt),
            updatedAt = SyncEvent.TIME.format(alarm.updatedAt),
            deleted = alarm.deleted,
        )
    }
}

/** The channel id, access token and key, all derived from the pairing secret with HKDF-SHA256. */
class SyncKeys private constructor(val code: String, val channel: String, val token: String, internal val key: ByteArray) {
    companion object {
        private const val PREFIX = "SB1-"
        private val url = Base64.getUrlEncoder().withoutPadding()

        /** Null for anything that is not a v1 pairing code. Spaces and line breaks from copy-paste are ignored. */
        fun fromCode(code: String?): SyncKeys? {
            val text = code.orEmpty().filterNot { it.isWhitespace() }
            if (!text.startsWith(PREFIX)) return null
            val secret = try {
                Base64.getUrlDecoder().decode(text.substring(PREFIX.length))
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (secret.size != 32) return null

            val prk = hmac(ByteArray(32), secret)
            // HKDF expand: every output here fits in one SHA-256 block, so T(1) is all of it.
            fun derive(info: String, length: Int) = hmac(prk, info.toByteArray() + 1.toByte()).copyOf(length)
            return SyncKeys(
                text,
                derive("shiftbell/v1/channel", 16).joinToString("") { "%02x".format(it) },
                url.encodeToString(derive("shiftbell/v1/token", 32)),
                derive("shiftbell/v1/key", 32),
            )
        }

        fun newCode(): String = PREFIX + url.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}

/** AES-256-GCM with the channel bound in as associated data, so the relay cannot move events between channels. */
object SyncCrypto {
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun seal(event: SyncEvent, keys: SyncKeys): String = seal(event, keys, ByteArray(NONCE).also { SecureRandom().nextBytes(it) })

    internal fun seal(event: SyncEvent, keys: SyncKeys, nonce: ByteArray): String {
        val cipher = cipher(Cipher.ENCRYPT_MODE, keys, nonce)
        val sealed = cipher.doFinal(json.encodeToString(SyncEvent.serializer(), event).toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + sealed)
    }

    /** Null for anything that does not decrypt and parse: a damaged or foreign event is skipped, never fatal. */
    fun open(data: String, keys: SyncKeys): SyncEvent? = try {
        val bytes = Base64.getUrlDecoder().decode(data)
        if (bytes.size < NONCE + TAG_BITS / 8) {
            null
        } else {
            val plain = cipher(Cipher.DECRYPT_MODE, keys, bytes.copyOfRange(0, NONCE)).doFinal(bytes, NONCE, bytes.size - NONCE)
            json.decodeFromString(SyncEvent.serializer(), plain.decodeToString()).takeIf { it.v == 1 }
        }
    } catch (_: IllegalArgumentException) {
        null // Bad base64, and SerializationException is one of these too.
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun cipher(mode: Int, keys: SyncKeys, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD("shiftbell/v1/${keys.channel}".toByteArray())
        }
}

/** Folds events into the state and settings. Idempotent and order-independent, so replays and overlaps are harmless. */
object SyncMerge {
    /** Applies an ack, snooze or day off to the state. Returns the same instance when nothing changed. */
    fun apply(state: ShiftbellState, event: SyncEvent): ShiftbellState {
        val date = parse { LocalDate.parse(event.date, SyncEvent.DATE) } ?: return state
        val at = time(event.at) ?: return state

        return when (event.type) {
            "ack" -> {
                val id = event.alarmId ?: return state
                val record = state.find(date)?.of(id) ?: AlarmRecord()
                val existing = record.acknowledgedAt
                if (existing != null && existing <= at) return state
                state.update(date) { it.with(id, record.copy(acknowledgedAt = at, snoozedUntil = null)) }
            }
            "snooze" -> {
                val id = event.alarmId ?: return state
                val until = time(event.until) ?: return state
                val record = state.find(date)?.of(id) ?: AlarmRecord()
                if (record.acknowledgedAt != null) return state
                val current = record.snoozedUntil
                val next = record.copy(
                    snoozedUntil = if (current == null || until > current) until else current,
                    snoozes = maxOf(record.snoozes, event.snoozes ?: 0),
                )
                if (next == record) return state
                state.update(date) { it.with(id, next) }
            }
            "dayoff" -> {
                val off = event.off ?: return state
                val changedAt = state.find(date)?.dayOffChangedAt
                if (changedAt != null && changedAt >= at) return state
                state.update(date) { it.copy(dayOff = off, dayOffChangedAt = at) }
            }
            else -> state
        }
    }

    /** Applies an `alarm` event to the list: the newest [Alarm.updatedAt] wins, ties keep what is there. */
    fun apply(settings: ShiftbellSettings, event: SyncEvent): ShiftbellSettings {
        if (event.type != "alarm") return settings
        val incoming = event.def?.toAlarm() ?: return settings
        val existing = settings.alarms.orEmpty().firstOrNull { it.id == incoming.id }
        return if (existing != null && existing.updatedAt >= incoming.updatedAt) settings else settings.with(incoming)
    }

    private fun time(text: String?): LocalDateTime? = text?.let { parse { LocalDateTime.parse(it, SyncEvent.TIME) } }

    private inline fun <T> parse(read: () -> T): T? = try {
        read()
    } catch (_: DateTimeParseException) {
        null
    }
}
