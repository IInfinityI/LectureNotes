package com.example.lecturenotes

object TextProcessor {
    // Словарь голосовых команд (от лица лектора)
    private val voiceCommands = mapOf(
        // атематика
        "плюс" to "+",
        "минус" to "-",
        "умножить на" to "*",
        "умножить" to "*",
        "разделить на" to "/",
        "разделить" to "/",
        "равно" to "=",
        
        // унктуация
        "запятая" to ",",
        "точка" to ".",
        "двоеточие" to ":",
        "тире" to "—",
        "вопрос" to "?",
        "восклицание" to "!",
        "открыть скобку" to "(",
        "закрыть скобку" to ")",
        "кавычка" to "\"",
        
        // орматирование
        "новая строка" to "\n",
        "красная строка" to "\n",
        "абзац" to "\n\n",
        "перенос" to "\n"
    )

    // рименяем голосовые команды
    fun applyVoiceCommands(text: String): String {
        var result = text
        // Сортируем по длине ключа (сначала длинные фразы, чтобы "умножить на" заменилось раньше "умножить")
        val sortedCommands = voiceCommands.toList().sortedByDescending { it.first.length }
        
        for ((command, symbol) in sortedCommands) {
            // аменяем команду на символ, учитывая границы слов
            result = result.replace(Regex("\\b${Regex.escape(command)}\\b", RegexOption.IGNORE_CASE), symbol)
        }
        
        // бираем лишние пробелы вокруг знаков препинания
        result = result.replace(Regex("\\s+([,\\.\\?!:;—])"), "$1")
        result = result.replace(Regex("([,\\.\\?!:;—])(\\S)"), "$1 $2")
        
        return result
    }

    // ычисляем простые арифметические выражения
    fun solveArithmetic(text: String): String {
        // аттерн для чисел с операторами: "6+7", "10 - 3", "5 * 2"
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
                
                if (result.isNaN()) {
                    matchResult.value // сли деление на 0, оставляем как есть
                } else {
                    val resultStr = if (result == result.toLong().toDouble()) {
                        result.toLong().toString()
                    } else {
                        String.format("%.2f", result).trimEnd('0').trimEnd('.')
                    }
                    "${matchResult.value} (=$resultStr)"
                }
            } catch (e: Exception) {
                matchResult.value
            }
        }
    }

    // олная обработка текста
    fun processText(text: String): String {
        // Сначала заменяем голосовые команды, потом считаем арифметику
        val withCommands = applyVoiceCommands(text)
        return solveArithmetic(withCommands)
    }
}
