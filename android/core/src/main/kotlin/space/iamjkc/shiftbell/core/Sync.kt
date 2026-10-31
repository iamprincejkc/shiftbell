package space.iamjkc.shiftbell.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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
    /** `ack`, `snooze` or `dayoff`. */
    val type: String,
    val kind: String? = null,
    val date: String,
    val at: String,
    val until: String? = null,
    val snoozes: Int? = null,
    val off: Boolean? = null,
    /** `test` and `testend` only: which test alarm, so stopping it on one device stops that one elsewhere. */
    val id: String? = null,
    val from: String = "phone",
) {
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

        /** A test alarm started here; the other device rings it too. Never recorded in the state. */
        fun test(kind: AlarmKind, at: LocalDateTime, id: String) =
            SyncEvent(type = "test", kind = kind.name, date = DATE.format(at), at = TIME.format(at), id = id)

        /** The test alarm with this id was stopped here, so stop it there too. */
        fun testEnd(id: String, at: LocalDateTime) =
            SyncEvent(type = "testend", date = DATE.format(at), at = TIME.format(at), id = id)

        fun newTestId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        fun ack(kind: AlarmKind, date: LocalDate, at: LocalDateTime) =
            SyncEvent(type = "ack", kind = kind.name, date = DATE.format(date), at = TIME.format(at))

        fun snoozed(kind: AlarmKind, date: LocalDate, at: LocalDateTime, until: LocalDateTime, snoozes: Int) = SyncEvent(
            type = "snooze", kind = kind.name, date = DATE.format(date), at = TIME.format(at), until = TIME.format(until), snoozes = snoozes,
        )

        fun dayOff(date: LocalDate, at: LocalDateTime, off: Boolean) =
            SyncEvent(type = "dayoff", date = DATE.format(date), at = TIME.format(at), off = off)
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

/** Folds events into the state. Idempotent and order-independent, so replays and overlaps are harmless. */
object SyncMerge {
    fun apply(state: ShiftbellState, event: SyncEvent): ShiftbellState {
        val date = parse { LocalDate.parse(event.date, SyncEvent.DATE) } ?: return state
        val at = time(event.at) ?: return state
        val day = state.find(date) ?: DayLog(date)

        val changed = when (event.type) {
            "ack" -> {
                val kind = kind(event.kind) ?: return state
                val record = day.of(kind)
                val existing = record.acknowledgedAt
                if (existing != null && existing <= at) return state
                day.with(kind, record.copy(acknowledgedAt = at, snoozedUntil = null))
            }
            "snooze" -> {
                val kind = kind(event.kind) ?: return state
                val until = time(event.until) ?: return state
                val record = day.of(kind)
                if (record.acknowledgedAt != null) return state
                val current = record.snoozedUntil
                val next = record.copy(
                    snoozedUntil = if (current == null || until > current) until else current,
                    snoozes = maxOf(record.snoozes, event.snoozes ?: 0),
                )
                if (next == record) return state
                day.with(kind, next)
            }
            "dayoff" -> {
                val off = event.off ?: return state
                val changedAt = day.dayOffChangedAt
                if (changedAt != null && changedAt >= at) return state
                day.copy(dayOff = off, dayOffChangedAt = at)
            }
            else -> return state
        }
        return state.copy(days = state.days.filter { it.date != date } + changed)
    }

    private fun kind(name: String?): AlarmKind? = AlarmKind.entries.firstOrNull { it.name == name }

    private fun time(text: String?): LocalDateTime? = text?.let { parse { LocalDateTime.parse(it, SyncEvent.TIME) } }

    private inline fun <T> parse(read: () -> T): T? = try {
        read()
    } catch (_: DateTimeParseException) {
        null
    }
}
