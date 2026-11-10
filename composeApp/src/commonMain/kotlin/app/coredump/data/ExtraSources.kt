package app.coredump.data

import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tiering
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lists from free web services that need no account or key, collected like the Wikidata pool (ids start "wx.", so
 * they live in the library, can be discarded, shared in 1v1 …). Each source has a popularity measure, which gives
 * the ranking (common → rare):
 *  - Datamuse (api.datamuse.com): rhymes, synonyms, word endings — ranked by how often the word is used
 *  - Deezer (api.deezer.com): an artist's songs — ranked by plays
 *  - SteamSpy (steamspy.com): video games by Steam tag — ranked by positive reviews
 *  - Open Library (openlibrary.org): books by subject — ranked by number of editions
 *  - PokéAPI (pokeapi.co): Pokémon by type — ranked by generation (first ones best known)
 */
object ExtraSources {
    enum class Kind { RHYME, SYNONYM, ENDING, DEEZER, STEAM, BOOKS, POKEMON }

    class Item(val id: String, val prompt: String, val topic: String, val kind: Kind, val key: String) {
        val minAnswers: Int get() = when (kind) { Kind.SYNONYM -> 12; Kind.POKEMON -> 15; else -> 20 }
    }

    private const val DATAMUSE = "https://api.datamuse.com/words"
    private const val DEEZER = "https://api.deezer.com"
    private const val STEAMSPY = "https://steamspy.com/api.php"
    private const val OPENLIB = "https://openlibrary.org/subjects"
    private const val POKEAPI = "https://pokeapi.co/api/v2"

    private val rhymes = listOf(
        "cat", "light", "day", "fun", "love", "time", "go", "rain", "fire", "blue", "tree", "ring", "cake", "moon", "star",
        "game", "heart", "house", "night", "song", "sing", "book", "dog", "fish", "ball", "bear", "hand", "king", "sea",
        "snow", "bee", "bread", "cold", "gold", "mouse", "nose", "rock", "sun", "town", "whale",
    )
    private val synonyms = listOf(
        "big", "small", "fast", "happy", "sad", "angry", "smart", "stupid", "beautiful", "ugly", "good", "bad", "strange",
        "scared", "tired", "funny", "rich", "poor", "cold", "hot", "old", "new", "loud", "quiet", "brave", "lazy", "crazy",
        "dirty", "clean", "walk", "run", "talk", "eat", "look", "steal",
    )
    private val endings = listOf("ology", "graphy", "cracy", "itis", "phobia", "ism", "archy", "scope", "meter", "cide", "onomy", "ship", "hood", "ware")
    private val artists = listOf(
        "Queen", "The Beatles", "ABBA", "Michael Jackson", "Madonna", "Taylor Swift", "Beyoncé", "Rihanna", "Drake", "Eminem",
        "Coldplay", "Ed Sheeran", "Adele", "Elton John", "David Bowie", "Prince", "Whitney Houston", "Mariah Carey", "Britney Spears",
        "Lady Gaga", "Katy Perry", "Bruno Mars", "The Weeknd", "Billie Eilish", "Ariana Grande", "Dua Lipa", "Harry Styles",
        "Justin Bieber", "Kanye West", "Kendrick Lamar", "Nirvana", "Metallica", "AC/DC", "Led Zeppelin", "Pink Floyd",
        "The Rolling Stones", "U2", "Radiohead", "Oasis", "Arctic Monkeys", "Red Hot Chili Peppers", "Green Day", "Linkin Park",
        "Bob Marley", "Stevie Wonder", "Frank Sinatra", "Elvis Presley", "Johnny Cash", "Dolly Parton", "Shakira", "Daft Punk",
        "Bon Jovi", "Fleetwood Mac", "The Killers", "Imagine Dragons", "Sabrina Carpenter", "Olivia Rodrigo", "Bad Bunny", "SZA",
        "Lana Del Rey", "Avicii", "Calvin Harris", "Kylie Minogue", "Celine Dion", "George Michael", "Tina Turner",
    )
    private val steamTags = listOf(
        "Roguelike" to "roguelike", "Metroidvania" to "metroidvania", "Survival" to "survival", "Puzzle" to "puzzle",
        "Racing" to "racing", "Horror" to "horror", "City Builder" to "city-builder", "Platformer" to "platformer",
        "Visual Novel" to "visual novel", "Open World" to "open-world", "Farming Sim" to "farming", "Rhythm" to "rhythm",
        "Card Game" to "card", "Tower Defense" to "tower defense", "Souls-like" to "soulslike", "Stealth" to "stealth",
        "Battle Royale" to "battle royale", "JRPG" to "JRPG", "Point & Click" to "point-and-click", "Fighting" to "fighting",
        "Space Sim" to "space sim", "Sandbox" to "sandbox", "Bullet Hell" to "bullet hell", "Cozy" to "cozy",
        "Deckbuilding" to "deckbuilding", "Turn-Based Strategy" to "turn-based strategy", "Hack and Slash" to "hack-and-slash",
        "Sports" to "sports", "Co-op" to "co-op", "Pixel Graphics" to "pixel-art",
    )
    private val subjects = listOf(
        "fantasy" to "Name a fantasy book", "science_fiction" to "Name a science fiction book",
        "mystery_and_detective_stories" to "Name a mystery or detective book", "romance" to "Name a romance novel",
        "horror" to "Name a horror book", "thriller" to "Name a thriller", "historical_fiction" to "Name a historical novel",
        "dragons" to "Name a book about dragons", "vampires" to "Name a book about vampires", "pirates" to "Name a book about pirates",
        "witches" to "Name a book about witches", "dystopias" to "Name a dystopian book", "time_travel" to "Name a book about time travel",
        "cats" to "Name a book about cats", "dogs" to "Name a book about dogs", "war" to "Name a book about war",
        "magic" to "Name a book about magic", "space" to "Name a book set in space", "detectives" to "Name a book with a detective",
        "ghosts" to "Name a ghost story", "robots" to "Name a book about robots", "dinosaurs" to "Name a book about dinosaurs",
        "zombies" to "Name a book about zombies", "poetry" to "Name a poetry book", "plays" to "Name a play",
        "juvenile_fiction" to "Name a children's book", "young_adult_fiction" to "Name a young adult novel",
        "biography" to "Name a biography", "humor" to "Name a funny book", "adventure" to "Name an adventure book",
    )
    private val types = listOf("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground", "flying",
        "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy")

