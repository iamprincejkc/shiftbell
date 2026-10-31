package space.iamjkc.shiftbell

import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.core.net.toUri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import space.iamjkc.shiftbell.core.AlarmCopy
import java.time.LocalDateTime

/**
 * The ringing screen. Shows over the lock screen and turns the screen on. Back does nothing; the only ways
 * out are typing the phrase, a limited snooze, or "Let me do it now", which comes back after three minutes.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The volume keys adjust the alarm volume while this is up, so a too-loud alarm can be turned down.
        volumeControlStream = AudioManager.STREAM_ALARM
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            val session by Ringing.session.collectAsStateWithLifecycle()
            val current = session
            LaunchedEffect(current == null) {
                if (current == null) finish()
            }
            // Opened from the notification while stepped aside, it shows without sound so you can type the phrase.
            if (current != null) {
                AlarmScreen(
                    session = current,
                    onConfirm = { RingService.confirm(this) },
                    onSnooze = { RingService.snooze(this) },
                    onStepAside = {
                        RingService.stepAside(this)
                        finish()
                    },
                    onOpenAttendance = { openAttendance(current.settings.attendanceLink) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Ringing.screenShowing = true
    }

    override fun onStop() {
        Ringing.screenShowing = false
        super.onStop()
    }

    /** Unlocks first if needed (the browser cannot open over the lock screen), then steps aside so you can punch. */
    private fun openAttendance(link: String) {
        val open = {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            RingService.stepAside(this)
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    open()
                }
            })
        } else {
            open()
        }
    }
}

@Composable
internal fun AlarmScreen(
    session: RingSession,
    onConfirm: () -> Unit,
    onSnooze: () -> Unit,
    onStepAside: () -> Unit,
    onOpenAttendance: () -> Unit,
    /** The wall clock; screenshots pass a fixed morning. */
    clock: () -> LocalDateTime = LocalDateTime::now,
) {
    val now by produceState(clock()) {
        while (true) {
            delay(1_000)
            value = clock()
        }
    }
    val copy = AlarmCopy.of(session.alarm, session.settings, now)
    val accent = Palette.accent(copy.tone)
    val hasLink = session.settings.attendanceLink.isNotEmpty()
    val requireTyping = session.settings.requireTypedConfirmation
    var typed by rememberSaveable { mutableStateOf("") }
    val matches = AlarmCopy.matches(typed, session.alarm.kind)

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Night)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), radius = 1400f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Bell(accent)
            Spacer(Modifier.height(24.dp))
            Surface(color = accent, shape = RoundedCornerShape(50)) {
                Text(
                    if (session.isTest) "TEST · ${copy.tag}" else copy.tag,
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Palette.Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(copy.headline, color = Palette.OnNight, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(keepTimesWhole(copy.detail), color = Palette.OnNightSoft, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(copy.clock, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(28.dp))

            val wide = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(54.dp)
            if (hasLink) {
                Button(
                    onClick = onOpenAttendance,
                    modifier = wide,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Palette.Ink),
                ) { Text("Open attendance", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = onStepAside,
                modifier = wide,
                shape = RoundedCornerShape(14.dp),
                colors = if (!hasLink) {
                    ButtonDefaults.buttonColors(containerColor = accent, contentColor = Palette.Ink)
                } else {
                    ButtonDefaults.buttonColors(containerColor = Palette.NightButton, contentColor = Palette.OnNight)
                },
            ) { Text("Let me do it now", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.height(20.dp))

            if (requireTyping) {
                Surface(
                    Modifier.fillMaxWidth().widthIn(max = 480.dp),
                    color = Palette.NightCard,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Palette.NightLine),
                ) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            buildAnnotatedString {
                                append("${copy.ask} Type ")
                                withStyle(SpanStyle(color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)) {
                                    append(copy.phrase)
                                }
                                append(" to turn this off.")
                            },
                            color = Palette.OnNightSoft,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(copy.phrase, color = Palette.OnNightFaint) },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { if (matches) onConfirm() }),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Palette.OnNight,
                                unfocusedTextColor = Palette.OnNight,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Palette.NightLine,
                                cursorColor = accent,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        ConfirmButton(copy.confirmLabel, accent, enabled = matches, onConfirm)
                    }
                }
            } else {
                ConfirmButton(copy.confirmLabel, accent, enabled = true, onConfirm)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSnooze, enabled = session.alarm.snoozesLeft > 0) {
                Text(AlarmCopy.snooze(session.settings, session.alarm), color = if (session.alarm.snoozesLeft > 0) Palette.OnNightSoft else Palette.OnNightFaint)
            }
            Spacer(Modifier.height(16.dp))
            Text("Shiftbell · this stays until you confirm", color = Palette.OnNightFaint, fontSize = 12.sp)
        }
    }
}

/** "8:00 AM" never breaks across lines, which left a lone "AM." under the sentence. */
private fun keepTimesWhole(text: String): String = text.replace(Regex("(\\d) (AM|PM)"), "$1 $2")

@Composable
private fun ConfirmButton(label: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Palette.Ink,
            disabledContainerColor = Palette.NightButton,
            disabledContentColor = Palette.OnNightFaint,
        ),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** The bell with a slow pulse ring, like the PC alarm. */
@Composable
private fun Bell(accent: Color) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val progress by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(104.dp)
                .scale(1f + 0.4f * progress)
                .alpha(0.45f * (1f - progress))
                .background(accent, CircleShape),
        )
        Box(Modifier.size(104.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.ic_bell),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                colorFilter = ColorFilter.tint(Palette.Ink),
            )
        }
    }
}
