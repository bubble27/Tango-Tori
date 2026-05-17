package com.tangotori.app.domain.usecases

import com.tangotori.app.data.jmdict.JmdictRepository
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.PartOfSpeech
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

class LookupWordUseCase @Inject constructor(
    private val repo: JmdictRepository,
) {
    suspend operator fun invoke(token: Token): List<DictEntry> {
        val entries = repo.lookup(token.dictionaryForm, token.reading)
        // For a token Sudachi classified as a particle, reorder the JMdict
        // result so any entry marked `prt` floats to the top. Otherwise a
        // common nominal homograph (e.g. `に` as a counter) can sit ahead of
        // the particle reading and confuse the disambiguation tabs.
        return when (token.partOfSpeech) {
            PartOfSpeech.PARTICLE -> entries.sortedByDescending { it.isParticle() }
            else -> entries
        }
    }

    private fun DictEntry.isParticle(): Boolean =
        senses.any { sense -> sense.partOfSpeech.any { it == "prt" } }
}