    private fun slug(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    val all: List<Item> by lazy {
        buildList {
            for (w in rhymes) add(Item("wx.wo.rhyme.$w", "Name a word that rhymes with “$w”", "WORDS", Kind.RHYME, w))
            for (w in synonyms) add(Item("wx.wo.syn.$w", "Name a word that means “$w”", "WORDS", Kind.SYNONYM, w))
            for (e in endings) add(Item("wx.wo.end.$e", "Name a word ending in “-$e”", "WORDS", Kind.ENDING, e))
            for (a in artists) add(Item("wx.mu.dz.${slug(a)}", "Name a song by $a", "MUSIC", Kind.DEEZER, a))
            for ((tag, words) in steamTags) add(Item("wx.gm.steam.${slug(tag)}", "Name a $words video game", "GAMING", Kind.STEAM, tag))
            for ((sub, prompt) in subjects) add(Item("wx.bk.ol.${slug(sub)}", prompt, "BOOKS", Kind.BOOKS, sub))
            for (t in types) add(Item("wx.gm.poke.$t", "Name a ${t.replaceFirstChar { it.uppercase() }}-type Pokémon", "GAMING", Kind.POKEMON, t))
        }
    }

    val byId: Map<String, Item> by lazy { all.associateBy { it.id } }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code) append(c.toChar())
            else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    /** Species names in national-dex order (one request, kept for the session). */
    private var species: List<String>? = null

