package space.iamjkc.shiftbell

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import space.iamjkc.shiftbell.core.Alarm
import space.iamjkc.shiftbell.core.AlarmCopy
import space.iamjkc.shiftbell.core.AlarmPlanner
import space.iamjkc.shiftbell.core.AlarmRole
import space.iamjkc.shiftbell.core.ShiftbellSettings
import space.iamjkc.shiftbell.core.ShiftbellState
import space.iamjkc.shiftbell.core.SyncEvent
import space.iamjkc.shiftbell.core.SyncKeys
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home is only what matters daily: the next alarm and the list. Setup collapses to one line while something is
 * missing, and pairing and the "while it rings" settings live behind the gear.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Palette.Ink, background = Palette.Paper, surface = Palette.Card)) {
                ShiftbellScreens()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is one more chance to catch a missed alarm and to repair the schedule.
        Scheduler.reschedule(this)
        RingService.ringIfDue(this)
        PhoneSync.publish(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftbellScreens(clock: () -> LocalDateTime = LocalDateTime::now) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Store.settings(context)) }
    var state by remember { mutableStateOf(Store.state(context)) }
    var now by remember { mutableStateOf(clock()) }
    var checks by remember { mutableIntStateOf(0) }
    var synced by remember { mutableStateOf(PhoneSync.lastSuccess) }
    var inSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun reload() {
        settings = Store.settings(context)
        state = Store.state(context)
        now = clock()
    }

    /** After a sync round, on the main thread: show what the PC changed. */
    fun refresh() = main.post {
        reload()
        synced = PhoneSync.lastSuccess
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reload()
        checks++
        if (PhoneSync.paired(context)) PhoneSync.start(context) { refresh() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            reload()
        }
    }
    // While the app is open, check the PC every 5 s, so a test or an alarm added there shows up here.
    LifecycleResumeEffect(Unit) {
        var open = true
        val poll = object : Runnable {
            override fun run() {
                if (!open) return
                if (PhoneSync.paired(context)) PhoneSync.start(context) { refresh() }
                main.postDelayed(this, 5_000)
            }
        }
        main.postDelayed(poll, 5_000)
        onPauseOrDispose {
            open = false
            main.removeCallbacks(poll)
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    LaunchedEffect(Unit) {
        if (!notificationsAllowed(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun saveAlarm(alarm: Alarm) {
        PhoneSync.saveAlarm(context, alarm)
        reload()
    }

    key(checks) {
        val missing = checks(context) { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }.count { !it.ok }
        if (inSettings) {
            BackHandler { inSettings = false }
            SettingsScreen(
                settings = settings,
                state = state,
                now = now,
                synced = synced,
                onAskNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onBack = { inSettings = false },
                onChange = {
                    Store.saveSettings(context, it)
                    reload()
                },
                onDayOff = { off ->
                    val at = clock()
                    Store.updateState(context) { it.setDayOff(at.toLocalDate(), off, at) }
                    PhoneSync.send(context, SyncEvent.dayOff(at.toLocalDate(), at, off))
                    reload()
                },
                onPair = { code ->
                    Store.pair(context, code)
                    reload()
                    if (code.isNotEmpty()) {
                        PhoneSync.publish(context)
                        PhoneSync.start(context) { refresh() }
                    }
                },
            )
        } else {
            HomeScreen(
                settings = settings,
                state = state,
                now = now,
                missingPermissions = missing,
                onSettings = { inSettings = true },
                onToggle = { alarm, on -> saveAlarm(alarm.copy(enabled = on)) },
                onEdit = {
                    editingIsNew = false
                    editing = it
                },
                onAdd = {
                    editingIsNew = true
                    editing = Alarm(
                        id = Alarm.newId(), ringAt = LocalTime.of(now.hour, 0).plusHours(1), days = Alarm.WEEKDAYS, strict = false,
                    )
                },
            )
        }
    }

    editing?.let { alarm ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Card,
        ) {
            AlarmEditor(
                alarm = alarm,
                isNew = editingIsNew,
                onSave = {
                    saveAlarm(it)
                    editing = null
                },
                onDelete = {
                    saveAlarm(alarm.copy(deleted = true))
                    editing = null
                },
                onTest = { RingService.test(context, it) },
            )
        }
    }
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.US)

@Composable
private fun HomeScreen(
    settings: ShiftbellSettings,
    state: ShiftbellState,
    now: LocalDateTime,
    missingPermissions: Int,
    onSettings: () -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onEdit: (Alarm) -> Unit,
    onAdd: () -> Unit,
) {
    val next = AlarmPlanner.next(settings, state, now)
    val dayOff = state.find(now.toLocalDate())?.dayOff == true
    Box(Modifier.fillMaxSize().background(Palette.Paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shiftbell", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                IconText("⚙", onSettings, "Settings")
            }
            Spacer(Modifier.height(18.dp))
            Text("NEXT", color = Palette.InkFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(next?.let { AlarmCopy.time(it.at) } ?: "None", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Text(
                when {
                    dayOff -> "Day off today. Nothing rings until tomorrow."
                    next == null -> "No alarm in the next two weeks."
                    else -> "${next.alarm.name}, ${day(next.at, now)}" + (next.alarm.deadline?.let { " · due ${AlarmCopy.time(it)}" } ?: "")
                },
                color = Palette.InkSoft,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))

            if (missingPermissions > 0) {
                Surface(onClick = onSettings, color = Color(0xFFFFF4DF), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFFF3D9A6))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(Palette.Amber)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "$missingPermissions permission${if (missingPermissions == 1) "" else "s"} needed to ring reliably",
                            Modifier.weight(1f),
                            color = Palette.Ink,
                            fontSize = 14.sp,
                        )
                        Text("Fix", color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            for (alarm in settings.active) {
                AlarmRow(alarm, onToggle = { onToggle(alarm, it) }, onClick = { onEdit(alarm) })
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(88.dp))
        }

        Button(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Color.White),
        ) { Text("+  Alarm", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

private fun day(at: LocalDateTime, now: LocalDateTime): String =
    when (at.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()) {
        0L -> "today"
        1L -> "tomorrow"
        else -> DAY_TIME.format(at)
    }

private fun accent(role: AlarmRole): Color = when (role) {
    AlarmRole.TimeIn -> Palette.Amber
    AlarmRole.TimeOut -> Palette.Blue
    AlarmRole.Custom -> Palette.Violet
}

@Composable
private fun AlarmRow(alarm: Alarm, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Palette.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(38.dp).background(accent(alarm.role), RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AlarmCopy.time(alarm.ringAt),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (alarm.enabled) Palette.Ink else Palette.InkFaint,
                )
                Text(
                    "${alarm.name} · ${AlarmCopy.repeat(alarm)}",
                    color = Palette.InkSoft,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ink, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun AlarmEditor(alarm: Alarm, isNew: Boolean, onSave: (Alarm) -> Unit, onDelete: () -> Unit, onTest: (Alarm) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(alarm.name) }
    var ringAt by remember { mutableStateOf(alarm.ringAt) }
    var deadline by remember { mutableStateOf(alarm.deadline) }
    var once by remember { mutableStateOf(alarm.date != null) }
    var date by remember { mutableStateOf(alarm.date ?: LocalDate.now()) }
    var days by remember { mutableStateOf(alarm.days.toSet()) }
    var strict by remember { mutableStateOf(alarm.strict) }
    var link by remember { mutableStateOf(alarm.link) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun result(): Alarm? {
        problem = when {
            !once && days.isEmpty() -> "Pick at least one day, or choose Once."
            link.isNotBlank() && !(link.trim().startsWith("http://") || link.trim().startsWith("https://")) ->
                "The link must start with https://"
            deadline != null && deadline!! < ringAt -> "Due by must be at or after the alarm time."
            else -> null
        }
        if (problem != null) return null
        return alarm.copy(
            name = name,
            ringAt = ringAt,
            deadline = deadline,
            date = if (once) date else null,
            days = if (once) alarm.days else days.toList(),
            strict = strict,
            link = link.trim(),
        ).normalized()
    }

    fun pickTime(start: LocalTime, onPick: (LocalTime) -> Unit) =
        TimePickerDialog(context, { _, h, m -> onPick(LocalTime.of(h, m)) }, start.hour, start.minute, false).show()

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            AlarmCopy.time(ringAt),
            Modifier.fillMaxWidth().clickable { pickTime(ringAt) { ringAt = it } },
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = Palette.Ink,
        )
        Text(
            if (isNew) "New alarm · tap the time to change it" else "Tap the time to change it",
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Palette.InkFaint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text("Standup, client call…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line),
        )
        Spacer(Modifier.height(12.dp))
        Segmented("Repeat", "Once", second = once) { once = it }
        Spacer(Modifier.height(10.dp))
        if (once) {
            OutlinedButton(
                onClick = { DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US).format(date), color = Palette.Ink) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (day in DayOfWeek.entries) {
                    val on = day in days
                    Surface(
                        onClick = { days = if (on) days - day else days + day },
                        shape = CircleShape,
                        color = if (on) Palette.Ink else Palette.Card,
                        border = BorderStroke(1.dp, if (on) Palette.Ink else Palette.Line),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(day.getDisplayName(TextStyle.NARROW, Locale.US), color = if (on) Color.White else Palette.InkSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Segmented("Type done", "Dismiss", second = !strict) { strict = !it }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Link (optional)") },
            placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line),
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clickable { pickTime(deadline ?: ringAt.plusMinutes(15)) { deadline = it } }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Due by (optional)", Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
            Text(deadline?.let { AlarmCopy.time(it) } ?: "None", color = Palette.InkSoft, fontSize = 15.sp)
            if (deadline != null) {
                TextButton(onClick = { deadline = null }) { Text("Clear", color = Palette.InkSoft) }
            }
        }
        problem?.let { Text(it, color = Palette.Red, fontSize = 13.sp) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { result()?.let(onSave) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Color.White),
        ) { Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { result()?.let(onTest) }) { Text("Ring it now", color = Palette.Ink) }
            if (!isNew && alarm.role == AlarmRole.Custom) {
                TextButton(onClick = onDelete) { Text("Delete", color = Palette.Red) }
            }
        }
    }
}

/** Two options side by side; [second] is whether the right one is selected. */
@Composable
private fun Segmented(first: String, secondLabel: String, second: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Card, RoundedCornerShape(12.dp))) {
        for ((label, isSecond) in listOf(first to false, secondLabel to true)) {
            val on = second == isSecond
            Surface(
                onClick = { onChange(isSecond) },
                color = if (on) Palette.Ink else Palette.Card,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (on) Palette.Ink else Palette.Line),
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) Color.White else Palette.InkSoft, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: ShiftbellSettings,
    state: ShiftbellState,
    now: LocalDateTime,
    synced: LocalDateTime?,
    onAskNotifications: () -> Unit,
    onBack: () -> Unit,
    onChange: (ShiftbellSettings) -> Unit,
    onDayOff: (Boolean) -> Unit,
    onPair: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Paper)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            IconText("✕", onBack, "Close settings")
        }

        Group("TODAY")
        Card {
            SwitchRow("Day off", "Holiday or leave: nothing rings today, on any linked device.", state.find(now.toLocalDate())?.dayOff == true, onDayOff)
        }

        Group("WHILE IT RINGS")
        Card {
            Stepper("Snooze length", "${settings.snoozeMinutes} min", settings.snoozeMinutes, 1, 60) { onChange(settings.copy(snoozeMinutes = it)) }
            Stepper("Snoozes allowed", "${settings.maxSnoozes}", settings.maxSnoozes, 0, 10) { onChange(settings.copy(maxSnoozes = it)) }
            Stepper("Missed alarms ring until", "${settings.catchUpHours} h late", settings.catchUpHours, 0, 12) { onChange(settings.copy(catchUpHours = it)) }
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))
            var floor by remember(settings.minimumAlarmVolumePercent) { mutableStateOf(settings.minimumAlarmVolumePercent.toFloat()) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lowest alarm volume", Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
                Text(if (floor.toInt() == 0) "Off" else "${floor.toInt()}%", color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = floor,
                // Snaps to 5% steps without drawing a tick for each one.
                onValueChange = { floor = (it / 5f).roundToInt() * 5f },
                onValueChangeFinished = { onChange(settings.copy(minimumAlarmVolumePercent = floor.toInt())) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Ink, inactiveTrackColor = Palette.Line),
            )
            Text("Raised to this only if your alarm volume is lower, and put back after.", color = Palette.InkSoft, fontSize = 13.sp)
            HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))
            SwitchRow("Vibrate", "Works on silent too.", settings.vibrate) { onChange(settings.copy(vibrate = it)) }
        }

        Group("DEVICES")
        PairCard(settings.syncCode, synced, onPair)

        Group("PERMISSIONS")
        Card {
            val items = checks(context, onAskNotifications)
            for (item in items) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(item.why, color = Palette.InkSoft, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (item.ok) {
                        Text("On", color = Palette.Good, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    } else {
                        Button(onClick = item.fix, colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Ink)) { Text("Allow") }
                    }
                }
            }
            Text(
                "Silent and vibrate mode never stop it: it plays on the alarm volume. Do Not Disturb lets alarms through unless you turned that off.",
                color = Palette.InkSoft,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Group(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), color = Palette.InkFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** A round, text-glyph button: the gear and the close cross. */
@Composable
private fun IconText(glyph: String, onClick: () -> Unit, description: String) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, color = Palette.InkSoft, fontSize = 22.sp, modifier = Modifier.semantics { contentDescription = description })
        }
    }
}

