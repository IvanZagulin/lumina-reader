package com.lumina.reader.ui.stats

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import kotlin.math.roundToInt

private val AchievementGold = Color(0xFFF59E0B)
private val AchievementOrange = Color(0xFFF97316)
private val AchievementGreen = Color(0xFF10B981)
private val AchievementBlue = Color(0xFF2563EB)
private val AchievementIndigo = Color(0xFF6366F1)
private val AchievementPurple = Color(0xFF8B5CF6)

private data class AchievementBadge(
    val category: String,
    val title: String,
    val detail: String,
    val current: Long,
    val target: Long,
    val icon: ImageVector,
    val color: Color
) {
    val unlocked: Boolean
        get() = target > 0 && current >= target

    val progress: Float
        get() = if (target <= 0L) 0f else
            (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
}

@Composable
fun AchievementsPanel(state: ReadingStatsUiState) {
    var expanded by remember { mutableStateOf(false) }
    val achievements = remember(state) { buildAchievements(state) }
    val unlockedCount = achievements.count(AchievementBadge::unlocked)
    val overallProgress = if (achievements.isEmpty()) 0f else
        unlockedCount.toFloat() / achievements.size.toFloat()
    val next = achievements
        .asSequence()
        .filterNot(AchievementBadge::unlocked)
        .maxByOrNull(AchievementBadge::progress)
    val preview = achievements
        .sortedWith(
            compareByDescending<AchievementBadge> { it.unlocked }
                .thenByDescending { it.progress }
        )
        .take(8)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AchievementProgressRing(
                        progress = overallProgress,
                        modifier = Modifier.size(72.dp)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            unlockedCount.toString(),
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "из ${achievements.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Достижения",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (unlockedCount == achievements.size) {
                            "Коллекция собрана полностью. Подозрительно продуктивно."
                        } else {
                            "Открыто $unlockedCount из ${achievements.size} медалей"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (next != null) {
                Spacer(Modifier.height(14.dp))
                NextAchievementCard(next)
            }

            Spacer(Modifier.height(16.dp))

            if (expanded) {
                achievements.groupBy(AchievementBadge::category).forEach { (category, badges) ->
                    Text(
                        category,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    AchievementGrid(badges)
                }
            } else {
                AchievementGrid(preview)
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    if (expanded) "Свернуть коллекцию" else "Показать все ${achievements.size} достижений",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NextAchievementCard(item: AchievementBadge) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = item.color.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = item.color.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = item.color,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Ближайшая цель · ${item.title}",
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(50)),
                    color = item.color,
                    trackColor = item.color.copy(alpha = 0.13f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(item.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = item.color
                )
            }
        }
    }
}

@Composable
private fun AchievementGrid(items: List<AchievementBadge>) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                rowItems.forEach { item ->
                    AchievementTile(item, Modifier.weight(1f))
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AchievementTile(item: AchievementBadge, modifier: Modifier = Modifier) {
    val unlocked = item.unlocked
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (unlocked) {
            item.color.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = if (unlocked) {
                        item.color.copy(alpha = 0.17f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (unlocked) item.color
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (unlocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (unlocked) item.color
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                item.title,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            if (unlocked) {
                Text(
                    "Получено",
                    color = item.color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            } else {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = item.color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(item.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AchievementProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.10f
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
        drawArc(
            color = AchievementGold,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}

private fun buildAchievements(state: ReadingStatsUiState): List<AchievementBadge> {
    val nightSeconds = state.hourlyActivity
        .filter { it.hour in 0..4 || it.hour == 23 }
        .sumOf { it.durationSeconds }
    val morningSeconds = state.hourlyActivity
        .filter { it.hour in 5..10 }
        .sumOf { it.durationSeconds }
    val weekendSeconds = state.weekdayActivity
        .filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        .sumOf { it.durationSeconds }

    fun badge(
        category: String,
        title: String,
        detail: String,
        current: Long,
        target: Long,
        icon: ImageVector,
        color: Color
    ) = AchievementBadge(category, title, detail, current, target, icon, color)

    return listOf(
        badge("Первые шаги", "Первая сессия", "Завершите первую сессию чтения", state.allTime.sessionCount.toLong(), 1, Icons.Default.MenuBook, AchievementBlue),
        badge("Первые шаги", "Неделя знакомства", "Читайте в 7 разных дней", state.activeReadingDays.toLong(), 7, Icons.Default.CalendarMonth, AchievementGreen),

        badge("Время за книгами", "Первый час", "Проведите 1 час за книгами", state.allTime.durationSeconds, 3_600, Icons.Default.Timer, AchievementBlue),
        badge("Время за книгами", "Десять часов", "Накопите 10 часов чтения", state.allTime.durationSeconds, 36_000, Icons.Default.Timer, AchievementBlue),
        badge("Время за книгами", "Полсотни", "Накопите 50 часов чтения", state.allTime.durationSeconds, 180_000, Icons.Default.Schedule, AchievementIndigo),
        badge("Время за книгами", "Сто часов", "Проведите 100 часов за книгами", state.allTime.durationSeconds, 360_000, Icons.Default.EmojiEvents, AchievementGold),
        badge("Время за книгами", "Четверть тысячи", "Накопите 250 часов чтения", state.allTime.durationSeconds, 900_000, Icons.Default.Star, AchievementGold),
        badge("Время за книгами", "Пятьсот часов", "Накопите 500 часов чтения", state.allTime.durationSeconds, 1_800_000, Icons.Default.EmojiEvents, AchievementPurple),

        badge("Стрики", "Три дня подряд", "Читайте 3 дня без перерыва", state.bestStreakDays.toLong(), 3, Icons.Default.LocalFireDepartment, AchievementOrange),
        badge("Стрики", "Неделя с книгой", "Читайте 7 дней подряд", state.bestStreakDays.toLong(), 7, Icons.Default.LocalFireDepartment, AchievementOrange),
        badge("Стрики", "Две недели", "Читайте 14 дней подряд", state.bestStreakDays.toLong(), 14, Icons.Default.LocalFireDepartment, AchievementOrange),
        badge("Стрики", "Книжный месяц", "Читайте 30 дней подряд", state.bestStreakDays.toLong(), 30, Icons.Default.LocalFireDepartment, AchievementOrange),
        badge("Стрики", "Два месяца", "Читайте 60 дней подряд", state.bestStreakDays.toLong(), 60, Icons.Default.LocalFireDepartment, AchievementGold),
        badge("Стрики", "Железная сотня", "Читайте 100 дней подряд", state.bestStreakDays.toLong(), 100, Icons.Default.LocalFireDepartment, AchievementGold),
        badge("Стрики", "Год без паузы", "Читайте 365 дней подряд", state.bestStreakDays.toLong(), 365, Icons.Default.EmojiEvents, AchievementPurple),

        badge("Завершённые книги", "Финиш", "Завершите первую книгу", state.completedBookCount.toLong(), 1, Icons.Default.AutoStories, AchievementGreen),
        badge("Завершённые книги", "Пятёрка", "Завершите 5 книг", state.completedBookCount.toLong(), 5, Icons.Default.AutoStories, AchievementGreen),
        badge("Завершённые книги", "Книжная десятка", "Завершите 10 книг", state.completedBookCount.toLong(), 10, Icons.Default.AutoStories, AchievementGreen),
        badge("Завершённые книги", "Четверть сотни", "Завершите 25 книг", state.completedBookCount.toLong(), 25, Icons.Default.EmojiEvents, AchievementGold),
        badge("Завершённые книги", "Полсотни книг", "Завершите 50 книг", state.completedBookCount.toLong(), 50, Icons.Default.EmojiEvents, AchievementGold),
        badge("Завершённые книги", "Книжная сотня", "Завершите 100 книг", state.completedBookCount.toLong(), 100, Icons.Default.Star, AchievementPurple),

        badge("Слова", "Первые 10 тысяч", "Прочитайте 10 000 слов", state.allTime.wordsRead, 10_000, Icons.Default.Article, AchievementPurple),
        badge("Слова", "Сто тысяч слов", "Прочитайте 100 000 слов", state.allTime.wordsRead, 100_000, Icons.Default.Article, AchievementPurple),
        badge("Слова", "Полмиллиона", "Прочитайте 500 000 слов", state.allTime.wordsRead, 500_000, Icons.Default.Article, AchievementPurple),
        badge("Слова", "Миллион слов", "Прочитайте 1 000 000 слов", state.allTime.wordsRead, 1_000_000, Icons.Default.EmojiEvents, AchievementGold),
        badge("Слова", "Пять миллионов", "Прочитайте 5 000 000 слов", state.allTime.wordsRead, 5_000_000, Icons.Default.Star, AchievementPurple),
        badge("Слова", "Десять миллионов", "Прочитайте 10 000 000 слов", state.allTime.wordsRead, 10_000_000, Icons.Default.Star, AchievementPurple),

        badge("Страницы", "Сто страниц", "Осильте примерно 100 страниц", state.allTime.estimatedPages, 100, Icons.Default.MenuBook, AchievementBlue),
        badge("Страницы", "Тысяча страниц", "Осильте примерно 1 000 страниц", state.allTime.estimatedPages, 1_000, Icons.Default.MenuBook, AchievementBlue),
        badge("Страницы", "Пять тысяч страниц", "Осильте примерно 5 000 страниц", state.allTime.estimatedPages, 5_000, Icons.Default.Bookmark, AchievementIndigo),
        badge("Страницы", "Десять тысяч страниц", "Осильте примерно 10 000 страниц", state.allTime.estimatedPages, 10_000, Icons.Default.EmojiEvents, AchievementGold),
        badge("Страницы", "Двадцать пять тысяч", "Осильте примерно 25 000 страниц", state.allTime.estimatedPages, 25_000, Icons.Default.Star, AchievementPurple),

        badge("Марафоны", "Полчаса без отрыва", "Сессия не короче 30 минут", state.longestSessionSeconds, 1_800, Icons.Default.Timer, AchievementBlue),
        badge("Марафоны", "Часовой марафон", "Сессия не короче 1 часа", state.longestSessionSeconds, 3_600, Icons.Default.Timer, AchievementIndigo),
        badge("Марафоны", "Два часа", "Сессия не короче 2 часов", state.longestSessionSeconds, 7_200, Icons.Default.Schedule, AchievementGold),
        badge("Марафоны", "Три часа", "Сессия не короче 3 часов", state.longestSessionSeconds, 10_800, Icons.Default.EmojiEvents, AchievementPurple),

        badge("Активные дни", "Месяц чтения", "Читайте хотя бы в 30 разных дней", state.activeReadingDays.toLong(), 30, Icons.Default.CalendarMonth, AchievementGreen),
        badge("Активные дни", "Сто читательских дней", "Читайте хотя бы в 100 разных дней", state.activeReadingDays.toLong(), 100, Icons.Default.CalendarMonth, AchievementGold),
        badge("Активные дни", "Год читательских дней", "Накопите 365 активных дней", state.activeReadingDays.toLong(), 365, Icons.Default.CalendarMonth, AchievementPurple),

        badge("Темп", "Быстрый ритм", "Устойчивая сессия на 250 сл/мин", state.personalRecords.fastestStableWpm.toLong(), 250, Icons.Default.Speed, AchievementBlue),
        badge("Темп", "Скорочтец", "Устойчивая сессия на 300 сл/мин", state.personalRecords.fastestStableWpm.toLong(), 300, Icons.Default.Speed, AchievementIndigo),
        badge("Темп", "Турборежим", "Устойчивая сессия на 400 сл/мин", state.personalRecords.fastestStableWpm.toLong(), 400, Icons.Default.Bolt, AchievementGold),
        badge("Темп", "Молния", "Устойчивая сессия на 500 сл/мин", state.personalRecords.fastestStableWpm.toLong(), 500, Icons.Default.Bolt, AchievementPurple),

        badge("Рекорды дня", "Десять тысяч за день", "Прочитайте 10 000 слов за один день", state.personalRecords.maxWordsDay, 10_000, Icons.Default.Article, AchievementGreen),
        badge("Рекорды дня", "Двадцать пять тысяч", "Прочитайте 25 000 слов за один день", state.personalRecords.maxWordsDay, 25_000, Icons.Default.Article, AchievementGold),
        badge("Рекорды дня", "Пятьдесят тысяч", "Прочитайте 50 000 слов за один день", state.personalRecords.maxWordsDay, 50_000, Icons.Default.EmojiEvents, AchievementPurple),

        badge("Цели", "Цель взята", "Выполните дневную цель", state.goalStreakDays.toLong(), 1, Icons.Default.TrackChanges, AchievementGreen),
        badge("Цели", "Неделя по плану", "Выполняйте дневную цель 7 дней подряд", state.goalStreakDays.toLong(), 7, Icons.Default.TrackChanges, AchievementGreen),
        badge("Цели", "Месяц дисциплины", "Выполняйте дневную цель 30 дней подряд", state.goalStreakDays.toLong(), 30, Icons.Default.TrackChanges, AchievementGold),
        badge("Цели", "Годовой план", "Выполните годовую цель по книгам", if (state.yearlyGoalProgress >= 1f) 1 else 0, 1, Icons.Default.EmojiEvents, AchievementPurple),

        badge("Ритм", "Ночная смена", "Накопите 5 часов чтения с 23:00 до 05:00", nightSeconds, 18_000, Icons.Default.Bedtime, AchievementIndigo),
        badge("Ритм", "Ранний старт", "Накопите 5 часов чтения с 05:00 до 11:00", morningSeconds, 18_000, Icons.Default.WbSunny, AchievementGold),
        badge("Ритм", "Выходной книжник", "Накопите 10 часов чтения по выходным", weekendSeconds, 36_000, Icons.Default.CalendarMonth, AchievementGreen),

        badge("Библиотека", "Любимая пятёрка", "Добавьте 5 книг в избранное", state.favoriteBookCount.toLong(), 5, Icons.Default.Favorite, AchievementOrange),
        badge("Библиотека", "Навести порядок", "Создайте или используйте 5 полок", state.shelfCount.toLong(), 5, Icons.Default.Folder, AchievementBlue),
        badge("Библиотека", "Серийный читатель", "Соберите 3 книжные серии", state.seriesCount.toLong(), 3, Icons.Default.CollectionsBookmark, AchievementIndigo),
        badge("Библиотека", "Коллекционер серий", "Соберите 10 книжных серий", state.seriesCount.toLong(), 10, Icons.Default.CollectionsBookmark, AchievementPurple),

        badge("Книжные месяцы", "Три за месяц", "Завершите 3 книги за один месяц", state.personalRecords.maxCompletedBooksMonth.toLong(), 3, Icons.Default.AutoStories, AchievementGreen),
        badge("Книжные месяцы", "Пять за месяц", "Завершите 5 книг за один месяц", state.personalRecords.maxCompletedBooksMonth.toLong(), 5, Icons.Default.EmojiEvents, AchievementGold),
        badge("Книжные месяцы", "Десять за месяц", "Завершите 10 книг за один месяц", state.personalRecords.maxCompletedBooksMonth.toLong(), 10, Icons.Default.Star, AchievementPurple)
    )
}
