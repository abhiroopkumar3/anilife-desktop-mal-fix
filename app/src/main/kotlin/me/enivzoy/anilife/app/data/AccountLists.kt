package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.enivzoy.anilife.core.model.AnimeCard
import kotlin.math.roundToInt

/** The two list services this app can sign in to. */
enum class AccountService(val label: String) {
    MY_ANIME_LIST("MyAnimeList"),
    ANI_LIST("AniList"),
}

/** One title on the signed-in account's list, in the terms both services share. */
data class AccountEntry(
    val malId: Long?,
    val anilistId: Long?,
    val title: String,
    /** AniList-style format name (TV, MOVIE, OVA...), which is what the XML writer expects. */
    val format: String?,
    val episodes: Int?,
    val progress: Int,
    /** Whole points out of ten; 0 when unscored. */
    val score: Int,
    val status: AniListListStatus,
    val updatedAt: Long,
)

/** What adding the saved titles to an account's Plan to Watch did. */
data class PlannedPush(
    val added: Int = 0,
    val alreadyThere: Int = 0,
    val withoutId: Int = 0,
    val failed: Int = 0,
)

/**
 * The signed-in list service, as the backup and XML code see it. Kept behind an interface so the
 * rules for what to read and what to add can be tested without a network.
 */
interface AccountLists {
    /** The account that is the source of truth right now, or null when the PC's own list is. */
    fun active(): AccountService?

    /** The name on the active account, for the XML header. */
    fun accountName(): String?

    /** The whole list of the active account. Throws when it cannot be read. */
    suspend fun read(): List<AccountEntry>

    /**
     * Add every title the account lacks as Plan to Watch. Titles already on the list keep whatever
     * status they have. Throws only when the list itself cannot be read.
     */
    suspend fun addPlanned(cards: List<AnimeCard>): PlannedPush
}

/** No account: the PC's own list is the only list. */
object NoAccountLists : AccountLists {
    override fun active(): AccountService? = null
    override fun accountName(): String? = null
    override suspend fun read(): List<AccountEntry> = emptyList()
    override suspend fun addPlanned(cards: List<AnimeCard>) = PlannedPush()
}

/**
 * The live accounts. With one account connected it is the source of truth. With both connected the
 * choice is [primary], which is nothing until the viewer picks one, so the PC's list stands.
 */
class LiveAccountLists(
    private val primary: () -> AccountService? = { null },
    private val pauseMillis: Long = 200L,
) : AccountLists {
    override fun active(): AccountService? {
        val mal = MyAnimeListSync.user.value != null
        val aniList = AniListSync.user.value != null
        return when {
            mal && aniList -> primary()
            mal -> AccountService.MY_ANIME_LIST
            aniList -> AccountService.ANI_LIST
            else -> null
        }
    }

    override fun accountName(): String? = when (active()) {
        AccountService.MY_ANIME_LIST -> MyAnimeListSync.user.value?.name
        AccountService.ANI_LIST -> AniListSync.user.value?.name
        null -> null
    }?.takeIf(String::isNotBlank)

    override suspend fun read(): List<AccountEntry> = when (active()) {
        AccountService.MY_ANIME_LIST -> MyAnimeListSync.list().map(::fromMal)
        AccountService.ANI_LIST -> {
            AniListSync.refreshList()
            val maximum = AniListSync.user.value?.scoreFormat?.maximum ?: 10.0
            AniListSync.entries.value.map { fromAniList(it, maximum) }
        }
        null -> emptyList()
    }

    override suspend fun addPlanned(cards: List<AnimeCard>): PlannedPush = when (active()) {
        AccountService.MY_ANIME_LIST -> {
            val onList = MyAnimeListSync.list().mapTo(mutableSetOf()) { it.id }
            planMissingTitles(cards, pauseMillis, isThere = { it.malId in onList }, id = { it.malId }) { MyAnimeListSync.addPlanned(it) }
        }
        AccountService.ANI_LIST -> {
            AniListSync.refreshList()
            val onList = AniListSync.entries.value.mapTo(mutableSetOf()) { it.anime.id }
            planMissingTitles(cards, pauseMillis, isThere = { it.id in onList }, id = { it.id }) { id ->
                cards.first { it.id == id }.let { AniListSync.addToList(it) }
            }
        }
        null -> PlannedPush()
    }

    private fun fromMal(entry: MyAnimeListEntry) = AccountEntry(
        malId = entry.id,
        anilistId = null,
        title = entry.title,
        format = malFormat(entry.mediaType),
        episodes = entry.episodes,
        progress = entry.watched,
        score = entry.score.coerceIn(0, 10),
        status = when {
            entry.rewatching -> AniListListStatus.REPEATING
            entry.status == "watching" -> AniListListStatus.CURRENT
            entry.status == "completed" -> AniListListStatus.COMPLETED
            entry.status == "on_hold" -> AniListListStatus.PAUSED
            entry.status == "dropped" -> AniListListStatus.DROPPED
            else -> AniListListStatus.PLANNING
        },
        updatedAt = entry.updatedAt,
    )

    private fun fromAniList(entry: AniListListEntry, maximum: Double) = AccountEntry(
        malId = entry.anime.malId,
        anilistId = entry.anime.id,
        title = entry.anime.title,
        format = entry.anime.format,
        episodes = entry.anime.episodes,
        progress = entry.progress,
        score = if (entry.score > 0 && maximum > 0) (entry.score / maximum * 10).roundToInt().coerceIn(0, 10) else 0,
        status = entry.status,
        updatedAt = entry.updatedAt,
    )

    private fun malFormat(type: String?): String? = when (type?.lowercase()) {
        "movie" -> "MOVIE"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special", "tv_special" -> "SPECIAL"
        "music" -> "MUSIC"
        null -> null
        else -> "TV"
    }
}

/**
 * Add each title the account lacks, one at a time, and count what happened. A title the account
 * already has is never touched, one without an id on that service is only counted, and one that
 * fails does not stop the rest.
 */
internal suspend fun planMissingTitles(
    cards: List<AnimeCard>,
    pauseMillis: Long,
    isThere: (AnimeCard) -> Boolean,
    id: (AnimeCard) -> Long?,
    add: suspend (Long) -> Unit,
): PlannedPush {
    var added = 0
    var there = 0
    var withoutId = 0
    var failed = 0
    for (card in cards) {
        val key = id(card)
        when {
            key == null -> withoutId++
            isThere(card) -> there++
            else -> {
                try {
                    add(key)
                    added++
                    delay(pauseMillis)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failed++
                }
            }
        }
    }
    return PlannedPush(added, there, withoutId, failed)
}
