package com.lumina.reader.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val StatsBlue = Color(0xFF2563EB)
private val StatsIndigo = Color(0xFF6366F1)
private val StatsPurple = Color(0xFF8B5CF6)
private val StatsOrange = Color(0xFFF97316)
private val StatsGreen = Color(0xFF10B981)
private val StatsAmber = Color(0xFFF59E0B)
private val RussianLocale = Locale("ru", "RU")

private enum class TrendRange(val days: Int, val title: String) {
    WEEK(7, "7 дней"),
    MONTH(30, "30 дней"),
    YEAR(365, "365 дней")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedPeriodName by rememberSaveable { mutableStateOf(StatsPeriod.SEVEN_DAYS.name) }
    val selectedPeriod = remember(selectedPeriodName) {
        runCatching { StatsPeriod.valueOf(selectedPeriodName) }
            .getOrDefault(StatsPeriod.SEVEN_DAYS)
    }
    var showDailyGoalDialog by remember { mutableStateOf(false) }
    var showYearGoalDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Статистика",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Ваш читательский ритм",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            StatsLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    StatsHero(
                        state = state,
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = { selectedPeriodName = it.name }
                    )
                }

                if (state.allTime.sessionCount == 0) {
                    item { EmptyStatsCard() }
                }

                item {
                    GoalsCard(
                        state = state,
                        onEditDaily = { showDailyGoalDialog = true },
                        onEditYear = { showYearGoalDialog = true }
                    )
                }

                item { SectionTitle("Активность", Icons.Default.ShowChart) }
                item { ActivityTrendCard(state) }
                item { HeatmapCard(state.dailyActivity) }

                item { SectionTitle("Ваш ритм", Icons.Default.AutoAwesome) }
                item { ReaderProfileCard(state) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ClockActivityCard(
                            activity = state.hourlyActivity,
                            modifier = Modifier.weight(1f)
                        )
                        ReadingBasicsCard(
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item { WeekdayRhythmCard(state.weekdayActivity) }

                item { SectionTitle("Личные рекорды", Icons.Default.EmojiEvents) }
                item { RecordsCard(state.personalRecords) }
                item { EquivalentsCard(state) }

                if (state.bookOfMonth != null) {
                    item { SectionTitle("Книга месяца", Icons.Default.WorkspacePremium) }
                    item { BookOfMonthCard(state.bookOfMonth!!) }
                }

                if (state.mostReadBooks.isNotEmpty()) {
                    item { SectionTitle("Больше всего читали", Icons.Default.Leaderboard) }
                    item { TopBooksCard(state.mostReadBooks) }
                }

                item { SectionTitle("Книги по месяцам", Icons.Default.CalendarMonth) }
                item { CompletedBooksByMonthCard(state) }

                item { SectionTitle("Как меняется чтение", Icons.AutoMirrored.Filled.TrendingUp) }
                item {
                    MonthlyTrendCard(
                        title = "Темп чтения",
                        subtitle = "Среднее количество слов в минуту",
                        values = state.monthlyActivity.map { it.averageWordsPerMinute.toFloat() },
                        months = state.monthlyActivity.map { it.month },
                        valueLabel = { "${it.roundToInt()} сл/мин" },
                        color = StatsPurple
                    )
                }
                item {
                    MonthlyTrendCard(
                        title = "Средняя длительность сессии",
                        subtitle = "Как долго вы читаете за один подход",
                        values = state.monthlyActivity.map { it.averageSessionSeconds.toFloat() },
                        months = state.monthlyActivity.map { it.month },
                        valueLabel = { formatDuration(it.toLong()) },
                        color = StatsBlue
                    )
                }

                item { SectionTitle("Библиотека", Icons.Default.LocalLibrary) }
                item { LibraryFunnelCard(state) }
                item { AchievementCard(state) }

                if (state.ignoredSessionCount > 0) {
                    item {
                        Text(
                            text = "Не учтено повреждённых сессий: ${state.ignoredSessionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showDailyGoalDialog) {
        DailyGoalDialog(
            settings = state.goalSettings,
            onSave = { type, target ->
                viewModel.updateDailyGoal(type, target)
                showDailyGoalDialog = false
            },
            onDismiss = { showDailyGoalDialog = false }
        )
    }

    if (showYearGoalDialog) {
        YearGoalDialog(
            target = state.goalSettings.yearlyBooksTarget,
            onSave = { target ->
                viewModel.updateYearlyBooksGoal(target)
                showYearGoalDialog = false
            },
            onDismiss = { showYearGoalDialog = false }
        )
    }
}

@Composable
private fun StatsLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
        )
        repeat(5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
private fun StatsHero(
    state: ReadingStatsUiState,
    selectedPeriod: StatsPeriod,
    onPeriodSelected: (StatsPeriod) -> Unit
) {
    val summary = state.summary(selectedPeriod)
    val comparison = state.comparison(selectedPeriod)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(StatsBlue, StatsIndigo, StatsPurple))
                )
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = periodTitle(selectedPeriod),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Surface(
                        color = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color(0xFFFFD18A)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.currentStreakDays > 0) {
                                    "${state.currentStreakDays} дн. подряд"
                                } else {
                                    "Начните серию"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                AnimatedContent(
                    targetState = summary.durationSeconds,
                    label = "readingTime"
                ) { duration ->
                    Text(
                        text = formatDuration(duration),
                        color = Color.White,
                        fontSize = 38.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "время за книгами",
                    color = Color.White.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (comparison.durationPercent != null && selectedPeriod != StatsPeriod.ALL_TIME) {
                    Spacer(Modifier.height(8.dp))
                    ComparisonPill(comparison.durationPercent)
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroMetric(
                        value = formatNumber(summary.wordsRead),
                        label = "слов",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetric(
                        value = "≈ ${formatNumber(summary.estimatedPages)}",
                        label = "страниц",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetric(
                        value = formatNumber(summary.sessionCount.toLong()),
                        label = "сессий",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        PeriodSelector(
            selectedPeriod = selectedPeriod,
            onPeriodSelected = onPeriodSelected,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun ComparisonPill(percent: Int) {
    val sign = if (percent > 0) "+" else ""
    Surface(
        color = Color.White.copy(alpha = 0.14f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (percent >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$sign$percent% к прошлому периоду",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.13f),
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp)) {
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: StatsPeriod,
    onPeriodSelected: (StatsPeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        StatsPeriod.entries.forEach { period ->
            val selected = period == selectedPeriod
            Surface(
                onClick = { onPeriodSelected(period) },
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                } else {
                    Color.Transparent
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = periodShortTitle(period),
                    modifier = Modifier.padding(vertical = 9.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun GoalsCard(
    state: ReadingStatsUiState,
    onEditDaily: () -> Unit,
    onEditYear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside("Цели", "Ежедневная привычка и план на год")
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GoalTile(
                    title = "Сегодня",
                    value = dailyGoalValue(state),
                    subtitle = dailyGoalSubtitle(state),
                    progress = state.dailyGoalProgress,
                    icon = Icons.Default.TrackChanges,
                    color = StatsBlue,
                    onClick = onEditDaily,
                    modifier = Modifier.weight(1f)
                )
                GoalTile(
                    title = "${LocalDate.now().year}",
                    value = "${state.completedBooksThisYear} / ${state.goalSettings.yearlyBooksTarget}",
                    subtitle = if (state.projectedBooksThisYear > 0) {
                        "Прогноз: ${state.projectedBooksThisYear} книг"
                    } else {
                        "Годовая цель"
                    },
                    progress = state.yearlyGoalProgress,
                    icon = Icons.Default.AutoStories,
                    color = StatsGreen,
                    onClick = onEditYear,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = StatsOrange
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "${state.goalStreakDays} дн. подряд с выполненной целью",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (state.dailyGoalProgress >= 1f) {
                                "Сегодняшняя цель выполнена"
                            } else {
                                "До цели осталось ${dailyGoalRemaining(state)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalTile(
    title: String,
    value: String,
    subtitle: String,
    progress: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = color.copy(alpha = 0.09f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = progress.coerceIn(0f, 1f),
                    color = color,
                    modifier = Modifier.size(76.dp)
                )
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun ActivityTrendCard(state: ReadingStatsUiState) {
    var rangeName by rememberSaveable { mutableStateOf(TrendRange.MONTH.name) }
    val range = runCatching { TrendRange.valueOf(rangeName) }.getOrDefault(TrendRange.MONTH)
    val data = state.dailyActivity.takeLast(range.days)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val maxSeconds = data.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside(
                "Время чтения",
                "Динамика по дням и сравнение с предыдущим периодом"
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TrendRange.entries) { item ->
                    FilterChip(
                        selected = item == range,
                        onClick = { rangeName = item.name },
                        label = { Text(item.title) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                if (data.isEmpty()) return@Canvas
                val chartHeight = size.height - 20.dp.toPx()
                if (range == TrendRange.YEAR) {
                    val step = if (data.size <= 1) size.width else size.width / (data.size - 1)
                    var previous: Offset? = null
                    data.forEachIndexed { index, day ->
                        val x = index * step
                        val y = chartHeight -
                            (day.durationSeconds.toFloat() / maxSeconds.toFloat()) * chartHeight
                        val point = Offset(x, y)
                        previous?.let {
                            drawLine(
                                color = primary,
                                start = it,
                                end = point,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        previous = point
                    }
                } else {
                    val gap = 2.dp.toPx()
                    val barWidth = ((size.width - gap * (data.size - 1)) / data.size)
                        .coerceAtLeast(1.dp.toPx())
                    data.forEachIndexed { index, day ->
                        val height = if (day.durationSeconds == 0L) 2.dp.toPx() else
                            (day.durationSeconds.toFloat() / maxSeconds.toFloat()) * chartHeight
                        val left = index * (barWidth + gap)
                        drawRoundRect(
                            color = if (day.durationSeconds == 0L) muted else primary,
                            topLeft = Offset(left, chartHeight - height),
                            size = Size(barWidth, height),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                    }
                }
            }

            val period = when (range) {
                TrendRange.WEEK -> StatsPeriod.SEVEN_DAYS
                TrendRange.MONTH -> StatsPeriod.THIRTY_DAYS
                TrendRange.YEAR -> StatsPeriod.YEAR
            }
            val comparison = state.comparison(period).durationPercent
            if (comparison != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${if (comparison >= 0) "+" else ""}$comparison% к прошлому периоду",
                    color = if (comparison >= 0) StatsGreen else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeatmapCard(allActivity: List<DailyReadingActivity>) {
    var monthsBack by rememberSaveable { mutableIntStateOf(12) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val today = allActivity.lastOrNull()?.date ?: LocalDate.now()
    val days = when (monthsBack) {
        3 -> 92
        6 -> 183
        else -> 365
    }
    val visible = allActivity.takeLast(days)
    val byDate = remember(visible) { visible.associateBy(DailyReadingActivity::date) }
    val firstDate = visible.firstOrNull()?.date ?: today
    val startMonday = firstDate.minusDays((firstDate.dayOfWeek.value - 1).toLong())
    val totalDays = ChronoUnit.DAYS.between(startMonday, today).toInt() + 1
    val weeks = ceil(totalDays / 7.0).toInt().coerceAtLeast(1)
    val maxSeconds = visible.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val selected = MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside(
                "Календарь активности",
                "Чем насыщеннее клетка, тем больше времени за книгами"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 6, 12).forEach { months ->
                    FilterChip(
                        selected = monthsBack == months,
                        onClick = { monthsBack = months },
                        label = { Text("$months мес.") }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .pointerInput(startMonday, weeks, byDate) {
                        detectTapGestures { offset ->
                            val cellWidth = size.width / weeks.toFloat()
                            val cellHeight = size.height / 7f
                            val week = (offset.x / cellWidth).toInt().coerceIn(0, weeks - 1)
                            val day = (offset.y / cellHeight).toInt().coerceIn(0, 6)
                            val date = startMonday.plusDays((week * 7L) + day)
                            if (!date.isAfter(today) && date in byDate.keys) {
                                selectedDate = date
                            }
                        }
                    }
            ) {
                val gap = 1.5.dp.toPx()
                val cellWidth = size.width / weeks.toFloat()
                val cellHeight = size.height / 7f

                repeat(weeks) { week ->
                    repeat(7) { day ->
                        val date = startMonday.plusDays(week * 7L + day)
                        if (!date.isAfter(today)) {
                            val activity = byDate[date]
                            val ratio = activity?.durationSeconds
                                ?.toFloat()
                                ?.div(maxSeconds.toFloat())
                                ?.coerceIn(0f, 1f)
                                ?: 0f
                            val color = when {
                                selectedDate == date -> selected
                                ratio <= 0f -> empty
                                else -> primary.copy(alpha = 0.18f + ratio * 0.82f)
                            }
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(
                                    week * cellWidth + gap / 2,
                                    day * cellHeight + gap / 2
                                ),
                                size = Size(
                                    (cellWidth - gap).coerceAtLeast(1f),
                                    (cellHeight - gap).coerceAtLeast(1f)
                                ),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            val selectedActivity = selectedDate?.let(byDate::get)
            Text(
                text = if (selectedActivity != null) {
                    "${formatDate(selectedActivity.date)} · ${formatDuration(selectedActivity.durationSeconds)} · " +
                        "${formatNumber(selectedActivity.wordsRead)} слов · ${selectedActivity.sessionCount} сесс."
                } else {
                    "Нажмите на день, чтобы увидеть детали"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReaderProfileCard(state: ReadingStatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            state.readerProfile.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Ваш читательский профиль",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    state.readerProfile.description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileTag(
                        icon = Icons.Default.DarkMode,
                        text = state.readerProfile.favoritePartOfDay
                    )
                    ProfileTag(
                        icon = Icons.Default.DateRange,
                        text = state.readerProfile.favoriteWeekday
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    state.readingRhythm,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfileTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ClockActivityCard(
    activity: List<HourlyReadingActivity>,
    modifier: Modifier = Modifier
) {
    val max = activity.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    val peak = activity.maxByOrNull { it.durationSeconds }?.takeIf { it.durationSeconds > 0 }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Часы активности",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    activity.forEach { hour ->
                        val ratio = hour.durationSeconds.toFloat() / max.toFloat()
                        drawArc(
                            color = if (hour.durationSeconds == 0L) {
                                track
                            } else {
                                primary.copy(alpha = 0.2f + ratio * 0.8f)
                            },
                            startAngle = -90f + hour.hour * 15f + 1f,
                            sweepAngle = 12.5f,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        peak?.let { "%02d:00".format(it.hour) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "пик",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingBasicsCard(
    state: ReadingStatsUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "В цифрах",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            SmallMetric("Темп", "${state.averageWordsPerMinute} сл/мин", Icons.Default.Speed)
            SmallMetric("Сессия", formatDuration(state.averageSessionSeconds), Icons.Default.Timer)
            SmallMetric("Дней с книгой", state.activeReadingDays.toString(), Icons.Default.CalendarToday)
            SmallMetric("Стрик", "${state.currentStreakDays} дн.", Icons.Default.LocalFireDepartment)
        }
    }
}

@Composable
private fun SmallMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeekdayRhythmCard(activity: List<WeekdayReadingActivity>) {
    val max = activity.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside(
                "Недельный ритм",
                "Средняя привычка по дням недели за всё время"
            )
            Spacer(Modifier.height(12.dp))
            activity.forEach { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        weekdayShort(day.dayOfWeek),
                        modifier = Modifier.width(30.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = {
                            (day.durationSeconds.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = StatsIndigo,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDurationShort(day.durationSeconds),
                        modifier = Modifier.width(58.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordsCard(records: PersonalRecords) {
    val dateText = records.bestDayDate?.let(::formatDateShort) ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RecordRow(
            RecordValue(
                "Лучший стрик",
                "${records.bestStreakDays} дн.",
                Icons.Default.LocalFireDepartment,
                StatsOrange
            ),
            RecordValue(
                "Долгая сессия",
                formatDuration(records.longestSessionSeconds),
                Icons.Default.Timer,
                StatsBlue
            )
        )
        RecordRow(
            RecordValue(
                "Самый читающий день",
                formatDuration(records.bestDaySeconds),
                Icons.Default.CalendarMonth,
                StatsGreen,
                dateText
            ),
            RecordValue(
                "Слов за день",
                formatNumber(records.maxWordsDay),
                Icons.Default.Article,
                StatsPurple
            )
        )
        RecordRow(
            RecordValue(
                "Пиковый темп",
                if (records.fastestStableWpm > 0) "${records.fastestStableWpm} сл/мин" else "—",
                Icons.Default.Bolt,
                StatsAmber
            ),
            RecordValue(
                "Книг за месяц",
                records.maxCompletedBooksMonth.toString(),
                Icons.Default.AutoStories,
                StatsIndigo
            )
        )
    }
}

private data class RecordValue(
    val title: String,
    val value: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val note: String? = null
)

@Composable
private fun RecordRow(first: RecordValue, second: RecordValue) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RecordTile(first, Modifier.weight(1f))
        RecordTile(second, Modifier.weight(1f))
    }
}

@Composable
private fun RecordTile(record: RecordValue, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = record.color.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        record.icon,
                        contentDescription = null,
                        tint = record.color,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                record.value,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                record.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            record.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = record.color
                )
            }
        }
    }
}

@Composable
private fun EquivalentsCard(state: ReadingStatsUiState) {
    val days = state.allTime.durationSeconds / 86_400
    val hoursRemainder = (state.allTime.durationSeconds % 86_400) / 3_600
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside("Эквиваленты", "Масштаб всего прочитанного")
            Spacer(Modifier.height(12.dp))
            Text(
                formatNumber(state.allTime.wordsRead),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "слов прочитано",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EquivalentTag("≈ ${formatNumber(state.allTime.estimatedPages)} стр.")
                EquivalentTag(
                    if (days > 0) "$days дн. $hoursRemainder ч непрерывно"
                    else "${state.allTime.durationSeconds / 3_600} ч чтения"
                )
            }
        }
    }
}

@Composable
private fun EquivalentTag(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BookOfMonthCard(book: BookReadingSummary) {
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(width = 72.dp, height = 104.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (coverFile != null) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = StatsAmber.copy(alpha = 0.14f)
                ) {
                    Text(
                        "🏆 Книга месяца",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    book.title,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatDuration(book.durationSeconds)} · " +
                        "${formatNumber(book.wordsRead)} слов · ${book.sessionCount} сесс.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TopBooksCard(books: List<BookReadingSummary>) {
    val maxDuration = books.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1L) ?: 1L
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            books.forEachIndexed { index, book ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (index) {
                            0 -> "🥇"
                            1 -> "🥈"
                            2 -> "🥉"
                            else -> "${index + 1}"
                        },
                        modifier = Modifier.width(36.dp),
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            book.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = {
                                (book.durationSeconds.toFloat() / maxDuration.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = StatsIndigo,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatDuration(book.durationSeconds)} · ${formatNumber(book.wordsRead)} слов",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedBooksByMonthCard(state: ReadingStatsUiState) {
    val data = state.monthlyActivity
    val max = data.maxOfOrNull { it.completedBooks }?.coerceAtLeast(1) ?: 1
    val barColor = StatsGreen
    val track = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        "${state.completedBooksThisYear}",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "книг завершено в ${LocalDate.now().year}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.projectedBooksThisYear > 0) {
                    Text(
                        "Прогноз: ${state.projectedBooksThisYear}",
                        color = StatsGreen,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                data.forEach { month ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .fillMaxHeight(
                                        if (month.completedBooks == 0) 0.03f
                                        else (month.completedBooks.toFloat() / max.toFloat())
                                            .coerceIn(0.05f, 1f)
                                    )
                                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    .background(
                                        if (month.completedBooks == 0) track else barColor
                                    )
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            monthLabel(month.month),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(
    title: String,
    subtitle: String,
    values: List<Float>,
    months: List<YearMonth>,
    valueLabel: (Float) -> String,
    color: Color
) {
    val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val latest = values.lastOrNull() ?: 0f
    val track = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    valueLabel(latest),
                    color = color,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(Modifier.height(14.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                if (values.isEmpty()) return@Canvas
                drawLine(
                    color = track,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                val step = if (values.size <= 1) size.width else size.width / (values.size - 1)
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    val point = Offset(
                        x = index * step,
                        y = size.height - (value / max) * size.height * 0.92f
                    )
                    previous?.let {
                        drawLine(
                            color = color,
                            start = it,
                            end = point,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                    drawCircle(
                        color = color,
                        radius = 3.dp.toPx(),
                        center = point
                    )
                    previous = point
                }
            }
            if (months.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        monthLabel(months.first()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        monthLabel(months.last()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFunnelCard(state: ReadingStatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderInside(
                "Воронка библиотеки",
                "Что происходит с книгами после добавления"
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(50))
            ) {
                if (state.completedBookCount > 0) {
                    Box(
                        Modifier
                            .weight(state.completedBookCount.toFloat())
                            .fillMaxHeight()
                            .background(StatsGreen)
                    )
                }
                if (state.readingBookCount > 0) {
                    Box(
                        Modifier
                            .weight(state.readingBookCount.toFloat())
                            .fillMaxHeight()
                            .background(StatsBlue)
                    )
                }
                if (state.unreadBookCount > 0) {
                    Box(
                        Modifier
                            .weight(state.unreadBookCount.toFloat())
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FunnelLegend("Прочитано", state.completedBookCount, StatsGreen)
            FunnelLegend("Читаю", state.readingBookCount, StatsBlue)
            FunnelLegend("Не начато", state.unreadBookCount, MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTag("${state.libraryBookCount} книг", Icons.Default.MenuBook)
                LibraryTag("${state.favoriteBookCount} избранных", Icons.Default.Favorite)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTag("${state.shelfCount} полок", Icons.Default.Folder)
                LibraryTag("${state.seriesCount} серий", Icons.Default.CollectionsBookmark)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Средний прогресс библиотеки: ${state.averageLibraryProgress}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FunnelLegend(label: String, value: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LibraryTag(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AchievementCard(state: ReadingStatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = StatsAmber.copy(alpha = 0.09f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = StatsAmber.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = StatsAmber
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.nextAchievementTitle,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    state.nextAchievementDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.nextAchievementProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(50)),
                    color = StatsAmber,
                    trackColor = StatsAmber.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SectionHeaderInside(title: String, subtitle: String) {
    Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    Text(
        subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyStatsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AutoStories, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Откройте книгу — и история начнётся", fontWeight = FontWeight.Bold)
                Text(
                    "Статистика будет заполняться автоматически.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DailyGoalDialog(
    settings: StatsGoalSettings,
    onSave: (DailyGoalType, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(settings.dailyType) }
    var value by remember { mutableStateOf(settings.dailyTarget.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Дневная цель") },
        text = {
            Column {
                Text(
                    "Что считать ежедневной целью",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DailyGoalType.entries) { item ->
                        FilterChip(
                            selected = item == type,
                            onClick = {
                                type = item
                                value = when (item) {
                                    DailyGoalType.MINUTES -> "30"
                                    DailyGoalType.PAGES -> "20"
                                    DailyGoalType.WORDS -> "5000"
                                }
                            },
                            label = { Text(goalTypeLabel(item)) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6) },
                    label = { Text(goalTypeUnit(type)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = value.toIntOrNull() ?: settings.dailyTarget
                    onSave(type, target)
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun YearGoalDialog(
    target: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(target.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цель на год") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(3) },
                label = { Text("Сколько книг прочитать") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.toIntOrNull() ?: target) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private fun periodTitle(period: StatsPeriod): String = when (period) {
    StatsPeriod.TODAY -> "Сегодня"
    StatsPeriod.SEVEN_DAYS -> "Последние 7 дней"
    StatsPeriod.THIRTY_DAYS -> "Последние 30 дней"
    StatsPeriod.YEAR -> "Этот год"
    StatsPeriod.ALL_TIME -> "За всё время"
}

private fun periodShortTitle(period: StatsPeriod): String = when (period) {
    StatsPeriod.TODAY -> "День"
    StatsPeriod.SEVEN_DAYS -> "7д"
    StatsPeriod.THIRTY_DAYS -> "30д"
    StatsPeriod.YEAR -> "Год"
    StatsPeriod.ALL_TIME -> "Всё"
}

private fun dailyGoalValue(state: ReadingStatsUiState): String {
    val current = state.today
    return when (state.goalSettings.dailyType) {
        DailyGoalType.MINUTES ->
            "${current.durationSeconds / 60} / ${state.goalSettings.dailyTarget} мин"
        DailyGoalType.PAGES ->
            "${current.estimatedPages} / ${state.goalSettings.dailyTarget} стр."
        DailyGoalType.WORDS ->
            "${formatNumber(current.wordsRead)} / ${formatNumber(state.goalSettings.dailyTarget.toLong())}"
    }
}

private fun dailyGoalSubtitle(state: ReadingStatsUiState): String =
    when (state.goalSettings.dailyType) {
        DailyGoalType.MINUTES -> "минут чтения"
        DailyGoalType.PAGES -> "страниц"
        DailyGoalType.WORDS -> "слов"
    }

private fun dailyGoalRemaining(state: ReadingStatsUiState): String {
    return when (state.goalSettings.dailyType) {
        DailyGoalType.MINUTES -> {
            val left = (state.goalSettings.dailyTarget - state.today.durationSeconds / 60)
                .coerceAtLeast(0)
            "$left мин."
        }
        DailyGoalType.PAGES -> {
            val left = (state.goalSettings.dailyTarget - state.today.estimatedPages)
                .coerceAtLeast(0)
            "$left стр."
        }
        DailyGoalType.WORDS -> {
            val left = (state.goalSettings.dailyTarget.toLong() - state.today.wordsRead)
                .coerceAtLeast(0)
            "${formatNumber(left)} слов"
        }
    }
}

private fun goalTypeLabel(type: DailyGoalType): String = when (type) {
    DailyGoalType.MINUTES -> "Минуты"
    DailyGoalType.PAGES -> "Страницы"
    DailyGoalType.WORDS -> "Слова"
}

private fun goalTypeUnit(type: DailyGoalType): String = when (type) {
    DailyGoalType.MINUTES -> "Минут в день"
    DailyGoalType.PAGES -> "Страниц в день"
    DailyGoalType.WORDS -> "Слов в день"
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0 мин"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
        hours > 0 -> "$hours ч"
        else -> "${minutes.coerceAtLeast(1)} мин"
    }
}

private fun formatDurationShort(seconds: Long): String {
    if (seconds <= 0) return "0"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}ч ${minutes}м" else "${minutes.coerceAtLeast(1)}м"
}

private fun formatNumber(value: Long): String =
    NumberFormat.getIntegerInstance(RussianLocale).format(value)

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", RussianLocale))

private fun formatDateShort(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("d MMM", RussianLocale))

private fun monthLabel(month: YearMonth): String =
    month.atDay(1)
        .format(DateTimeFormatter.ofPattern("LLL", RussianLocale))
        .replace(".", "")
        .replaceFirstChar { it.uppercase() }

private fun weekdayShort(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Пн"
    DayOfWeek.TUESDAY -> "Вт"
    DayOfWeek.WEDNESDAY -> "Ср"
    DayOfWeek.THURSDAY -> "Чт"
    DayOfWeek.FRIDAY -> "Пт"
    DayOfWeek.SATURDAY -> "Сб"
    DayOfWeek.SUNDAY -> "Вс"
}