@Composable
private fun PairCard(code: String, synced: LocalDateTime?, onPair: (String) -> Unit) {
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    fun tryCode(text: String) {
        val keys = SyncKeys.fromCode(text)
        if (keys == null) {
            problem = "That is not a Shiftbell pairing code. It starts with SB1-."
        } else {
            problem = null
            typing = false
            typed = ""
            onPair(keys.code)
        }
    }

    Card {
        if (code.isNotEmpty()) {
            CardTitle("Linked with your PC")
            Text(
                "Alarms, confirms, snoozes and days off apply to both. " +
                    (synced?.let { "Last synced ${AlarmCopy.time(it)}." } ?: "Not reached the sync server yet; alarms still ring on their own."),
                color = Palette.InkSoft,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onPair("") }) { Text("Unlink", color = Palette.Ink) }
            return@Card
        }

        CardTitle("Link with PC")
        Text(
            "On the PC open Shiftbell, go to Devices, press Pair a phone, then scan the code.",
            color = Palette.InkSoft,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    GmsBarcodeScanning.getClient(
                        context,
                        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
                    ).startScan()
                        .addOnSuccessListener { tryCode(it.rawValue.orEmpty()) }
                        .addOnFailureListener { problem = "The scanner did not open. Enter the code instead." }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Color.White),
            ) { Text("Scan QR code") }
            OutlinedButton(onClick = { typing = !typing }) { Text("Enter code", color = Palette.Ink) }
        }
        if (typing) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("SB1-…") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { tryCode(typed) }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { tryCode(typed) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink, contentColor = Color.White),
            ) { Text("Link") }
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Palette.Red, fontSize = 13.sp)
        }
    }
}

