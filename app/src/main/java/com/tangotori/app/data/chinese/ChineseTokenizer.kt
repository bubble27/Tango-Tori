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
 * [Token.reading] is set to space-separated tone-marked pinyin syllables
 * (e.g. "zhōng guó") so the existing Anki sentence builder can produce ruby
 * markup the same way it does for Japanese. [Token.dictionaryForm] == surface
 * (Chinese verbs don't conjugate in a way that requires a separate citation
 * form for dictionary lookup).
 */
@Singleton
class ChineseTokenizer @Inject constructor() {

    private val initMutex = Mutex()
    private var segmenter: JiebaSegmenter? = null

    // Pre-warm jieba on a background thread the moment this singleton is
    // created (app launch), so the dictionary is loaded before the user
    // first switches to Chinese mode.
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
                val seg = withContext(Dispatchers.IO) { JiebaSegmenter() }
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
            seg.process(text, JiebaSegmenter.SegMode.SEARCH).map { segToken ->
                val surface = segToken.word
                val pinyin = surfacePinyin(surface)
                Token(
                    surface = surface,
                    dictionaryForm = surface,
                    reading = pinyin,
                    dictionaryReading = pinyin,
                    partOfSpeech = classifyPos(surface),
                    rawPosTag = "",
                )
            }
        }
    }

    /** Returns space-separated tone-marked pinyin for a surface string. For
     *  words not in the pinyin4j database (proper nouns, foreign words), returns
     *  an empty string. */
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
        if (surface in CHINESE_PARTICLES)  return PartOfSpeech.PARTICLE
        if (surface in CHINESE_AUXILIARIES) return PartOfSpeech.AUXILIARY_VERB
        if (surface in CHINESE_ADVERBS)    return PartOfSpeech.ADVERB
        if (surface in CHINESE_CONJUNCTIONS) return PartOfSpeech.CONJUNCTION_OTHER
        if (surface in CHINESE_VERBS)      return PartOfSpeech.VERB
        return PartOfSpeech.NOUN
    }

    private fun isPunctuationChar(c: Char): Boolean {
        val code = c.code
        return code in 0x3000..0x303F ||   // CJK Symbols and Punctuation
               code in 0xFF00..0xFFEF ||   // Fullwidth forms
               code in 0x2000..0x206F ||   // General Punctuation
               c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }

    private fun charByCharFallback(text: String): List<Token> =
        text.map { c ->
            Token(
                surface = c.toString(),
                dictionaryForm = c.toString(),
                reading = surfacePinyin(c.toString()),
                dictionaryReading = surfacePinyin(c.toString()),
                partOfSpeech = if (isPunctuationChar(c)) PartOfSpeech.PUNCTUATION else PartOfSpeech.NOUN,
                rawPosTag = "",
            )
        }

    private companion object {
        const val TAG = "ChineseTokenizer"

        // Structural particles (sentence-final, aspect markers). Both scripts.
        val CHINESE_PARTICLES: Set<String> = setOf(
            // Simplified
            "的", "地", "得", "了", "着", "过", "吧", "嘛", "呢", "啊",
            "哦", "哈", "嗯", "哎", "哟", "咦", "哇", "哼", "唉",
            "吗", "啦", "呀", "呗", "嘿",
            // Traditional equivalents
            "著", "過", "嗎", "囉", "吶",
        )

        // Modal / auxiliary verbs and negation. Both scripts.
        val CHINESE_AUXILIARIES: Set<String> = setOf(
            // Simplified
            "是", "不", "没", "没有", "会", "能", "可以", "应该", "要", "想",
            "必须", "可能", "应", "该", "需要", "敢", "肯", "愿意", "愿",
            "宁", "宁愿", "被", "把", "让", "叫", "使", "令",
            // Traditional
            "沒", "沒有", "會", "應該", "應", "該", "必須", "願意", "願",
            "寧", "寧願",
        )

        // Adverbs (degree, frequency, time, scope). Both scripts.
        val CHINESE_ADVERBS: Set<String> = setOf(
            // Scope / focus
            "就", "都", "也", "还", "又", "才", "只", "仅", "便", "即",
            "光", "净", "单",
            // Degree
            "很", "非常", "太", "更", "最", "极", "挺", "比较", "相当",
            "十分", "格外", "特别", "尤其", "稍", "稍微", "略",
            // Time
            "已", "已经", "曾", "曾经", "正", "正在", "将", "将要",
            "刚", "刚刚", "刚才", "马上", "立刻", "立即", "即将",
            "一直", "始终", "总", "总是", "往往", "常", "常常", "经常",
            "偶尔", "有时", "忽然", "突然",
            // Negation modifier (non-standalone)
            "不断", "不再",
            // Traditional equivalents
            "還", "僅", "將", "將要", "已經", "曾經", "剛", "剛剛", "馬上",
            "立刻", "立即", "即將", "一直", "始終", "總", "總是", "常常",
            "經常", "偶爾", "有時", "忽然", "突然",
            "不斷", "不再",
        )

        // Conjunctions and prepositions. Both scripts.
        val CHINESE_CONJUNCTIONS: Set<String> = setOf(
            // Coordinating
            "和", "与", "及", "以及", "或", "或者", "而", "而且", "并", "并且",
            // Subordinating
            "因为", "所以", "但是", "虽然", "如果", "虽", "但", "只要", "只有",
            "不但", "既然", "尽管", "即使", "否则", "不过", "可是", "然而",
            "除非", "无论", "不管", "由于", "为了", "对于", "关于", "至于",
            // Prepositions / coverbs
            "在", "从", "向", "往", "对", "跟", "给", "比", "于", "自",
            "以", "用", "按", "按照", "根据", "通过",
            // Traditional
            "與", "以及", "或者", "並", "並且",
            "因為", "雖然", "雖", "只要", "只有", "不但", "既然", "儘管",
            "即使", "否則", "不過", "可是", "然而", "除非", "無論", "不管",
            "由於", "為了", "對於", "關於", "至於",
            "從", "對", "給", "於", "根據", "通過",
        )

        // Common verbs (single-char and frequent compounds). Both scripts.
        val CHINESE_VERBS: Set<String> = setOf(
            // Single-char action verbs
            "去", "来", "做", "说", "看", "走", "想", "知", "用", "到",
            "有", "写", "读", "学", "教", "问", "答", "听", "唱", "跑",
            "跳", "买", "卖", "吃", "喝", "睡", "起", "坐", "站", "开",
            "关", "进", "出", "上", "下", "回", "打", "拿", "放", "找",
            "见", "爱", "恨", "怕", "活", "死", "生", "变", "成", "过",
            "带", "送", "接", "推", "拉", "切", "洗", "穿", "脱",
            // Common 2-char verbs (simplified)
            "进行", "工作", "学习", "发现", "认为", "觉得", "需要",
            "开始", "结束", "出现", "提供", "建立", "发展", "参加", "解决",
            "了解", "理解", "认识", "记得", "忘记", "注意", "决定", "选择",
            "改变", "增加", "减少", "提高", "降低", "实现", "完成",
            "表示", "说明", "表达", "描述", "介绍", "分析",
            "研究", "讨论", "解释", "证明", "研讨", "探讨",
            "帮助", "支持", "反对", "同意", "拒绝", "相信", "怀疑",
            "希望", "要求", "建议", "允许", "禁止", "保护", "破坏",
            "创造", "设计", "制造", "生产", "使用", "管理", "控制",
            // Traditional equivalents (where different from simplified)
            "來", "說", "寫", "讀", "學", "聽", "買", "賣",
            "開", "關", "進", "愛", "恨", "帶", "穿",
            "進行", "學習", "發現", "認為", "覺得",
            "開始", "結束", "出現", "提供", "建立", "發展", "參加", "解決",
            "了解", "理解", "認識", "記得", "忘記", "注意", "決定", "選擇",
            "改變", "增加", "減少", "提高", "降低", "實現", "完成",
            "表示", "說明", "表達", "描述", "介紹", "分析",
            "研究", "討論", "解釋", "證明", "研討", "探討",
            "幫助", "支持", "反對", "同意", "拒絕", "相信", "懷疑",
            "希望", "要求", "建議", "允許", "禁止", "保護", "破壞",
            "創造", "設計", "製造", "生產", "使用", "管理", "控制",
        )
    }
}
