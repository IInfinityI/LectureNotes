package com.example.lecturenotes

object TextProcessor {
    private val voiceCommands = mapOf(
        // атематика и числа
        "плюс" to "+", "минус" to "-", "умножить на" to "*", "умножить" to "*",
        "разделить на" to "/", "разделить" to "/", "равно" to "=",
        "ноль" to "0", "один" to "1", "два" to "2", "три" to "3", "четыре" to "4",
        "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9",
        "десять" to "10", "одиннадцать" to "11", "двенадцать" to "12",
        "тринадцать" to "13", "четырнадцать" to "14", "пятнадцать" to "15",
        "двадцать" to "20", "тридцать" to "30", "сорок" to "40", "пятьдесят" to "50",
        "сто" to "100", "тысяча" to "1000",
        
        // унктуация
        "запятая" to ",", "точка" to ".", "двоеточие" to ":", "тире" to "—",
        "вопрос" to "?", "восклицание" to "!", "открыть скобку" to "(",
        "закрыть скобку" to ")", "кавычка" to "\"",
        
        // Форматирование
        "новая строка" to "\n", "красная строка" to "\n", "абзац" to "\n\n", "перенос" to "\n"
    )

    fun applyVoiceCommands(text: String): String {
        var result = text
        val sortedCommands = voiceCommands.toList().sortedByDescending { it.first.length }
        for ((command, symbol) in sortedCommands) {
            result = result.replace(Regex("\\b${Regex.escape(command)}\\b", RegexOption.IGNORE_CASE), symbol)
        }
        // Убираем лишние пробелы вокруг знаков
        result = result.replace(Regex("\\s+([,.\\?!:;—])"), "$1")
        result = result.replace(Regex("([,.\\?!:;—])(\\S)"), "$1 $2")
        return result
    }

    fun solveArithmetic(text: String): String {
        val pattern = """(\d+(?:\.\d+)?)\s*([\+\-\*\/])\s*(\d+(?:\.\d+)?)""".toRegex()
        return pattern.replace(text) { matchResult ->
            try {
                val a = matchResult.groupValues[1].toDouble()
                val op = matchResult.groupValues[2]
                val b = matchResult.groupValues[3].toDouble()
                val result = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b != 0.0) a / b else Double.NaN
                    else -> Double.NaN
                }
                if (result.isNaN()) matchResult.value
                else {
                    val resultStr = if (result == result.toLong().toDouble()) result.toLong().toString() else String.format("%.2f", result).trimEnd('0').trimEnd('.')
                    "${matchResult.value} (=$resultStr)"
                }
            } catch (e: Exception) {
                matchResult.value
            }
        }
    }

    fun processText(text: String): String {
        return solveArithmetic(applyVoiceCommands(text))
    }
}
