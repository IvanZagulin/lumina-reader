package com.lumina.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.BookFormat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val AdvBlue = Color(0xFF2563EB)
private val AdvIndigo = Color(0xFF6366F1)
private val AdvPurple = Color(0xFF8B5CF6)
private val AdvGreen = Color(0xFF10B981)
private val AdvAmber = Color(0xFFF59E0B)
private val AdvOrange = Color(0xFFF97316)
private val RuLocale = Locale("ru", "RU")

@Composable
fun AdvancedStatsPanel(
    base: ReadingStatsUiState,
    advanced: AdvancedStatsUiState
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "Расширенная аналитика",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Поведение, история и прогнозы на основе ваших реальных сессий.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (advanced.isLoading) {
            repeat(4) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                )
            }
            return@Column
        }

        WrappedCard(advanced)
        RhythmMatrixCard(advanced.rhythmMatrix)
        RegularityCard(advanced.regularity)
        SessionAnalyticsCard(advanced.sessionAnalytics)
        DayPartSpeedCard(advanced.speedByDayPart)
        BestMonthsCard(advanced.bestMonths)
        FormatStatsCard(advanced.formatStats)
        SeriesStatsCard(advanced.seriesStats)
        AuthorStatsCard(advanced.authorStats)
        CompletionCard(advanced.completionAnalytics)
        AbandonedBooksCard(advanced.abandonedBooks)
        BacklogCard(advanced.backlog)
        PredictionsCard(advanced.predictions)
        SelfComparisonCard(base, advanced)
    }
}

@Composable
private fun WrappedCard(state: AdvancedStatsUiState) {
    var yearly by remember { mutableStateOf(false) }
    val source = if (yearly) state.yearlyWrapped else state.monthlyWrapped
    var selectedKey by remember(source, yearly) { mutableStateOf(source.lastOrNull()?.key.orEmpty()) }
    val selected = source.firstOrNull { it.key == selectedKey } ?: source.lastOrNull()

    StatCard {
        Header("Reading Wrapped", "Итоги месяца или года одним экраном", Icons.Default.AutoAwesome)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !yearly, onClick = { yearly = false }, label = { Text("Месяцы") })
            FilterChip(selected = yearly, onClick = { yearly = true }, label = { Text("Годы") })
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(source, key = { it.key }) { item ->
                FilterChip(
                    selected = item.key == selected?.key,
                    onClick = { selectedKey = item.key },
                    label = { Text(if (yearly) item.title else formatMonth(YearMonth.parse(item.key))) }
                )
            }
        }
        selected?.let { wrapped ->
            Spacer(Modifier.height(14.dp))
            Text(
                if (yearly) wrapped.title else formatMonthLong(YearMonth.parse(wrapped.key)),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (wrapped.comparisonDurationPercent != null) {
                val value = wrapped.comparisonDurationPercent
                Text(
                    "${if (value >= 0) "+" else ""}$value% времени к предыдущему периоду",
                    color = if (value >= 0) AdvGreen else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(12.dp))
            MetricGrid(
                listOf(
                    "Время" to formatDuration(wrapped.durationSeconds),
                    "Слова" to formatNumber(wrapped.wordsRead),
                    "Страницы" to formatNumber(wrapped.estimatedPages),
                    "Завершено" to wrapped.completedBooks.toString(),
                    "Активных дней" to wrapped.activeDays.toString(),
                    "Средний темп" to "${wrapped.averageWpm} сл/мин"
                )
            )
            Spacer(Modifier.height(12.dp))
            InfoLine(Icons.Default.WorkspacePremium, "Книга периода", wrapped.topBookTitle)
            InfoLine(
                Icons.Default.CalendarMonth,
                "Лучший день",
                wrapped.bestDay?.let { "${formatDate(it)} · ${formatDuration(wrapped.bestDaySeconds)}" } ?: "—"
            )
        }
    }
}

