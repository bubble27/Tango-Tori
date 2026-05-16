package com.tangotori.app.domain.usecases

import com.tangotori.app.data.sudachi.SudachiTokenizer
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

class TokenizeSentenceUseCase @Inject constructor(
    private val tokenizer: SudachiTokenizer,
) {
    suspend operator fun invoke(sentence: String): List<Token> =
        tokenizer.tokenize(sentence)
}