private data class Check(val title: String, val why: String, val ok: Boolean, val fix: () -> Unit)

/** Everything Android must allow for the alarm to get through. */
private fun checks(context: Context, onAskNotifications: () -> Unit): List<Check> = listOfNotNull(
    Check(
        "Notifications",
        "The alarm rings through a notification. Without it, nothing can show.",
        notificationsAllowed(context),
        onAskNotifications,
    ),
    // Android 14 made this a separate switch; before that every app had it.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Check(
            "Full-screen alarm",
            "Lets the alarm take over the lock screen and wake the screen.",
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        ) { open(context, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
    } else {
        null
    },
    Check(
        "Alarms on time",
        "Exact alarms, so 7:45 means 7:45 and not \"sometime soon\".",
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
    ) { open(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
    Check(
        "Show over other apps",
        "So the alarm takes the whole screen even while you are using the phone, not just a banner.",
        Settings.canDrawOverlays(context),
    ) { open(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
)

private fun notificationsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun open(context: Context, action: String) {
    val intent = Intent(action, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
}

@Composable
private fun Stepper(label: String, shown: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
        StepButton("−", enabled = value > min) { onChange(value - 1) }
        Text(shown, Modifier.width(88.dp), color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        StepButton("+", enabled = value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Palette.Card,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Palette.Ink else Palette.Line, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Palette.InkSoft, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ink, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Palette.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
