package space.iamjkc.shiftbell

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import space.iamjkc.shiftbell.core.AlarmKind
import space.iamjkc.shiftbell.core.SyncCrypto
import space.iamjkc.shiftbell.core.SyncEvent
import space.iamjkc.shiftbell.core.SyncKeys
import space.iamjkc.shiftbell.core.SyncMerge
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Sync with the PC through the relay on the owner's site (docs/sync.md): send the outbox, read what the PC did,
 * merge it. Rounds run one at a time on their own thread. Every failure is "no change": no network must never
 * stop an alarm from ringing.
 */
object PhoneSync {
    /** The owner's portfolio on Netlify, which holds the relay's storage (the Cloudflare Pages copy does not). */
    private const val RELAY = "https://iamprincejkc.netlify.app/api/shiftbell/"
    private const val TIMEOUT_MS = 4_000

    /** Reads start this far before the last server time, so an event that landed out of order is not skipped. */
    private const val OVERLAP_MS = 120_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "shiftbell-sync").apply { isDaemon = true } }

    @Volatile
    var lastSuccess: LocalDateTime? = null
        private set

    fun paired(context: Context): Boolean = SyncKeys.fromCode(Store.settings(context).syncCode) != null

    /** Queues a local change for the PC and sends it straight away. */
    fun send(context: Context, event: SyncEvent) {
        if (!paired(context)) return
        Store.updateState(context) { it.copy(outbox = it.outbox + event) }
        start(context)
    }

    /** Starts a round in the background. */
    fun start(context: Context, then: (Boolean) -> Unit = {}): Future<*> =
        worker.submit { then(round(context.applicationContext)) }

    /**
     * Runs a round and waits for it, at most [limitMs]. For "about to ring": a confirm on the PC a moment ago
     * should stop this alarm, but a slow network must not delay it by more than that.
     */
    fun roundWithin(context: Context, limitMs: Long) {
        runCatching { start(context).get(limitMs, TimeUnit.MILLISECONDS) }
    }

    /** True when something the PC did changed the state here. */
    private fun round(context: Context): Boolean {
        val keys = SyncKeys.fromCode(Store.settings(context).syncCode) ?: return false
        val before = Store.state(context)

        // Read first: when an alarm is about to ring, this is the part that matters.
        val pulled = pull(keys, maxOf(0, before.syncSince - OVERLAP_MS))
        val sent = before.outbox.takeWhile { push(keys, it) }

        var merged = false
        Store.updateState(context) { current ->
            var next = current.copy(outbox = current.outbox.filterNot { it in sent })
            if (pulled != null) {
                for (event in pulled.events) {
                    val applied = SyncMerge.apply(next, event)
                    merged = merged || applied != next
                    next = applied
                }
                next = next.copy(syncSince = pulled.now)
            }
            next
        }
        if (pulled != null) {
            lastSuccess = LocalDateTime.now()
            handleTests(context, pulled.events)
        }
        return merged
    }

    /** Test ids already rung or started here, so the overlapping reads never ring the same test twice. */
    private val seenTests = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun markTestSeen(id: String) {
        seenTests.add(id)
    }

    /** A test pressed on the PC rings here too; one stopped there stops here. Tests never touch the state. */
    private fun handleTests(context: Context, events: List<SyncEvent>) {
        val now = LocalDateTime.now()
        val ended = events.filter { it.type == "testend" }.mapNotNull { it.id }.toSet()
        val running = Ringing.session.value
        if (running?.testId != null && running.testId in ended) RingService.endElsewhere(context)

        for (event in events) {
            val id = event.id ?: continue
            val kind = AlarmKind.entries.firstOrNull { it.name == event.kind } ?: continue
            if (event.isFreshTestFrom("pc", now) && seenTests.add(id) && id !in ended && Ringing.session.value == null) {
                RingService.testFromPc(context, kind, id)
            }
        }
    }

    private class Pulled(val events: List<SyncEvent>, val now: Long)

    private fun pull(keys: SyncKeys, since: Long): Pulled? {
        val body = request(keys, "GET", "${keys.channel}?since=$since", null) ?: return null
        return try {
            val root = JSONObject(body)
            val list = root.getJSONArray("events")
            val events = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.optString("data")?.takeIf { it.isNotEmpty() }?.let { SyncCrypto.open(it, keys) }
            }
            Pulled(events, root.getLong("now"))
        } catch (_: JSONException) {
            null
        }
    }

    private fun push(keys: SyncKeys, event: SyncEvent): Boolean =
        request(keys, "POST", keys.channel, """{"data":"${SyncCrypto.seal(event, keys)}"}""") != null

    /** The response body on a 2xx, otherwise null. */
    private fun request(keys: SyncKeys, method: String, path: String, body: String?): String? {
        val connection = URL(RELAY + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${keys.token}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes().decodeToString() } else null
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
