package com.tangotori.app.domain.util

/**
 * Human-readable labels for the JMdict abbreviation codes our entries carry as
 * semicolon-separated strings (partOfSpeech, misc, field, dialect). Lives in
 * `domain/util` so card-side HTML builders and UI composables share one
 * mapping. Unknown codes fall through to the raw code so nothing silently
 * disappears.
 *
 * Source: https://www.edrdg.org/jmdictdb/cgi-bin/edhelp.py?svc=jmdict&sid=
 */
val JmdictPosLabels: Map<String, String> = mapOf(
    // Nouns
    "n" to "Noun",
    "n-pr" to "Proper noun",
    "n-pref" to "Noun prefix",
    "n-suf" to "Noun suffix",
    "n-t" to "Temporal noun",
    "n-adv" to "Adverbial noun",
    "pn" to "Pronoun",

    // Adjectives
    "adj-i" to "I-adjective",
    "adj-ix" to "I-adjective (yoi/ii class)",
    "adj-na" to "Na-adjective",
    "adj-no" to "No-adjective",
    "adj-pn" to "Pre-noun adjectival",
    "adj-t" to "Taru-adjective",
    "adj-f" to "Noun/verb acting prenominally",

    // Adverbs
    "adv" to "Adverb",
    "adv-to" to "Adverb taking と",

    // Verbs
    "v1" to "Ichidan verb",
    "v1-s" to "Ichidan verb (kureru class)",
    "v5" to "Godan verb",
    "v5b" to "Godan verb (-bu)",
    "v5g" to "Godan verb (-gu)",
    "v5k" to "Godan verb (-ku)",
    "v5k-s" to "Godan verb (iku/yuku)",
    "v5m" to "Godan verb (-mu)",
    "v5n" to "Godan verb (-nu)",
    "v5r" to "Godan verb (-ru)",
    "v5r-i" to "Godan verb (-ru, irregular)",
    "v5s" to "Godan verb (-su)",
    "v5t" to "Godan verb (-tsu)",
    "v5u" to "Godan verb (-u)",
    "v5u-s" to "Godan verb (-u, special)",
    "vk" to "Kuru verb (irregular)",
    "vs" to "Suru verb",
    "vs-i" to "Suru verb (included)",
    "vs-s" to "Suru verb (special)",
    "vt" to "Transitive verb",
    "vi" to "Intransitive verb",
    "aux-v" to "Auxiliary verb",
    "aux-adj" to "Auxiliary adjective",
    "aux" to "Auxiliary",

    // Particles & function words
    "prt" to "Particle",
    "conj" to "Conjunction",
    "cop" to "Copula",
    "int" to "Interjection",
    "exp" to "Expression",
    "ctr" to "Counter",
    "num" to "Numeric",

    // Other
    "pref" to "Prefix",
    "suf" to "Suffix",
    "unc" to "Unclassified",
)

val JmdictMiscLabels: Map<String, String> = mapOf(
    "uk" to "Usually written in kana alone",
    "abbr" to "Abbreviation",
    "arch" to "Archaism",
    "col" to "Colloquial",
    "fam" to "Familiar language",
    "hon" to "Honorific",
    "hum" to "Humble",
    "id" to "Idiomatic expression",
    "obs" to "Obsolete",
    "obsc" to "Obscure",
    "on-mim" to "Onomatopoeia / mimesis",
    "poet" to "Poetical",
    "pol" to "Polite",
    "rare" to "Rare",
    "sens" to "Sensitive",
    "sl" to "Slang",
    "vulg" to "Vulgar",
    "X" to "Vulgar / sexual",
    "yoji" to "Yojijukugo (four-character idiom)",
    "derog" to "Derogatory",
    "joc" to "Jocular",
    "chn" to "Children's language",
    "fem" to "Female speech",
    "male" to "Male speech",
)

val JmdictFieldLabels: Map<String, String> = mapOf(
    "math" to "Mathematics",
    "comp" to "Computing",
    "med" to "Medicine",
    "physics" to "Physics",
    "chem" to "Chemistry",
    "biol" to "Biology",
    "bot" to "Botany",
    "zool" to "Zoology",
    "ling" to "Linguistics",
    "music" to "Music",
    "sports" to "Sports",
    "baseb" to "Baseball",
    "sumo" to "Sumo",
    "MA" to "Martial arts",
    "food" to "Food",
    "law" to "Law",
    "econ" to "Economics",
    "finc" to "Finance",
    "Buddh" to "Buddhism",
    "Shinto" to "Shinto",
    "Christn" to "Christianity",
    "anat" to "Anatomy",
    "astron" to "Astronomy",
    "geol" to "Geology",
    "geogr" to "Geography",
    "psych" to "Psychology",
)

val JmdictDialectLabels: Map<String, String> = mapOf(
    "ksb" to "Kansai dialect",
    "ktb" to "Kantou dialect",
    "kyb" to "Kyoto dialect",
    "kyu" to "Kyuushuu dialect",
    "nab" to "Nagano dialect",
    "osb" to "Osaka dialect",
    "rkb" to "Ryuukyuu dialect",
    "thb" to "Touhoku dialect",
    "tsb" to "Tosa dialect",
    "tsug" to "Tsugaru dialect",
)

/**
 * Pretty-print a semicolon-separated list of codes against the given map. Used
 * by both the in-app expanded entry card and the Anki MeaningHtmlBuilder so
 * they agree on POS / misc / field / dialect labels.
 */
fun formatCodes(raw: String?, labels: Map<String, String>): String? {
    if (raw.isNullOrBlank()) return null
    return raw.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { labels[it] ?: it }
        .distinct()
        .joinToString(" · ")
        .ifEmpty { null }
}

/** Format JMdict POS codes as comma-joined human-readable labels. */
fun formatPos(raw: String): String =
    raw.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { JmdictPosLabels[it] ?: it }
        .distinct()
        .joinToString(", ")
