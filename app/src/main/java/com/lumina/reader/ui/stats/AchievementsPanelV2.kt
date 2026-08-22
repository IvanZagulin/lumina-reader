package com.lumina.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.YearMonth

private enum class BadgeRarity(val title: String, val xp: Int, val color: Color) {
    COMMON("Обычная", 50, Color(0xFF78909C)),
    UNCOMMON("Необычная", 100, Color(0xFF10B981)),
    RARE("Редкая", 200, Color(0xFF2563EB)),
    EPIC("Эпическая", 400, Color(0xFF8B5CF6)),
    LEGENDARY("Легендарная", 800, Color(0xFFF59E0B))
}

private enum class BadgeTier(val title: String, val multiplier: Float) {
    BRONZE("Бронза", 1f),
    SILVER("Серебро", 1.15f),
    GOLD("Золото", 1.35f),
    PLATINUM("Платина", 1.6f)
}

private data class AchievementV2(
    val category: String,
    val title: String,
    val detail: String,
    val current: Long,
    val target: Long,
    val icon: ImageVector,
    val rarity: BadgeRarity,
    val tier: BadgeTier = BadgeTier.BRONZE,
    val secret: Boolean = false
) {
    val unlocked: Boolean get() = target > 0 && current >= target
    val progress: Float get() = if (target <= 0) 0f else (current.toFloat() / target).coerceIn(0f, 1f)
    val earnedXp: Int get() = if (unlocked) (rarity.xp * tier.multiplier).toInt() else 0
    val visibleTitle: String get() = if (secret && !unlocked) "???" else title
    val visibleDetail: String get() = if (secret && !unlocked) "Секретное достижение. Условие откроется после выполнения." else detail
}

