package space.iamjkc.shiftbell

import android.content.Context
import android.util.AtomicFile
import space.iamjkc.shiftbell.core.Alarm
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

    fun saveSettings(context: Context, settings: ShiftbellSettings) {
        updateSettings(context) { settings }
    }

    /** Applies [change] to the saved settings under the lock, so a sync merge and an edit never lose each other. */
    fun updateSettings(context: Context, change: (ShiftbellSettings) -> ShiftbellSettings) {
        synchronized(lock) {
            write(file(context, SETTINGS), Codec.encodeSettings(change(settings(context)).normalized()))
        }
        Scheduler.reschedule(context)
    }

    /**
     * Adds, edits, turns on or off, or deletes (a tombstone) one alarm, and returns it as saved. Moving when it rings
     * re-arms it, so a time pulled earlier than now does not fire a "late" alarm the moment it is saved.
     */
    fun saveAlarm(context: Context, edited: Alarm, now: LocalDateTime = LocalDateTime.now()): Alarm {
        var saved = edited
        updateSettings(context) { settings ->
            val before = settings.alarms.orEmpty().firstOrNull { it.id == edited.id }
            val armedAt = if (before == null || edited.scheduleDiffers(before)) now else before.armedAt
            saved = edited.copy(updatedAt = now, armedAt = armedAt).normalized()
            settings.with(saved)
        }
        return saved
    }

    /** Pairs with a PC (or unpairs with ""). A new channel starts with an empty outbox and reads from the start. */
    fun pair(context: Context, code: String) {
        updateSettings(context) { it.copy(syncCode = code) }
        updateState(context) { it.copy(outbox = emptyList(), syncSince = 0, alarmsPublished = false) }
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