@Composable
private fun RhythmMatrixCard(cells: List<RhythmMatrixCell>) {
    val max = cells.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    StatCard {
        Header("День × время суток", "Когда чтение действительно случается", Icons.Default.GridView)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(34.dp))
            AdvancedDayPart.entries.forEach {
                Text(
                    it.title.take(3),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        DayOfWeek.entries.forEach { day ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(weekdayShort(day), modifier = Modifier.width(34.dp), fontWeight = FontWeight.Bold)
                AdvancedDayPart.entries.forEach { part ->
                    val value = cells.firstOrNull { it.dayOfWeek == day && it.part == part }?.durationSeconds ?: 0
                    val ratio = value.toFloat() / max.toFloat()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (value == 0L) MaterialTheme.colorScheme.surfaceVariant
                                else AdvIndigo.copy(alpha = 0.18f + 0.82f * ratio)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (value > 0) {
                            Text(
                                formatDurationTiny(value),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (ratio > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        val peak = cells.maxByOrNull { it.durationSeconds }?.takeIf { it.durationSeconds > 0 }
        if (peak != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Пик: ${weekdayName(peak.dayOfWeek)} · ${peak.part.title.lowercase()}",
                color = AdvIndigo,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RegularityCard(score: RegularityScore) {
    StatCard {
        Header("Индекс регулярности", "Насколько чтение стало устойчивой привычкой", Icons.Default.TrackChanges)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Ring(score.score / 100f, AdvGreen, Modifier.size(92.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(score.score.toString(), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("из 100", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(score.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(
                    "≈ ${String.format(RuLocale, "%.1f", score.activeDaysPerWeek)} дня чтения в неделю",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Самый длинный перерыв: ${score.longestGapDays} дн.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SessionAnalyticsCard(data: SessionAnalytics) {
    val max = data.buckets.maxOfOrNull(SessionBucket::count)?.coerceAtLeast(1) ?: 1
    StatCard {
        Header("Сессии чтения", "Не только среднее, но и реальное распределение", Icons.Default.Timer)
        Spacer(Modifier.height(12.dp))
        MetricGrid(
            listOf(
                "Всего" to data.total.toString(),
                "Средняя" to formatDuration(data.averageSeconds),
                "Медиана" to formatDuration(data.medianSeconds),
                "Короткая" to formatDuration(data.shortestSeconds),
                "Длинная" to formatDuration(data.longestSeconds),
                "> 1 часа" to data.over60Minutes.toString()
            )
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            data.buckets.forEach { bucket ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(bucket.count.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height((70f * bucket.count / max.toFloat()).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(AdvBlue)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(bucket.label, fontSize = 9.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ">30 мин: ${data.over30Minutes} · >1 ч: ${data.over60Minutes} · >2 ч: ${data.over120Minutes}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayPartSpeedCard(data: List<DayPartSpeed>) {
    val max = data.maxOfOrNull { it.wordsPerMinute }?.coerceAtLeast(1) ?: 1
    val best = data.maxByOrNull { it.wordsPerMinute }?.takeIf { it.wordsPerMinute > 0 }
    StatCard {
        Header("Когда вы читаете быстрее", "Темп по времени суток", Icons.Default.Speed)
        Spacer(Modifier.height(12.dp))
        data.forEach { item ->
            BarRow(
                label = item.part.title,
                value = if (item.wordsPerMinute > 0) "${item.wordsPerMinute} сл/мин" else "—",
                progress = item.wordsPerMinute.toFloat() / max,
                color = when (item.part) {
                    AdvancedDayPart.MORNING -> AdvAmber
                    AdvancedDayPart.DAY -> AdvOrange
                    AdvancedDayPart.EVENING -> AdvPurple
                    AdvancedDayPart.NIGHT -> AdvIndigo
                }
            )
        }
        if (best != null) {
            Spacer(Modifier.height(8.dp))
            Text("Лучший темп: ${best.part.title.lowercase()}", color = AdvGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BestMonthsCard(records: BestMonthRecords) {
    StatCard {
        Header("Лучшие месяцы", "Исторические рекорды по разным метрикам", Icons.Default.EmojiEvents)
        Spacer(Modifier.height(12.dp))
        MetricGrid(
            listOf(
                "По времени" to monthWithValue(records.timeMonth, formatDuration(records.timeSeconds)),
                "По словам" to monthWithValue(records.wordsMonth, formatNumber(records.words)),
                "По книгам" to monthWithValue(records.booksMonth, records.books.toString()),
                "По активности" to monthWithValue(records.activeDaysMonth, "${records.activeDays} дн.")
            )
        )
    }
}

@Composable
private fun FormatStatsCard(data: List<FormatReadingStats>) {
    val max = data.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    StatCard {
        Header("Форматы", "Что вы на самом деле читаете", Icons.Default.Description)
        Spacer(Modifier.height(12.dp))
        data.forEach { item ->
            BarRow(
                label = formatLabel(item.format),
                value = "${formatDuration(item.durationSeconds)} · ${item.bookCount} книг",
                progress = item.durationSeconds.toFloat() / max,
                color = AdvBlue
            )
            Text(
                "Завершено ${item.completedCount} · средняя сессия ${formatDuration(item.averageSessionSeconds)}",
                modifier = Modifier.padding(start = 4.dp, bottom = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SeriesStatsCard(data: List<SeriesReadingStats>) {
    val visible = data.take(8)
    StatCard {
        Header("Книжные серии", "Прогресс и время по сериям", Icons.Default.CollectionsBookmark)
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) EmptyInside("Серий пока нет") else visible.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.completedBooks}/${item.totalBooks} книг · ${formatDuration(item.durationSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(6.dp).clip(CircleShape),
                        color = if (item.isComplete) AdvGreen else AdvIndigo,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("${item.progressPercent}%", fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
        }
        val completed = data.count(SeriesReadingStats::isComplete)
        Text(
            "Серий начато: ${data.size} · завершено полностью: $completed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AuthorStatsCard(data: List<AuthorReadingStats>) {
    val visible = data.take(8)
    val max = visible.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    StatCard {
        Header("Авторы", "К кому вы возвращаетесь чаще всего", Icons.Default.Person)
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) EmptyInside("Авторов пока недостаточно") else visible.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", modifier = Modifier.width(28.dp), fontWeight = FontWeight.ExtraBold, color = AdvPurple)
                Column(Modifier.weight(1f)) {
                    BarRow(
                        label = item.name,
                        value = "${item.bookCount} книг · ${formatDuration(item.durationSeconds)}",
                        progress = item.durationSeconds.toFloat() / max,
                        color = AdvPurple
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionCard(data: CompletionAnalytics) {
    StatCard {
        Header("Сколько занимает книга", "Время чтения и календарный путь до финала", Icons.Default.CheckCircle)
        Spacer(Modifier.height(12.dp))
        MetricGrid(
            listOf(
                "С историей" to data.completedWithHistory.toString(),
                "Среднее чтение" to formatDuration(data.averageReadingSeconds),
                "Календарно" to if (data.averageCalendarDays > 0) "${String.format(RuLocale, "%.1f", data.averageCalendarDays)} дн." else "—"
            )
        )
        Spacer(Modifier.height(12.dp))
        InfoLine(Icons.Default.Bolt, "Быстрее всего", "${data.fastestTitle} · ${formatDuration(data.fastestSeconds)}")
        InfoLine(Icons.Default.HourglassBottom, "Дольше всего", "${data.slowestTitle} · ${formatDuration(data.slowestSeconds)}")
    }
}

@Composable
private fun AbandonedBooksCard(data: List<AbandonedBookStats>) {
    StatCard {
        Header("Заброшенные книги", "Начаты, но не открывались больше 30 дней", Icons.Default.History)
        Spacer(Modifier.height(12.dp))
        if (data.isEmpty()) {
            EmptyInside("Заброшенных книг нет")
        } else {
            data.take(8).forEach { book ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${book.progressPercent}% · не открывалась ${book.daysSinceRead} дн.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${book.progressPercent}%", color = AdvOrange, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(9.dp))
            }
            if (data.size > 8) Text("Ещё ${data.size - 8}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BacklogCard(data: BacklogStats) {
    StatCard {
        Header("Книжный бэклог", "Сколько чтения уже лежит и ждёт своей судьбы", Icons.Default.Inventory2)
        Spacer(Modifier.height(10.dp))
        Text(data.unfinishedBooks.toString(), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
        Text("незавершённых книг", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            data.estimatedMonths?.let { "При текущем темпе этого примерно на $it мес." }
                ?: "Пока недостаточно завершённых книг для прогноза",
            fontWeight = FontWeight.Bold,
            color = AdvIndigo
        )
        Text(
            "Темп завершения: ${String.format(RuLocale, "%.1f", data.completedPerMonth)} книг/мес.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PredictionsCard(data: List<PredictionItem>) {
    StatCard {
        Header("Прогнозы", "Если нынешний ритм не решит внезапно исчезнуть", Icons.Default.OnlinePrediction)
        Spacer(Modifier.height(12.dp))
        data.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        Text(item.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(item.value, fontWeight = FontWeight.ExtraBold, color = AdvGreen, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun SelfComparisonCard(base: ReadingStatsUiState, advanced: AdvancedStatsUiState) {
    val month = advanced.monthlyWrapped.lastOrNull()
    val previous = advanced.monthlyWrapped.dropLast(1).lastOrNull()
    StatCard {
        Header("Относительно себя", "Текущий месяц против предыдущего", Icons.Default.CompareArrows)
        Spacer(Modifier.height(12.dp))
        if (month == null || previous == null) {
            EmptyInside("Нужно больше истории")
        } else {
            ComparisonRow("Время", month.durationSeconds, previous.durationSeconds, ::formatDuration)
            ComparisonRow("Слова", month.wordsRead, previous.wordsRead, ::formatNumber)
            ComparisonRow("Книги", month.completedBooks.toLong(), previous.completedBooks.toLong()) { it.toString() }
            Spacer(Modifier.height(10.dp))
            Text(
                "Текущий стрик: ${base.currentStreakDays} дн. · лучший: ${base.bestStreakDays} дн.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, current: Long, previous: Long, formatter: (Long) -> String) {
    val percent = if (previous <= 0) if (current > 0) 100 else 0 else
        (((current - previous).toDouble() / previous) * 100).roundToInt().coerceIn(-999, 999)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("${formatter(previous)} → ${formatter(current)}", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            "${if (percent > 0) "+" else ""}$percent%",
            color = if (percent >= 0) AdvGreen else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun Header(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(38.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) -> MetricBox(label, value, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)) {
        Column(Modifier.padding(10.dp)) {
            Text(value, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BarRow(label: String, value: String, progress: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun Ring(progress: Float, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun EmptyInside(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun monthWithValue(month: YearMonth?, value: String): String = month?.let { "${formatMonth(it)} · $value" } ?: "—"
private fun formatMonth(month: YearMonth): String = month.atDay(1).format(DateTimeFormatter.ofPattern("LLL yy", RuLocale)).replace(".", "")
private fun formatMonthLong(month: YearMonth): String = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RuLocale)).replaceFirstChar { it.uppercase() }
private fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", RuLocale))
private fun formatNumber(value: Long): String = NumberFormat.getIntegerInstance(RuLocale).format(value)
private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0 мин"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when { h > 0 && m > 0 -> "$h ч $m мин"; h > 0 -> "$h ч"; else -> "${m.coerceAtLeast(1)} мин" }
}
private fun formatDurationTiny(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}ч" else "${m}м"
}
private fun formatLabel(format: BookFormat) = when (format) {
    BookFormat.EPUB -> "EPUB"
    BookFormat.FB2 -> "FB2"
    BookFormat.FB2_ZIP -> "FB2.ZIP"
    BookFormat.PDF -> "PDF"
    BookFormat.TXT -> "TXT"
}
private fun weekdayShort(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Пн"; DayOfWeek.TUESDAY -> "Вт"; DayOfWeek.WEDNESDAY -> "Ср"; DayOfWeek.THURSDAY -> "Чт"; DayOfWeek.FRIDAY -> "Пт"; DayOfWeek.SATURDAY -> "Сб"; DayOfWeek.SUNDAY -> "Вс"
}
private fun weekdayName(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "понедельник"; DayOfWeek.TUESDAY -> "вторник"; DayOfWeek.WEDNESDAY -> "среда"; DayOfWeek.THURSDAY -> "четверг"; DayOfWeek.FRIDAY -> "пятница"; DayOfWeek.SATURDAY -> "суббота"; DayOfWeek.SUNDAY -> "воскресенье"
}
