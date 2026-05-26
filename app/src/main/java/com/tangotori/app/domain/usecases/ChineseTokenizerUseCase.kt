package com.tangotori.app.domain.usecases

import com.tangotori.app.data.chinese.ChineseTokenizer
import com.tangotori.app.domain.models.Token
import javax.inject.Inject

class ChineseTokenizerUseCase @Inject constructor(
    private val tokenizer: ChineseTokenizer,
) {
    suspend operator fun invoke(sentence: String): List<Token> =
        tokenizer.tokenize(sentence)
}
