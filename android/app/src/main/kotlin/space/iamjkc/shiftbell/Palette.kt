package space.iamjkc.shiftbell

import androidx.compose.ui.graphics.Color
import space.iamjkc.shiftbell.core.AlarmTone

/** The PC app's colours, so both alarms look like the same product. */
object Palette {
    val Night = Color(0xFF0B0D12)
    val NightCard = Color(0xFF141821)
    val NightLine = Color(0xFF262B36)
    val NightButton = Color(0xFF1C212C)
    val OnNight = Color(0xFFF4F5F7)
    val OnNightSoft = Color(0xFFB9BEC9)
    val OnNightFaint = Color(0xFF6E7482)

    val Paper = Color(0xFFF7F5F0)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFE7E3DA)
    val Ink = Color(0xFF14161C)
    val InkSoft = Color(0xFF5B606B)
    val InkFaint = Color(0xFF8D919A)
    val Good = Color(0xFF2E8B57)

    val Amber = Color(0xFFF5A623)
    val Blue = Color(0xFF6CA8FF)
    val Red = Color(0xFFFF5A5F)

    fun accent(tone: AlarmTone): Color = when (tone) {
        AlarmTone.TimeIn -> Amber
        AlarmTone.TimeOut -> Blue
        AlarmTone.Late -> Red
    }
}
