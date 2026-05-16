package com.tangotori.app.domain.usecases

import com.tangotori.app.data.jmdict.JmdictRepository
import com.tangotori.app.domain.models.DictEntry
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

class LookupWordUseCase @Inject constructor(
    private val repo: JmdictRepository,
) {
    suspend operator fun invoke(token: Token): List<DictEntry> =
        repo.lookup(token.dictionaryForm, token.reading)
}
