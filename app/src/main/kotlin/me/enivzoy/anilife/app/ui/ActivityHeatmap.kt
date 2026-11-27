package me.enivzoy.anilife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.enivzoy.anilife.app.data.HEATMAP_WEEKS
import me.enivzoy.anilife.app.data.activityStreak
import me.enivzoy.anilife.app.data.heatLevel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val HeatAlphas = floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f)
private val HeatColor = Color(0xFFFFB74D)

/**
 * The last [HEATMAP_WEEKS] weeks, a column per week and a row per weekday, as Android's
 * ActivityHeatmap draws it. Brighter is busier relative to the busiest day shown.
 */
@Composable
fun ActivityHeatmap(days: Map<Long, Int>, fromAccount: Boolean, today: LocalDate = LocalDate.now()) {
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstDay = weekStart.minusWeeks((HEATMAP_WEEKS - 1).toLong())
    val window = (0 until HEATMAP_WEEKS * 7).map { firstDay.plusDays(it.toLong()) }
    val max = window.maxOf { days[it.toEpochDay()] ?: 0 }.coerceAtLeast(1)
    val activeDays = window.count { !it.isAfter(today) && (days[it.toEpochDay()] ?: 0) > 0 }
    val streak = activityStreak(days, today.toEpochDay())
    val empty = AniLifeColors.Foreground.copy(alpha = 0.07f)
    val cell = 14.dp
    val gap = 3.dp

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your activity", color = AniLifeColors.Foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (fromAccount) "Last $HEATMAP_WEEKS weeks · AniList activity and episodes watched here"
            else "Last $HEATMAP_WEEKS weeks · episodes finished on this PC",
            color = AniLifeColors.MutedForeground,
            fontSize = 12.sp,
        )
        Row(Modifier.padding(start = 36.dp)) {
            (0 until HEATMAP_WEEKS).forEach { week ->
                val monday = firstDay.plusWeeks(week.toLong())
                val startsMonth = week == 0 || monday.month != monday.minusWeeks(1).month
                Box(Modifier.width(cell + gap)) {
                    if (startsMonth) {
                        Text(
                            monday.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                            color = AniLifeColors.MutedForeground,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0 until 7).forEach { weekday ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf("Mon", "", "Wed", "", "Fri", "", "Sun")[weekday],
                        color = AniLifeColors.MutedForeground,
                        fontSize = 10.sp,
                        modifier = Modifier.width(36.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        (0 until HEATMAP_WEEKS).forEach { week ->
                            val date = firstDay.plusDays((week * 7 + weekday).toLong())
                            val level = heatLevel(days[date.toEpochDay()] ?: 0, max)
                            Box(
                                Modifier.size(cell).clip(RoundedCornerShape(3.dp)).background(
                                    when {
                                        date.isAfter(today) -> Color.Transparent
                                        level == 0 -> empty
                                        else -> HeatColor.copy(alpha = HeatAlphas[level])
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (streak) {
                    0 -> "No streak yet"
                    1 -> "1-day streak"
                    else -> "$streak-day streak"
                },
                color = if (streak > 0) HeatColor else AniLifeColors.MutedForeground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text("Less", color = AniLifeColors.MutedForeground, fontSize = 10.sp)
            (0..4).forEach { level ->
                Box(
                    Modifier.padding(start = 3.dp).size(10.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (level == 0) empty else HeatColor.copy(alpha = HeatAlphas[level])),
                )
            }
            Text("More", color = AniLifeColors.MutedForeground, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            if (activeDays == 0 && !fromAccount) "Watch an episode here to start your activity history."
            else "$activeDays active ${if (activeDays == 1) "day" else "days"} in the last $HEATMAP_WEEKS weeks",
            color = AniLifeColors.MutedForeground,
            fontSize = 12.sp,
        )
    }
}
