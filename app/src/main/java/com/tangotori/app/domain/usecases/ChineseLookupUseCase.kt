package com.tangotori.app.domain.usecases

import com.tangotori.app.data.cedict.CedictRepository
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

class ChineseLookupUseCase @Inject constructor(
    private val repo: CedictRepository,
) {
    suspend operator fun invoke(token: Token): List<DictEntry> {
        val exact = repo.lookup(token.dictionaryForm)
        if (exact.isNotEmpty() || token.dictionaryForm.length <= 1) return exact
        // Fall back to per-character lookup for Classical Chinese compounds or
        // any multi-char token that jieba produces but CC-CEDICT doesn't have.
        return repo.lookupByChars(token.dictionaryForm)
    }
}
