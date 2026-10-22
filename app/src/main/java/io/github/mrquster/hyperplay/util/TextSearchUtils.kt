package io.github.mrquster.hyperplay.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.text.Normalizer
import java.util.Locale

object TextSearchUtils {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Приводит строку к нижнему регистру и удаляет диакритические знаки (включая ё -> е)
     * для мгновенного поиска без учёта регистра и диакритики.
     */
    fun normalizeForSearch(input: String): String {
        if (input.isBlank()) return ""
        val lower = input.lowercase(Locale.ROOT).replace('ё', 'е')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").trim()
    }

    /**
     * Подсвечивает все вхождения поискового запроса в тексте акцентным цветом.
     */
    fun highlightQuery(
        text: String,
        rawQuery: String,
        highlightColor: Color
    ): AnnotatedString {
        if (rawQuery.isBlank() || text.isEmpty()) {
            return AnnotatedString(text)
        }
        val normalizedText = text.lowercase(Locale.ROOT).replace('ё', 'е')
        val normalizedQuery = rawQuery.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        if (normalizedQuery.isEmpty()) return AnnotatedString(text)

        return buildAnnotatedString {
            append(text)
            var startIndex = normalizedText.indexOf(normalizedQuery)
            while (startIndex >= 0 && startIndex + normalizedQuery.length <= text.length) {
                addStyle(
                    style = SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.ExtraBold,
                        background = highlightColor.copy(alpha = 0.20f)
                    ),
                    start = startIndex,
                    end = startIndex + normalizedQuery.length
                )
                startIndex = normalizedText.indexOf(normalizedQuery, startIndex + normalizedQuery.length)
            }
        }
    }
}
