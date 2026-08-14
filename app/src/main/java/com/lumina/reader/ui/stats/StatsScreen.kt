package com.lumina.reader.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val StatsBlue = Color(0xFF2563EB)
private val StatsIndigo = Color(0xFF6366F1)
private val StatsPurple = Color(0xFF8B5CF6)
private val StatsOrange = Color(0xFFF97316)
private val StatsGreen = Color(0xFF10B981)
private val StatsAmber = Color(0xFFF59E0B)
private val RussianLocale = Locale("ru", "RU")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedPeriodName by rememberSaveable { mutableStateOf(StatsPeriod.SEVEN_DAYS.name) }
    val selectedPeriod = remember(selectedPeriodName) {
        runCatching { StatsPeriod.valueOf(selectedPeriodName) }.getOrDefault(StatsPeriod.SEVEN_DAYS)
    }

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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    StatsHero(
                        state = state,
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = {
                            selectedPeriodName = it.name
                        }
                    )
                }

                if (state.allTime.sessionCount == 0) {
                    item { EmptyStatsCard() }
                }

                item {
                    StreakCard(
                        currentStreakDays = state.currentStreakDays,
                        bestStreakDays = state.bestStreakDays,
                        readingRhythm = state.readingRhythm
                    )
                }

                item { SectionTitle("Последние 7 дней", Icons.Default.BarChart) }

                item {
                    ActivityCard(state.dailyActivity)
                }

                item { SectionTitle("Чтение в цифрах", Icons.AutoMirrored.Filled.TrendingUp) }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InsightCard(
                            title = "Средняя сессия",
                            value = formatDuration(state.averageSessionSeconds),
                            icon = Icons.Default.Timer,
                            color = StatsBlue,
                            modifier = Modifier.weight(1f)
                        )
                        InsightCard(
                            title = "Темп чтения",
                            value = if (state.averageWordsPerMinute > 0) {
                                "${formatNumber(state.averageWordsPerMinute.toLong())} сл/мин"
                            } else {
                                "Нет данных"
                            },
                            icon = Icons.Default.Speed,
                            color = StatsPurple,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InsightCard(
                            title = "Дней с книгой",
                            value = formatNumber(state.activeReadingDays.toLong()),
                            icon = Icons.Default.CalendarMonth,
                            color = StatsGreen,
                            modifier = Modifier.weight(1f)
                        )
                        InsightCard(
                            title = "Долгая сессия",
                            value = formatDuration(state.longestSessionSeconds),
                            icon = Icons.Default.Schedule,
                            color = StatsAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item { SectionTitle("Библиотека", Icons.AutoMirrored.Filled.MenuBook) }

                item { LibraryCard(state) }

                if (state.mostReadBooks.isNotEmpty()) {
                    item { SectionTitle("Больше всего читали", Icons.Default.EmojiEvents) }

                    itemsIndexed(
                        items = state.mostReadBooks,
                        key = { _, item -> item.bookId }
                    ) { index, book ->
                        TopBookCard(
                            rank = index + 1,
                            book = book,
                            maximumDuration = state.mostReadBooks.maxOf { it.durationSeconds }
                        )
                    }
                }

                if (state.recentSessions.isNotEmpty()) {
                    item { SectionTitle("Недавняя активность", Icons.Default.History) }

                    itemsIndexed(
                        items = state.recentSessions,
                        key = { index, session -> "${session.id}_${session.timestamp}_$index" }
                    ) { _, session ->
                        RecentSessionCard(session)
                    }
                }

                if (state.ignoredSessionCount > 0) {
                    item {
                        Text(
                            text = plural(
                                state.ignoredSessionCount,
                                "${state.ignoredSessionCount} повреждённая сессия не учтена",
                                "${state.ignoredSessionCount} повреждённые сессии не учтены",
                                "${state.ignoredSessionCount} повреждённых сессий не учтено"
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
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
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
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
                    Brush.linearGradient(
                        listOf(StatsBlue, StatsIndigo, StatsPurple)
                    )
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
                        text = selectedPeriod.title,
                        color = Color.White.copy(alpha = 0.78f),
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
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = Color(0xFFFFD18A)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.currentStreakDays > 0) {
                                    "${state.currentStreakDays} ${dayWord(state.currentStreakDays)} подряд"
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

                AnimatedContent(targetState = summary.durationSeconds, label = "readingTime") { duration ->
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
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium
                )

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
                        label = sessionWord(summary.sessionCount),
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
private fun HeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.13f),
        shape = RoundedCornerShape(13.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
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
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    text = period.shortTitle,
                    modifier = Modifier.padding(vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
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
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoStories, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Откройте книгу — и история начнётся", fontWeight = FontWeight.Bold)
                Text(
                    text = "Здесь появятся время, серии чтения и любимые книги.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun StreakCard(
    currentStreakDays: Int,
    bestStreakDays: Int,
    readingRhythm: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(StatsOrange, Color(0xFFFB923C))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentStreakDays == 0) {
                        "Серия ждёт первого дня"
                    } else {
                        "$currentStreakDays ${dayWord(currentStreakDays)} подряд"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = readingRhythm,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = bestStreakDays.toString(),
                    color = StatsOrange,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "рекорд",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: List<DailyReadingActivity>) {
    val maximumDuration = activity.maxOfOrNull { it.durationSeconds } ?: 0L
    val weekDuration = activity.sumOf { it.durationSeconds }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "Активность",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (weekDuration > 0) {
                            "За неделю — ${formatDuration(weekDuration)}"
                        } else {
                            "На этой неделе пока тихо"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val activeDays = activity.count { it.sessionCount > 0 }
                Text(
                    text = "$activeDays из 7 дней",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                activity.forEach { day ->
                    ActivityBar(
                        activity = day,
                        maximumDuration = maximumDuration,
                        isToday = day.date == LocalDate.now(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityBar(
    activity: DailyReadingActivity,
    maximumDuration: Long,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    val fraction = if (maximumDuration == 0L) 0f else {
        activity.durationSeconds.toFloat() / maximumDuration.toFloat()
    }
    val barHeight = if (activity.durationSeconds == 0L) 4.dp else (8f + 68f * fraction).dp
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(
            text = if (activity.durationSeconds > 0) formatCompactDuration(activity.durationSeconds) else "",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                .background(
                    when {
                        activity.durationSeconds == 0L -> MaterialTheme.colorScheme.surfaceVariant
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> StatsIndigo.copy(alpha = 0.72f)
                    }
                )
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = activity.date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.SHORT,
                RussianLocale
            ).removeSuffix(".").uppercase(RussianLocale),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = activity.date.dayOfMonth.toString(),
            fontSize = 9.sp,
            lineHeight = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )
    }
}

@Composable
private fun InsightCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Surface(
                modifier = Modifier.size(34.dp),
                color = color.copy(alpha = 0.14f),
                contentColor = color,
                shape = RoundedCornerShape(10.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LibraryCard(state: ReadingStatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${state.libraryBookCount} ${bookWord(state.libraryBookCount)} на полках",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${state.shelfCount} ${shelfWord(state.shelfCount)} · " +
                            if (state.seriesCount > 0) {
                                "${state.seriesCount} ${seriesWord(state.seriesCount)}"
                            } else {
                                "серии пока не созданы"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${state.averageLibraryProgress}%",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.averageLibraryProgress / 100f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(listOf(StatsBlue, StatsPurple))
                        )
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                LibraryMetric("Читаю", state.readingBookCount, Icons.Default.AutoStories, StatsAmber)
                LibraryMetric("Готово", state.completedBookCount, Icons.Default.CheckCircle, StatsGreen)
                LibraryMetric("Любимые", state.favoriteBookCount, Icons.Default.Favorite, Color(0xFFEC4899))
                LibraryMetric("Открыто", state.allTime.bookCount, Icons.AutoMirrored.Filled.MenuBook, StatsBlue)
            }
        }
    }
}

@Composable
private fun LibraryMetric(label: String, value: Int, icon: ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(4.dp))
        Text(value.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopBookCard(
    rank: Int,
    book: BookReadingSummary,
    maximumDuration: Long
) {
    val fraction = if (maximumDuration <= 0L) 0f else {
        (book.durationSeconds.toFloat() / maximumDuration.toFloat()).coerceIn(0f, 1f)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                color = when (rank) {
                    1 -> StatsAmber.copy(alpha = 0.18f)
                    2 -> Color(0xFF94A3B8).copy(alpha = 0.18f)
                    3 -> Color(0xFFB45309).copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                },
                contentColor = when (rank) {
                    1 -> StatsAmber
                    2 -> Color(0xFF64748B)
                    3 -> Color(0xFFB45309)
                    else -> MaterialTheme.colorScheme.primary
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("#$rank", fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = book.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatDuration(book.durationSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(7.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(StatsBlue, StatsPurple)))
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "≈ ${formatNumber((book.wordsRead + 249) / 250)} стр. · " +
                        "${book.sessionCount} ${sessionWord(book.sessionCount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentSessionCard(session: RecentReadingSession) {
    val date = Instant.ofEpochMilli(session.timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", RussianLocale))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.bookTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDuration(session.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (session.wordsRead > 0) {
                    Text(
                        text = "${formatNumber(session.wordsRead)} слов",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private val StatsPeriod.title: String
    get() = when (this) {
        StatsPeriod.TODAY -> "Сегодня"
        StatsPeriod.SEVEN_DAYS -> "Последние 7 дней"
        StatsPeriod.ALL_TIME -> "За всё время"
    }

private val StatsPeriod.shortTitle: String
    get() = when (this) {
        StatsPeriod.TODAY -> "Сегодня"
        StatsPeriod.SEVEN_DAYS -> "7 дней"
        StatsPeriod.ALL_TIME -> "Всё время"
    }

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0 мин"
    if (seconds < 60) return "< 1 мин"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes мин"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    if (hours < 24) return if (remainingMinutes == 0L) "$hours ч" else "$hours ч $remainingMinutes мин"
    val days = hours / 24
    val remainingHours = hours % 24
    return if (remainingHours == 0L) "$days д" else "$days д $remainingHours ч"
}

private fun formatCompactDuration(seconds: Long): String = when {
    seconds < 60 -> "<1м"
    seconds < 3_600 -> "${seconds / 60}м"
    else -> "${seconds / 3_600}ч"
}

private fun formatNumber(value: Long): String =
    NumberFormat.getIntegerInstance(RussianLocale).format(value)

private fun dayWord(value: Int): String = plural(value, "день", "дня", "дней")

private fun sessionWord(value: Int): String = plural(value, "сессия", "сессии", "сессий")

private fun bookWord(value: Int): String = plural(value, "книга", "книги", "книг")

private fun shelfWord(value: Int): String = plural(value, "полка", "полки", "полок")

private fun seriesWord(value: Int): String = plural(value, "серия", "серии", "серий")

private fun plural(value: Int, one: String, few: String, many: String): String {
    val absolute = kotlin.math.abs(value)
    val lastTwo = absolute % 100
    val last = absolute % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}
