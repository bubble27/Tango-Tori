package com.tangotori.app.data.chinese

import android.util.Log
import com.huaban.analysis.jieba.JiebaSegmenter
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import com.tangotori.app.domain.util.PinyinToneConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps jieba (segmentation) and pinyin4j (per-character pronunciation) to
 * produce [Token]s from Chinese text. Jieba lazily loads its built-in
 * dictionary on first use (~200 ms); we serialize init under a mutex.
 *
 * POS tags come from Jieba's own dict.txt (format: `word frequency tag`),
 * loaded once at startup via the JAR classpath. Tags are mapped to the app's
 * [PartOfSpeech] enum using Jieba's ICTCLAS tag set. Pattern-based rules
 * handle words Jieba segments contextually but that aren't in dict.txt (e.g.
 * AABB reduplications, 不A不B balanced negations).
 *
 * [Token.reading] is set to space-separated tone-marked pinyin syllables
 * (e.g. "zhōng guó") so the Anki sentence builder can produce ruby markup.
 * [Token.dictionaryForm] == surface (Chinese verbs don't conjugate).
 */
@Singleton
class ChineseTokenizer @Inject constructor() {

    private val initMutex = Mutex()
    private var segmenter: JiebaSegmenter? = null

    // POS lookup table built from Jieba's bundled dict.txt.
    // Volatile so reads on Dispatchers.Default see the write from initMutex.
    @Volatile private var posDict: HashMap<String, PartOfSpeech>? = null

    // Pre-warm jieba (and the POS dict) on a background thread at app launch.
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        warmupScope.launch {
            try { ensureReady() } catch (_: Exception) {}
        }
    }

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        toneType = HanyuPinyinToneType.WITH_TONE_NUMBER
        vCharType = HanyuPinyinVCharType.WITH_V
        caseType = HanyuPinyinCaseType.LOWERCASE
    }

    private suspend fun ensureReady(): JiebaSegmenter {
        segmenter?.let { return it }
        return initMutex.withLock {
            segmenter ?: run {
                val seg = withContext(Dispatchers.IO) {
                    val s = JiebaSegmenter()
                    loadPosDict()   // piggyback on the same IO slot — dict.txt is in the same JAR
                    s
                }
                segmenter = seg
                seg
            }
        }
    }

    suspend fun tokenize(text: String): List<Token> {
        if (text.isBlank()) return emptyList()
        val seg = try {
            ensureReady()
        } catch (e: Exception) {
            Log.e(TAG, "Jieba init failed, falling back to char-by-char", e)
            return charByCharFallback(text)
        }

        return withContext(Dispatchers.Default) {
            seg.sentenceProcess(text).map { surface ->
                val pinyin = surfacePinyin(surface)
                val pos = classifyPos(surface)
                Token(
                    surface = surface,
                    dictionaryForm = surface,
                    reading = pinyin,
                    dictionaryReading = pinyin,
                    partOfSpeech = pos,
                    rawPosTag = "",
                )
            }
        }
    }

    private fun surfacePinyin(surface: String): String {
        return surface.mapNotNull { c ->
            val candidates = try {
                PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat)
            } catch (_: Exception) { null }
            if (candidates.isNullOrEmpty()) null
            else PinyinToneConverter.convertSyllable(candidates[0])
        }.joinToString(" ")
    }

    private fun classifyPos(surface: String): PartOfSpeech {
        if (surface.isNotEmpty() && surface.all { isPunctuationChar(it) })
            return PartOfSpeech.PUNCTUATION

        // Primary: Jieba's own dictionary.
        posDict?.get(surface)?.let { return it }

        // Pattern fallbacks for words Jieba assembles from context but that
        // don't have their own dict.txt entry.

        // VV reduplication (走走, 看看): base char's dict tag decides verb vs adverb.
        if (surface.length == 2 && surface[0] == surface[1] && isCjk(surface[0])) {
            val base = posDict?.get(surface[0].toString())
            return when (base) {
                PartOfSpeech.VERB -> PartOfSpeech.VERB           // 走走, 看看
                PartOfSpeech.NA_ADJECTIVE -> PartOfSpeech.ADVERB // 慢慢, 轻轻 → manner adverb
                else -> PartOfSpeech.ADVERB
            }
        }
        // AABB reduplication (安安静静, 高高兴兴) → descriptive adjective.
        if (surface.length == 4 && surface[0] == surface[1] &&
            surface[2] == surface[3] && surface[0] != surface[2]) return PartOfSpeech.NA_ADJECTIVE
        // 不A不B / 没A没B (不冷不热, 不多不少) → adverb.
        if (surface.length == 4 && surface[0] == surface[2] &&
            (surface[0] == '不' || surface[0] == '没')) return PartOfSpeech.ADVERB

        return PartOfSpeech.NOUN
    }

    /**
     * Reads Jieba's bundled dict.txt from the JAR classpath and builds the
     * word → [PartOfSpeech] lookup table. Format per line: `word freq tag`.
     * Called once inside [ensureReady] on Dispatchers.IO.
     */
    private fun loadPosDict() {
        val dict = HashMap<String, PartOfSpeech>(120_000)
        try {
            val stream = JiebaSegmenter::class.java.getResourceAsStream("/dict.txt")
                ?: run { Log.w(TAG, "dict.txt not found in Jieba JAR"); return }
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val s1 = line.indexOf(' ')
                    if (s1 < 0) continue
                    val s2 = line.indexOf(' ', s1 + 1)
                    if (s2 < 0) continue
                    val word = line.substring(0, s1)
                    val tag  = line.substring(s2 + 1).trimEnd()
                    if (word.isNotEmpty() && tag.isNotEmpty()) {
                        dict[word] = tagToPos(tag)
                    }
                }
            }
            Log.d(TAG, "Loaded ${dict.size} POS entries from Jieba dict.txt")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Jieba POS dict: ${e.message}")
        }
        posDict = dict
    }

    /**
     * Maps Jieba's ICTCLAS tag codes to [PartOfSpeech].
     * Reference: https://gist.github.com/luw2007/6016931 (ICTCLAS tag table)
     */
    private fun tagToPos(tag: String): PartOfSpeech = when (tag) {
        // Verbs
        "v", "vg", "vi", "vf"          -> PartOfSpeech.VERB
        "vd"                            -> PartOfSpeech.ADVERB      // verb-as-adverb (不断地)
        "vn"                            -> PartOfSpeech.VERB        // verbal noun — usually verb in context
        // Adjectives
        "a", "ag", "b", "z"            -> PartOfSpeech.NA_ADJECTIVE
        "an"                            -> PartOfSpeech.NA_ADJECTIVE // adjectival noun
        "ad"                            -> PartOfSpeech.ADVERB      // adjective-as-adverb
        // Adverbs
        "d", "dg"                       -> PartOfSpeech.ADVERB
        "t", "tg"                       -> PartOfSpeech.ADVERB      // time words (今天, 最近)
        "o"                             -> PartOfSpeech.ADVERB      // onomatopoeia
        // Particles
        "u", "ud", "ug", "uj", "ul",
        "uv", "uz"                      -> PartOfSpeech.PARTICLE    // 的/地/得/了/着/过
        "y"                             -> PartOfSpeech.PARTICLE    // sentence-final modal (吧/呢/啊)
        "e"                             -> PartOfSpeech.PARTICLE    // exclamation
        // Conjunctions / prepositions / pronouns / function words
        "p"                             -> PartOfSpeech.CONJUNCTION_OTHER  // preposition (在/从/对)
        "c"                             -> PartOfSpeech.CONJUNCTION_OTHER  // conjunction (和/但/如果)
        "r", "rg"                       -> PartOfSpeech.CONJUNCTION_OTHER  // pronoun (我/你/他)
        "f"                             -> PartOfSpeech.CONJUNCTION_OTHER  // direction word (上/下/里)
        "s"                             -> PartOfSpeech.CONJUNCTION_OTHER  // place word
        // Punctuation
        "w"                             -> PartOfSpeech.PUNCTUATION
        // Everything else → noun (includes n, nr, ns, nt, nz, ng, m, q, i, j, l, ...)
        else                            -> PartOfSpeech.NOUN
    }

    private fun isPunctuationChar(c: Char): Boolean {
        val code = c.code
        return code in 0x3000..0x303F ||   // CJK Symbols and Punctuation
               code in 0xFF00..0xFFEF ||   // Fullwidth forms
               code in 0x2000..0x206F ||   // General Punctuation
               c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    fun singleCharToken(char: Char): Token = Token(
        surface = char.toString(),
        dictionaryForm = char.toString(),
        reading = surfacePinyin(char.toString()),
        dictionaryReading = surfacePinyin(char.toString()),
        partOfSpeech = if (isPunctuationChar(char)) PartOfSpeech.PUNCTUATION else PartOfSpeech.NOUN,
        rawPosTag = "",
    )

    private fun charByCharFallback(text: String): List<Token> =
        text.map { c -> singleCharToken(c) }

    private companion object {
        const val TAG = "ChineseTokenizer"
    }
}
