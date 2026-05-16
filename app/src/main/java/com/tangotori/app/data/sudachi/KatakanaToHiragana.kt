package com.tangotori.app.data.sudachi

/** Sudachi returns readings in katakana; we want hiragana for furigana display. */
internal fun String.katakanaToHiragana(): String = buildString(length) {
    for (c in this@katakanaToHiragana) {
        append(
            when (c.code) {
                in 0x30A1..0x30F6 -> (c.code - 0x60).toChar()
                else -> c
            }
        )
    }
}
