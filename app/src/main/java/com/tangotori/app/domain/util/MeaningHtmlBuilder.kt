package com.tangotori.app.domain.util

import com.tangotori.app.domain.models.Sense

/**
 * Renders the Anki Meaning field as a grouped POS-label + ordered-list block:
 *
 *   <div class="pos-label">Noun</div>
 *   <ol class="senses">
 *     <li>companion, fellow, friend, mate, comrade…</li>
 *     <li>group, company, circle, set, gang</li>
 *   </ol>
 *
 * When the POS changes between consecutive senses, a fresh `pos-label` + `ol`
 * pair is emitted for the new group. POS abbreviation codes are translated via
 * [JmdictPosLabels] (`n` → "Noun", `v5r` → "Godan verb", etc.) so cards never
 * show JMdict shorthand. Misc/field/dialect notes are appended inline on each
 * gloss line in muted italic text.
 */
object MeaningHtmlBuilder {

    private const val POS_COLOR = "#78909C"

    fun build(senses: List<Sense>): String {
        if (senses.isEmpty()) return ""
        val groups = groupByConsecutivePos(senses)
        val sb = StringBuilder()
        for (group in groups) {
            val posLabel = formatPos(group.posKey)
            if (posLabel.isNotBlank()) {
                sb.append("<div class=\"pos-label\">")
                    .append(posLabel.htmlEscape())
                    .append("</div>")
            }
            sb.append("<ol class=\"senses\">")
            for (sense in group.senses) {
                val glosses = sense.glosses.filter { it.text.isNotBlank() }
                if (glosses.isEmpty()) continue
                sb.append("<li>")
                sb.append(glosses.joinToString(", ") { it.text.htmlEscape() })
                appendAnnotations(sb, sense)
                sb.append("</li>")
            }
            sb.append("</ol>")
        }
        return sb.toString()
    }

    private fun appendAnnotations(sb: StringBuilder, sense: Sense) {
        val miscLabel = formatCodes(sense.misc, JmdictMiscLabels)
        val fieldLabel = formatCodes(sense.field, JmdictFieldLabels)
        val dialectLabel = formatCodes(sense.dialect, JmdictDialectLabels)
        val annotations = listOfNotNull(miscLabel, fieldLabel, dialectLabel)
        if (annotations.isEmpty()) return
        sb.append(" <small style=\"color:")
            .append(POS_COLOR)
            .append(";\"><i>")
            .append(annotations.joinToString(" · ").htmlEscape())
            .append("</i></small>")
    }

    private data class PosGroup(val posKey: String, val senses: List<Sense>)

    private fun groupByConsecutivePos(senses: List<Sense>): List<PosGroup> {
        val out = mutableListOf<PosGroup>()
        var currentKey: String? = null
        var bucket = mutableListOf<Sense>()
        for (sense in senses) {
            // Group key = the raw codes joined with ';' so identical POS sets
            // collapse into one block even if order differs.
            val key = sense.partOfSpeech.sorted().joinToString(";")
            if (currentKey == null) {
                currentKey = key
                bucket.add(sense)
            } else if (key == currentKey) {
                bucket.add(sense)
            } else {
                out.add(PosGroup(currentKey, bucket))
                currentKey = key
                bucket = mutableListOf(sense)
            }
        }
        if (currentKey != null) out.add(PosGroup(currentKey, bucket))
        return out
    }
}
