package com.example.lecturenotes.textprocessor

/**
 * РЕАЛИЗАЦИЯ МОДУЛЯ: TextProcessor
 *
 * Фиксы относительно старого object TextProcessor:
 * 1. \b НЕ РАБОТАЕТ с кириллицей даже с UNICODE_CASE.
 *    Заменено на явные lookaround: (?<![а-яёa-z0-9]) и (?![а-яёa-z0-9]).
 * 2. Класс вместо object — можно подменить, тестировать, отключить.
 * 3. Нет android.util.Log — модуль не знает про Android.
 * 4. Потокобезопасен: все поля immutable, state между вызовами не хранится.
 *
 * USB-ПРИНЦИП:
 * Конструктор принимает словарь команд. Хочешь другие команды — передай свой Map.
 * Хочешь отключить математику — передай enableArithmetic = false.
 */
class DefaultTextProcessor(
    private val commands: Map<String, String> = DEFAULT_COMMANDS,
    private val enableArithmetic: Boolean = true
) : TextProcessor {

    override val voiceCommands: Map<String, String>
        get() = commands

    // ─── ОСНОВНОЙ PIPELINE ───────────────────────────────────────────────

    override fun process(rawText: String): String {
        if (rawText.isBlank()) return rawText

        var result = rawText
        result = applyVoiceCommands(result)
        if (enableArithmetic) {
            result = solveArithmetic(result)
        }
        result = normalize(result)
        return result
    }

    // ─── ГОЛОСОВЫЕ КОМАНДЫ ───────────────────────────────────────────────

    override fun applyVoiceCommands(text: String): String {
        if (text.isBlank()) return text

        var result = text
        for ((phrase, replacement) in commands) {
            // \b не работает с кириллицей. Используем явные lookaround.
            // Граница слова = начало/конец строки, пробел, пунктуация.
            val pattern = Regex(
                "(?<![а-яёa-z0-9])" +
                Regex.escape(phrase) +
                "(?![а-яёa-z0-9])",
                RegexOption.IGNORE_CASE
            )
            result = pattern.replace(result, replacement)
        }
        return result
    }

    // ─── АРИФМЕТИКА ──────────────────────────────────────────────────────

    override fun solveArithmetic(text: String): String {
        if (text.isBlank()) return text

        // Ищем паттерны: число [оператор число]+
        // Поддерживаем: + - * / ( ) и десятичные дроби
        val expressionPattern = Regex(
            """\d+(?:[.,]\d+)?(?:\s*[+\-*/]\s*\d+(?:[.,]\d+)?)+"""
        )

        return expressionPattern.replace(text) { match ->
            val expr = match.value
            val normalized = expr.replace(",", ".")
            val result = evaluate(normalized)
            if (result != null) {
                "$expr (=${formatNumber(result)})"
            } else {
                expr // не удалось вычислить — оставляем как есть
            }
        }
    }

    // ─── НОРМАЛИЗАЦИЯ ────────────────────────────────────────────────────

    private fun normalize(text: String): String {
        return text
            .replace(Regex("\\s{2,}"), " ")   // множественные пробелы → один
            .replace(Regex("\\s+([.,;:!?])"), "$1") // пробел перед пунктуацией
            .replace(Regex("([.,;:!?])([^\\s])"), "$1 $2") // пробел после пунктуации
            .trim()
    }

    // ─── ПАРСЕР АРИФМЕТИКИ (recursive descent) ───────────────────────────
    // Безопасная замена eval. Поддерживает + - * / ( ) и унарный минус.

    private fun evaluate(expression: String): Double? {
        return try {
            val parser = ArithmeticParser(expression.replace(" ", ""))
            val result = parser.parseExpression()
            if (parser.hasMore()) null else result
        } catch (_: Exception) {
            null
        }
    }

    private fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            // максимум 6 знаков после запятой, без хвостовых нулей
            "%.6f".format(value).trimEnd('0').trimEnd('.')
        }
    }

    // ─── КОМПАНЬОН: СЛОВАРЬ ПО УМОЛЧАНИЮ ─────────────────────────────────

    companion object {
        val DEFAULT_COMMANDS: Map<String, String> = linkedMapOf(
            // Арифметика
            "плюс" to "+",
            "минус" to "-",
            "умножить на" to "*",
            "умножить" to "*",
            "разделить на" to "/",
            "разделить" to "/",
            "равно" to "=",
            // Скобки
            "открыть скобку" to "(",
            "закрыть скобку" to ")",
            "открой скобку" to "(",
            "закрой скобку" to ")",
            // Пунктуация
            "точка" to ".",
            "запятая" to ",",
            "двоеточие" to ":",
            "точка с запятой" to ";",
            "восклицательный знак" to "!",
            "вопросительный знак" to "?",
            // Структура
            "новый абзац" to "\n\n",
            "новая строка" to "\n",
            "красная строка" to "\n",
            // Спецсимволы
            "процент" to "%",
            "степень" to "^",
            "корень из" to "√"
        )
    }
}

// ─── ВЛОЖЕННЫЙ ПАРСЕР ────────────────────────────────────────────────────
// Вынесен в private class, чтобы не засорять namespace модуля.
// Грамматика:
//   expression = term (('+' | '-') term)*
//   term       = factor (('*' | '/') factor)*
//   factor     = ['-'] (NUMBER | '(' expression ')')

private class ArithmeticParser(private val input: String) {
    private var pos = 0

    fun hasMore(): Boolean = pos < input.length

    fun parseExpression(): Double {
        var result = parseTerm()
        while (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
            val op = input[pos++]
            val right = parseTerm()
            result = if (op == '+') result + right else result - right
        }
        return result
    }

    private fun parseTerm(): Double {
        var result = parseFactor()
        while (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
            val op = input[pos++]
            val right = parseFactor()
            if (op == '/' && right == 0.0) throw ArithmeticException("Division by zero")
            result = if (op == '*') result * right else result / right
        }
        return result
    }

    private fun parseFactor(): Double {
        // Унарный минус
        if (pos < input.length && input[pos] == '-') {
            pos++
            return -parseFactor()
        }

        // Скобки
        if (pos < input.length && input[pos] == '(') {
            pos++ // skip '('
            val result = parseExpression()
            if (pos < input.length && input[pos] == ')') pos++ // skip ')'
            return result
        }

        // Число
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
            pos++
        }
        if (start == pos) throw NumberFormatException("Expected number at position $pos")
        return input.substring(start, pos).toDouble()
    }
}