    /**
     * Fetches and ranks [x]; [get] does one HTTP GET and returns the body (throws on failure).
     * Returns null when the reply is unusable (then the list is skipped for a while).
     */
    suspend fun fetch(x: Item, get: suspend (String) -> String): Prompt? {
        val ranked: List<RawAnswer> = when (x.kind) {
            Kind.RHYME -> words(get("$DATAMUSE?rel_rhy=${enc(x.key)}&md=f&max=1000"), x.key)
            Kind.SYNONYM -> words(get("$DATAMUSE?rel_syn=${enc(x.key)}&md=f&max=1000"), x.key)
            Kind.ENDING -> words(get("$DATAMUSE?sp=*${enc(x.key)}&md=f&max=1000"), x.key).filter { it.name.length > x.key.length + 1 }
            Kind.DEEZER -> {
                val found = json.parseToJsonElement(get("$DEEZER/search/artist?q=${enc(x.key)}&limit=1")).jsonObject["data"]?.jsonArray
                val id = found?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
                val rows = ArrayList<Pair<String, Long>>()
                var url: String? = "$DEEZER/artist/$id/top?limit=100"
                var pages = 0
                while (url != null && pages < 3) {
                    val o = json.parseToJsonElement(get(url)).jsonObject
                    for (el in o["data"]?.jsonArray.orEmpty()) {
                        val t = el.jsonObject
                        val title = (t.str("title_short") ?: t.str("title"))?.let(::cleanTitle) ?: continue
                        rows += title to (t["rank"]?.jsonPrimitive?.longOrNull ?: 0L)
                    }
                    url = o.str("next")
                    pages++
                }
                rows.sortedByDescending { it.second }.map { RawAnswer(it.first) }
            }
            Kind.STEAM -> {
                val o = json.parseToJsonElement(get("$STEAMSPY?request=tag&tag=${enc(x.key)}")).jsonObject
                o.values.mapNotNull { el ->
                    val g = el as? JsonObject ?: return@mapNotNull null
                    val name = g.str("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 } ?: return@mapNotNull null
                    val pos = g["positive"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (pos < 300) null else name to pos
                }.sortedByDescending { it.second }.map { RawAnswer(cleanTitle(it.first)) }
            }
            Kind.BOOKS -> {
                val o = json.parseToJsonElement(get("$OPENLIB/${x.key}.json?limit=400")).jsonObject
                o["works"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val w = el.jsonObject
                    val title = w.str("title")?.trim()?.takeIf { it.length in 2..70 } ?: return@mapNotNull null
                    val eds = w["edition_count"]?.jsonPrimitive?.intOrNull ?: 0
                    if (eds < 3) null else title to eds
                }.sortedByDescending { it.second }.map { RawAnswer(it.first) }
            }
            Kind.POKEMON -> {
                val names = species ?: run {
                    val o = json.parseToJsonElement(get("$POKEAPI/pokemon-species?limit=2000")).jsonObject
                    o["results"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") }.also { species = it }
                }
                val o = json.parseToJsonElement(get("$POKEAPI/type/${x.key}")).jsonObject
                o["pokemon"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val p = el.jsonObject["pokemon"]?.jsonObject ?: return@mapNotNull null
                    val id = p.str("url")?.trimEnd('/')?.substringAfterLast('/')?.toIntOrNull() ?: return@mapNotNull null
                    // alternate forms have ids from 10001 up: the base species is enough
                    if (id < 1 || id > names.size) null else id to names[id - 1]
                }.distinctBy { it.second }.sortedBy { it.first }.map { pokemon(it.second) }
            }
        }
        if (ranked.size < x.minAnswers) return null
        val entries = Tiering.build(ranked.take(1500), x.id)
        if (entries.size < x.minAnswers) return null
        return Prompt(x.id, x.prompt, x.topic, SourceKind.WIKIDATA, entries, null, null)
    }

    private fun JsonObject.str(k: String): String? = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** Datamuse words ranked by use (tag "f:<per million>"); single plain words only, never the prompt word itself. */
    private fun words(body: String, prompt: String): List<RawAnswer> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val w = o.str("word")?.trim() ?: return@mapNotNull null
            if (w.equals(prompt, ignoreCase = true) || w.length < 2 || !w.all { it.isLetter() || it == '-' }) return@mapNotNull null
            val f = o["tags"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.firstOrNull { it.startsWith("f:") }?.removePrefix("f:")?.toDoubleOrNull() ?: 0.0
            if (f < 0.02) null else w to f
        }.sortedByDescending { it.second }.map { RawAnswer(it.first) }

    /** "Song (Remastered 2011)" / "Song - Live" → "Song". */
    fun cleanTitle(t: String): String = t
        .replace(Regex("""\s*[\(\[][^)\]]*(remaster|live|version|edit|mix|mono|stereo|demo|feat|ft\.|bonus|deluxe|acoustic)[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|live|version|edit|mix|mono|stereo|demo).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private val pokeNames = mapOf(
        "mr-mime" to "Mr. Mime", "mime-jr" to "Mime Jr.", "mr-rime" to "Mr. Rime", "type-null" to "Type: Null",
        "nidoran-f" to "Nidoran♀", "nidoran-m" to "Nidoran♂", "farfetchd" to "Farfetch'd", "sirfetchd" to "Sirfetch'd",
        "flabebe" to "Flabébé", "ho-oh" to "Ho-Oh", "porygon-z" to "Porygon-Z", "jangmo-o" to "Jangmo-o", "hakamo-o" to "Hakamo-o",
        "kommo-o" to "Kommo-o", "chi-yu" to "Chi-Yu", "chien-pao" to "Chien-Pao", "ting-lu" to "Ting-Lu", "wo-chien" to "Wo-Chien",
    )

    /** PokéAPI species name → display name (+ the plain spelling as an alias). */
    private fun pokemon(n: String): RawAnswer {
        val shown = pokeNames[n] ?: n.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        val plain = n.replace('-', ' ')
        return RawAnswer(shown, if (plain.equals(shown, ignoreCase = true)) emptyList() else listOf(plain))
    }
}