@Composable
fun AchievementsPanelV2(base: ReadingStatsUiState, advanced: AdvancedStatsUiState) {
    val badges = buildAchievementsV2(base, advanced)
    val unlocked = badges.count(AchievementV2::unlocked)
    val xp = badges.sumOf(AchievementV2::earnedXp)
    val level = xp / 1_000 + 1
    val xpInLevel = xp % 1_000
    val next = badges.filterNot(AchievementV2::unlocked).maxByOrNull(AchievementV2::progress)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        AchievementRing(unlocked.toFloat() / badges.size.coerceAtLeast(1), Modifier.size(82.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(unlocked.toString(), fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                            Text("из ${badges.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Коллекция достижений", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Обычные, редкие и секретные медали", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = BadgeRarity.LEGENDARY.color)
                            Spacer(Modifier.width(8.dp))
                            Text("Уровень читателя $level", fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Text("$xp XP", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { xpInLevel / 1000f },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                            color = BadgeRarity.EPIC.color,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("$xpInLevel / 1000 XP до уровня ${level + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        next?.let { badge ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = badge.rarity.color.copy(alpha = 0.10f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BadgeIcon(badge, 50)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ближайшая медаль", style = MaterialTheme.typography.labelSmall, color = badge.rarity.color)
                        Text(badge.visibleTitle, fontWeight = FontWeight.ExtraBold)
                        Text(badge.visibleDetail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(7.dp))
                        LinearProgressIndicator(
                            progress = { badge.progress },
                            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                            color = badge.rarity.color,
                            trackColor = badge.rarity.color.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        }

        RarityLegend()

        badges.groupBy(AchievementV2::category).forEach { (category, group) ->
            Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            group.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { badge -> AchievementTile(badge, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AchievementTile(badge: AchievementV2, modifier: Modifier) {
    Card(
        modifier = modifier.padding(bottom = 10.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (badge.unlocked) badge.rarity.color.copy(alpha = 0.11f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BadgeIcon(badge, 48)
            Spacer(Modifier.height(8.dp))
            Text(
                badge.visibleTitle,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (badge.unlocked) "${badge.tier.title} · ${badge.rarity.title}" else badge.rarity.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (badge.unlocked) badge.rarity.color else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(5.dp))
            Text(
                badge.visibleDetail,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                progress = { badge.progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = badge.rarity.color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (badge.unlocked) "+${badge.earnedXp} XP" else "${badge.current.coerceAtMost(badge.target)} / ${badge.target}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BadgeIcon(badge: AchievementV2, size: Int) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        color = if (badge.unlocked) badge.rarity.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (badge.secret && !badge.unlocked) Icons.Default.Lock else badge.icon,
                null,
                tint = if (badge.unlocked) badge.rarity.color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size * 0.48f).dp)
            )
        }
    }
}

@Composable
private fun AchievementRing(progress: Float, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val stroke = size.minDimension * 0.095f
        drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(BadgeRarity.LEGENDARY.color, -90f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun RarityLegend() {
    LazyRowCompat(BadgeRarity.entries)
}

@Composable
private fun LazyRowCompat(items: List<BadgeRarity>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { rarity ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = rarity.color.copy(alpha = 0.11f)
            ) {
                Text(
                    rarity.title,
                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 3.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = rarity.color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

private fun buildAchievementsV2(base: ReadingStatsUiState, advanced: AdvancedStatsUiState): List<AchievementV2> {
    val result = mutableListOf<AchievementV2>()
    val hours = base.allTime.durationSeconds / 3600
    val pages = base.allTime.estimatedPages
    val words = base.allTime.wordsRead
    val sessions = base.allTime.sessionCount.toLong()

    fun chain(
        category: String,
        titles: List<String>,
        current: Long,
        targets: List<Long>,
        unit: String,
        icon: ImageVector
    ) {
        targets.forEachIndexed { index, target ->
            val rarity = when (index) {
                0 -> BadgeRarity.COMMON
                1 -> BadgeRarity.UNCOMMON
                2 -> BadgeRarity.RARE
                3 -> BadgeRarity.EPIC
                else -> BadgeRarity.LEGENDARY
            }
            val tier = when (index) {
                0 -> BadgeTier.BRONZE
                1 -> BadgeTier.SILVER
                2 -> BadgeTier.GOLD
                else -> BadgeTier.PLATINUM
            }
            result += AchievementV2(category, titles.getOrElse(index) { "Уровень ${index + 1}" }, "$target $unit", current, target, icon, rarity, tier)
        }
    }

    chain("Время", listOf("Первая смена", "Завсегдатай", "Книжный жилец", "Сто часов", "Часть мебели"), hours, listOf(1, 10, 50, 100, 500), "часов чтения", Icons.Default.Schedule)
    chain("Стрики", listOf("Три дня", "Неделя с книгой", "Две недели", "Месяц без отговорок", "Годовая машина"), base.bestStreakDays.toLong(), listOf(3, 7, 14, 30, 365), "дней подряд", Icons.Default.LocalFireDepartment)
    chain("Книги", listOf("Первая закрыта", "Тройка", "Пять финалов", "Десятка", "Сотня"), base.completedBookCount.toLong(), listOf(1, 3, 5, 10, 100), "завершённых книг", Icons.Default.AutoStories)
    chain("Слова", listOf("Разогрев", "Поток", "Сто тысяч", "Миллионер", "Десять миллионов"), words, listOf(10_000, 50_000, 100_000, 1_000_000, 10_000_000), "слов", Icons.Default.Article)
    chain("Страницы", listOf("Сто страниц", "Пять сотен", "Тысяча", "Пять тысяч", "Двадцать пять тысяч"), pages, listOf(100, 500, 1_000, 5_000, 25_000), "страниц", Icons.Default.MenuBook)
    chain("Сессии", listOf("Привычка", "Пятьдесят заходов", "Сотня сессий", "Пять сотен", "Тысяча подходов"), sessions, listOf(10, 50, 100, 500, 1_000), "сессий", Icons.Default.Timer)
    chain("Активные дни", listOf("Неделя активности", "Месяц активности", "Сто дней", "Год с книгой"), base.activeReadingDays.toLong(), listOf(7, 30, 100, 365), "активных дней", Icons.Default.CalendarMonth)
    chain("Длинные сессии", listOf("Полчаса", "Час без побега", "Два часа", "Три часа подряд"), advanced.sessionAnalytics.longestSeconds / 60, listOf(30, 60, 120, 180), "минут за одну сессию", Icons.Default.Timer)
    chain("Темп", listOf("Двести", "Двести пятьдесят", "Триста", "Четыреста"), base.personalRecords.fastestStableWpm.toLong(), listOf(200, 250, 300, 400), "слов/мин устойчивого темпа", Icons.Default.Speed)
    chain("День-рекорд", listOf("5K за день", "10K за день", "20K за день", "50K за день"), base.personalRecords.maxWordsDay, listOf(5_000, 10_000, 20_000, 50_000), "слов за день", Icons.Default.Bolt)
    chain("Цели", listOf("Цель ×3", "Идеальная неделя", "Месяц дисциплины", "Сто дней цели"), base.goalStreakDays.toLong(), listOf(3, 7, 30, 100), "дней подряд с выполненной целью", Icons.Default.TrackChanges)
    chain("Книги за месяц", listOf("Тройной финиш", "Пять за месяц", "Десять за месяц"), base.personalRecords.maxCompletedBooksMonth.toLong(), listOf(3, 5, 10), "книг за месяц", Icons.Default.EmojiEvents)
    chain("Ночной режим", listOf("Ночная глава", "Полуночник", "Сова со стажем"), advanced.habits.nightSessionCount.toLong(), listOf(1, 10, 50), "ночных сессий", Icons.Default.Bedtime)
    chain("Утро", listOf("Ранняя глава", "Утренний ритуал", "Жаворонок"), advanced.habits.morningSessionCount.toLong(), listOf(1, 10, 50), "утренних сессий", Icons.Default.WbSunny)
    chain("Библиотека", listOf("Пять любимых", "Двадцать любимых"), base.favoriteBookCount.toLong(), listOf(5, 20), "книг в избранном", Icons.Default.Favorite)
    chain("Полки", listOf("Три полки", "Десять полок"), base.shelfCount.toLong(), listOf(3, 10), "полок", Icons.Default.Folder)
    chain("Серии", listOf("Три серии", "Десять серий"), base.seriesCount.toLong(), listOf(3, 10), "серий в библиотеке", Icons.Default.CollectionsBookmark)

    fun secret(title: String, detail: String, condition: Boolean, icon: ImageVector, rarity: BadgeRarity = BadgeRarity.EPIC) {
        result += AchievementV2("Секретные", title, detail, if (condition) 1 else 0, 1, icon, rarity, BadgeTier.PLATINUM, true)
    }

    secret("Кто вообще спит?", "Прочитать одной ночной сессией не меньше двух часов", advanced.habits.longestNightSessionSeconds >= 7_200, Icons.Default.Bedtime, BadgeRarity.LEGENDARY)
    secret("Ещё одну главу", "Одна сессия дольше трёх часов", advanced.sessionAnalytics.longestSeconds >= 10_800, Icons.Default.Timer, BadgeRarity.EPIC)
    secret("С возвращением", "Вернуться к чтению после перерыва минимум 30 дней", advanced.habits.comebackAfter30Days > 0, Icons.Default.History)
    secret("Мы всё ещё здесь", "Вернуться после перерыва минимум 90 дней", advanced.habits.comebackAfter90Days > 0, Icons.Default.History, BadgeRarity.LEGENDARY)
    secret("Выходные для книг", "Прочитать суммарно пять часов за одни выходные", advanced.habits.maxWeekendSeconds >= 18_000, Icons.Default.CalendarMonth)
    secret("Ранний подъём был не зря", "Читать хотя бы раз до 06:00", advanced.habits.beforeSixSessionCount > 0, Icons.Default.WbSunny)
    secret("Полночный марафон", "Одна сессия пересекла полночь", advanced.habits.crossMidnightSessionCount > 0, Icons.Default.Bedtime)
    secret("Не оторваться", "Прочитать 20 000 слов за один день", base.personalRecords.maxWordsDay >= 20_000, Icons.Default.Article)
    secret("Закрыл гештальт", "Завершить книгу после перерыва в её чтении больше 30 дней", advanced.habits.returnedAndCompletedBooks > 0, Icons.Default.CheckCircle, BadgeRarity.LEGENDARY)
    secret("Круглые сутки", "Хотя бы раз читать в каждом из 24 часов суток", advanced.habits.distinctHoursRead >= 24, Icons.Default.Schedule, BadgeRarity.LEGENDARY)
    secret("Полная неделя", "Читать хотя бы раз в каждый день недели", advanced.habits.distinctWeekdaysRead >= 7, Icons.Default.CalendarMonth)
    secret("Семь воскресений", "Читать в семь разных воскресений", advanced.habits.activeSundayCount >= 7, Icons.Default.CalendarMonth)
    secret("Пятничный вечер", "Семь сессий в пятницу вечером", advanced.habits.fridayEveningSessionCount >= 7, Icons.Default.Bedtime)
    secret("Всеядный", "Завершить книги как минимум четырёх разных форматов", advanced.habits.completedFormats >= 4, Icons.Default.Description)
    secret("Исследователь", "Прочитать книги десяти разных авторов", advanced.habits.distinctAuthors >= 10, Icons.Default.Person)
    secret("Библиограф", "Прочитать книги пятидесяти разных авторов", advanced.habits.distinctAuthors >= 50, Icons.Default.Person, BadgeRarity.LEGENDARY)
    secret("Верный читатель", "Иметь минимум три книги одного автора", advanced.habits.maxBooksSameAuthor >= 3, Icons.Default.Person, BadgeRarity.RARE)
    secret("Фанат автора", "Иметь минимум пять книг одного автора", advanced.habits.maxBooksSameAuthor >= 5, Icons.Default.Person)
    secret("Монография жизни", "Иметь минимум десять книг одного автора", advanced.habits.maxBooksSameAuthor >= 10, Icons.Default.Person, BadgeRarity.LEGENDARY)
    secret("Финал сезона", "Полностью завершить первую книжную серию", advanced.habits.completedSeriesCount >= 1, Icons.Default.CollectionsBookmark)
    secret("Три финала", "Полностью завершить три книжные серии", advanced.habits.completedSeriesCount >= 3, Icons.Default.CollectionsBookmark, BadgeRarity.EPIC)
    secret("Сериальный маньяк", "Полностью завершить десять серий", advanced.habits.completedSeriesCount >= 10, Icons.Default.CollectionsBookmark, BadgeRarity.LEGENDARY)
    secret("Большой цикл", "Завершить целиком серию минимум из пяти книг", advanced.habits.maxSeriesSizeCompleted >= 5, Icons.Default.CollectionsBookmark, BadgeRarity.LEGENDARY)
    secret("Запоем", "Завершить подряд три книги одной серии", advanced.habits.maxConsecutiveSeriesCompletions >= 3, Icons.Default.CollectionsBookmark, BadgeRarity.EPIC)
    secret("Идеальная неделя", "Выполнить дневную цель семь дней одной недели", hasPerfectGoalWeek(base), Icons.Default.TrackChanges, BadgeRarity.EPIC)
    secret("Идеальный месяц", "Выполнить дневную цель каждый день полного календарного месяца", hasPerfectGoalMonth(base), Icons.Default.TrackChanges, BadgeRarity.LEGENDARY)
    secret("Без пропусков", "Читать каждый день полного календарного месяца", hasPerfectReadingMonth(base), Icons.Default.LocalFireDepartment, BadgeRarity.LEGENDARY)

    return result
}

private fun hasPerfectGoalWeek(base: ReadingStatsUiState): Boolean {
    val items = base.dailyActivity.sortedBy { it.date }
    if (items.size < 7) return false
    return items.windowed(7).any { week ->
        week.zipWithNext().all { (a, b) -> b.date == a.date.plusDays(1) } && week.all { goalMet(it, base.goalSettings) }
    }
}

private fun hasPerfectGoalMonth(base: ReadingStatsUiState): Boolean {
    return base.dailyActivity.groupBy { YearMonth.from(it.date) }.any { (month, days) ->
        days.size == month.lengthOfMonth() && days.all { goalMet(it, base.goalSettings) }
    }
}

private fun hasPerfectReadingMonth(base: ReadingStatsUiState): Boolean {
    return base.dailyActivity.groupBy { YearMonth.from(it.date) }.any { (month, days) ->
        days.size == month.lengthOfMonth() && days.all { it.sessionCount > 0 }
    }
}

private fun goalMet(day: DailyReadingActivity, settings: StatsGoalSettings): Boolean {
    val value = when (settings.dailyType) {
        DailyGoalType.MINUTES -> day.durationSeconds / 60f
        DailyGoalType.PAGES -> day.estimatedPages.toFloat()
        DailyGoalType.WORDS -> day.wordsRead.toFloat()
    }
    return value >= settings.dailyTarget
}
