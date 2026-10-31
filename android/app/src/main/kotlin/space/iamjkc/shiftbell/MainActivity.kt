package space.iamjkc.shiftbell

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import space.iamjkc.shiftbell.core.AlarmCopy
import space.iamjkc.shiftbell.core.AlarmKind
import space.iamjkc.shiftbell.core.AlarmPlanner
import space.iamjkc.shiftbell.core.AlarmSettings
import space.iamjkc.shiftbell.core.ShiftbellSettings
import space.iamjkc.shiftbell.core.ShiftbellState
import space.iamjkc.shiftbell.core.SyncEvent
import space.iamjkc.shiftbell.core.SyncKeys
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Settings, today's status, and a checklist of everything Android must allow for the alarm to get through. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Palette.Ink, background = Palette.Paper, surface = Palette.Card)) {
                Home()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is one more chance to catch a missed alarm and to repair the schedule.
        Scheduler.reschedule(this)
        RingService.ringIfDue(this)
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Store.settings(context)) }
    var state by remember { mutableStateOf(Store.state(context)) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var checks by remember { mutableIntStateOf(0) }
    var synced by remember { mutableStateOf(PhoneSync.lastSuccess) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** After a sync round, on the main thread: show what the PC changed. */
    fun refresh() = main.post {
        state = Store.state(context)
        synced = PhoneSync.lastSuccess
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        settings = Store.settings(context)
        state = Store.state(context)
        now = LocalDateTime.now()
        checks++
        if (PhoneSync.paired(context)) PhoneSync.start(context) { refresh() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            state = Store.state(context)
            now = LocalDateTime.now()
        }
    }
    // While the app is open, check the PC every 5 s, so a test pressed there rings here too.
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

    fun save(next: ShiftbellSettings) {
        Store.saveSettings(context, next)
        settings = Store.settings(context)
        state = Store.state(context)
        now = LocalDateTime.now()
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    LaunchedEffect(Unit) {
        if (!notificationsAllowed(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Shiftbell", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text("Time in and time out, impossible to miss. Rings on silent too.", color = Palette.InkSoft, fontSize = 15.sp)

        StatusCard(settings, state, now) { off ->
            val at = LocalDateTime.now()
            Store.updateState(context) { it.setDayOff(at.toLocalDate(), off, at) }
            PhoneSync.send(context, SyncEvent.dayOff(at.toLocalDate(), at, off))
            state = Store.state(context)
        }
        PairCard(settings.syncCode, synced) { code ->
            Store.pair(context, code)
            settings = Store.settings(context)
            if (code.isNotEmpty()) PhoneSync.start(context) { refresh() }
        }
        // Re-read the checklist whenever the app comes back from a settings screen.
        key(checks) { SetupCard(onAskNotifications = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) }

        AlarmCard("Time in", settings.timeIn, showDeadline = true) { save(settings.copy(timeIn = it)) }
        AlarmCard("Time out", settings.timeOut, showDeadline = false) { save(settings.copy(timeOut = it)) }
        DaysCard(settings.workDays) { save(settings.copy(workDays = it)) }
        LinkCard(settings.attendanceLink) { save(settings.copy(attendanceLink = it)) }
        BehaviourCard(settings) { save(it) }

        Card {
            CardTitle("Try it")
            Text("Rings now, exactly like the real thing, and records nothing.", color = Palette.InkSoft, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { RingService.test(context, AlarmKind.TimeIn) }, Modifier.weight(1f)) { Text("Test time in", color = Palette.Ink) }
                OutlinedButton(onClick = { RingService.test(context, AlarmKind.TimeOut) }, Modifier.weight(1f)) { Text("Test time out", color = Palette.Ink) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

@Composable
private fun StatusCard(settings: ShiftbellSettings, state: ShiftbellState, now: LocalDateTime, onDayOff: (Boolean) -> Unit) {
    val today = now.toLocalDate()
    val day = state.find(today)
    val next = AlarmPlanner.next(settings, state, now)
    Card {
        Text("NEXT ALARM", color = Palette.InkFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            next?.let { "${label(it.kind)} · ${DAY.format(it.at)}" } ?: "No alarm scheduled",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Palette.Line)
        Spacer(Modifier.height(10.dp))
        if (today.dayOfWeek in settings.workDays) {
            for (kind in AlarmKind.entries) {
                val alarm = settings.of(kind)
                if (!alarm.enabled) continue
                val done = day?.of(kind)?.acknowledgedAt
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (done != null) Palette.Good else Palette.Line)
                    Spacer(Modifier.width(10.dp))
                    Text(label(kind), Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
                    Text(
                        if (done != null) "Done ${AlarmCopy.time(done)}" else "Rings ${AlarmCopy.time(alarm.ringAt)}",
                        color = if (done != null) Palette.Good else Palette.InkSoft,
                        fontSize = 15.sp,
                    )
                }
            }
            SwitchRow("Day off today", "Holiday or leave: no alarms today.", day?.dayOff == true, onDayOff)
        } else {
            Text("Not a work day. No alarms today.", color = Palette.InkSoft, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SetupCard(onAskNotifications: () -> Unit) {
    val context = LocalContext.current
    val items = listOfNotNull(
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
    val missing = items.count { !it.ok }
    Card {
        CardTitle(if (missing == 0) "Ready to ring" else "Allow $missing more to be sure it rings")
        Text(
            "Silent and vibrate mode never stop it: it plays on the alarm volume. Do Not Disturb lets alarms through unless you turned that off.",
            color = Palette.InkSoft,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        for (item in items) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The dot belongs to the title line, not the middle of a wrapped description.
                Box(Modifier.align(Alignment.Top).padding(top = 6.dp)) { Dot(if (item.ok) Palette.Good else Palette.Amber) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(item.why, color = Palette.InkSoft, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                if (item.ok) {
                    Text("On", color = Palette.Good, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                } else {
                    Button(
                        onClick = item.fix,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber, contentColor = Palette.Ink),
                    ) { Text("Allow") }
                }
            }
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
                "Confirming, snoozing or a day off on either one applies to both. " +
                    (synced?.let { "Last synced ${AlarmCopy.time(it)}." } ?: "Not reached the sync server yet; alarms still ring on their own."),
                color = Palette.InkSoft,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onPair("") }) { Text("Unlink", color = Palette.Ink) }
            return@Card
        }

        CardTitle("Link with your PC")
        Text(
            "So confirming on one stops the other. On the PC open Shiftbell settings, press Pair a phone, then scan the code.",
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

private fun notificationsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun open(context: Context, action: String) {
    val intent = Intent(action, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
}

@Composable
private fun AlarmCard(title: String, alarm: AlarmSettings, showDeadline: Boolean, onChange: (AlarmSettings) -> Unit) {
    Card {
        SwitchRow(title, if (alarm.enabled) "Rings every work day." else "Off: this alarm never rings.", alarm.enabled) {
            onChange(alarm.copy(enabled = it))
        }
        if (alarm.enabled) {
            HorizontalDivider(color = Palette.Line)
            TimeRow("Rings at", alarm.ringAt) { onChange(alarm.copy(ringAt = it)) }
            if (showDeadline) {
                val deadline = alarm.deadline ?: alarm.ringAt
                TimeRow("Due by", deadline) { onChange(alarm.copy(deadline = it)) }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, value: LocalTime, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                TimePickerDialog(context, { _, h, m -> onPick(LocalTime.of(h, m)) }, value.hour, value.minute, false).show()
            }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
        Text(AlarmCopy.time(value), color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DaysCard(days: List<DayOfWeek>, onChange: (List<DayOfWeek>) -> Unit) {
    Card {
        CardTitle("Work days")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (day in DayOfWeek.entries) {
                val on = day in days
                Surface(
                    onClick = { onChange(if (on) days - day else days + day) },
                    shape = CircleShape,
                    color = if (on) Palette.Ink else Palette.Card,
                    border = BorderStroke(1.dp, if (on) Palette.Ink else Palette.Line),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            day.name.take(1),
                            color = if (on) Color.White else Palette.InkSoft,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(link: String, onChange: (String) -> Unit) {
    var text by remember(link) { mutableStateOf(link) }
    Card {
        CardTitle("Attendance page")
        Text("The alarm's \"Open attendance\" button opens this, e.g. your HR portal's punch page. Leave it empty to hide the button.", color = Palette.InkSoft, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text.trim() != link) onChange(text) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.trim() != link) onChange(text) }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line),
        )
    }
}

@Composable
private fun BehaviourCard(settings: ShiftbellSettings, onChange: (ShiftbellSettings) -> Unit) {
    Card {
        CardTitle("While it rings")
        Stepper("Snooze length", "${settings.snoozeMinutes} min", settings.snoozeMinutes, 1, 60) { onChange(settings.copy(snoozeMinutes = it)) }
        Stepper("Snoozes allowed", "${settings.maxSnoozes}", settings.maxSnoozes, 0, 10) { onChange(settings.copy(maxSnoozes = it)) }
        Stepper("Missed alarms ring until", "${settings.catchUpHours} h late", settings.catchUpHours, 0, 12) {
            onChange(settings.copy(catchUpHours = it))
        }
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))

        var floor by remember(settings.minimumAlarmVolumePercent) { mutableStateOf(settings.minimumAlarmVolumePercent.toFloat()) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Raise alarm volume to at least", Modifier.weight(1f), color = Palette.Ink, fontSize = 15.sp)
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
        Text(
            "Only if your alarm volume is lower, and put back after. It starts soft and builds up over a minute either way.",
            color = Palette.InkSoft,
            fontSize = 13.sp,
        )
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(vertical = 6.dp))
        SwitchRow("Vibrate", "Works on silent too.", settings.vibrate) { onChange(settings.copy(vibrate = it)) }
        SwitchRow(
            "Type to stop it",
            "You type \"done\", so it cannot be swiped away unread.",
            settings.requireTypedConfirmation,
        ) { onChange(settings.copy(requireTypedConfirmation = it)) }
    }
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
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun label(kind: AlarmKind) = if (kind == AlarmKind.TimeIn) "Time in" else "Time out"
