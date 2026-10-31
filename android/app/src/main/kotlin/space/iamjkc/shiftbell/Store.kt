package space.iamjkc.shiftbell

import android.content.Context
import android.util.AtomicFile
import space.iamjkc.shiftbell.core.Codec
import space.iamjkc.shiftbell.core.ShiftbellSettings
import space.iamjkc.shiftbell.core.ShiftbellState
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/**
 * Settings and state as two JSON files in device-protected storage, so an alarm can be read and rung
 * after a reboot before the phone is first unlocked. Writes are atomic: a crash mid-write keeps the old file.
 */
object Store {
    private val lock = Any()

    fun settings(context: Context): ShiftbellSettings = synchronized(lock) {
        Codec.decodeSettings(read(file(context, SETTINGS)))
    }

    fun state(context: Context, now: LocalDateTime = LocalDateTime.now()): ShiftbellState = synchronized(lock) {
        val text = read(file(context, STATE))
        val state = Codec.decodeState(text, now)
        if (text == null) write(file(context, STATE), Codec.encodeState(state))
        state
    }

    /**
     * Saves settings. Moving a ring time re-arms from now, so pulling the time-in earlier than the current
     * time does not fire a "late" alarm the moment it is saved.
     */
    fun saveSettings(context: Context, settings: ShiftbellSettings, now: LocalDateTime = LocalDateTime.now()) {
        synchronized(lock) {
            val old = settings(context)
            val normalized = settings.normalized()
            write(file(context, SETTINGS), Codec.encodeSettings(normalized))
            if (old.timeIn.ringAt != normalized.timeIn.ringAt || old.timeOut.ringAt != normalized.timeOut.ringAt) {
                write(file(context, STATE), Codec.encodeState(state(context, now).copy(armedSince = now)))
            }
        }
        Scheduler.reschedule(context)
    }

    /** Pairs with a PC (or unpairs with ""). A new channel starts with an empty outbox and reads from the start. */
    fun pair(context: Context, code: String) {
        saveSettings(context, settings(context).copy(syncCode = code))
        updateState(context) { it.copy(outbox = emptyList(), syncSince = 0) }
    }

    /** Applies [change] to the saved state under the lock, so two writers can never lose each other's change. */
    fun updateState(context: Context, change: (ShiftbellState) -> ShiftbellState) {
        synchronized(lock) {
            val now = LocalDateTime.now()
            write(file(context, STATE), Codec.encodeState(change(state(context, now)).prune(now.toLocalDate())))
        }
        Scheduler.reschedule(context)
    }

    private fun file(context: Context, name: String) =
        AtomicFile(File(context.createDeviceProtectedStorageContext().filesDir, name))

    private fun read(file: AtomicFile): String? =
        try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }

    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private const val SETTINGS = "settings.json"
    private const val STATE = "state.json"
}
