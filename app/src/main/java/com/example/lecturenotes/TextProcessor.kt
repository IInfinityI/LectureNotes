package com.example.lecturenotes

import android.util.Log

object TextProcessor {
    
    private const val TAG = "TextProcessor"
    
    // Голосовые команды и их замены
    private val voiceCommands = mapOf(
        "плюс" to "+",
        "минус" to "-",
        "умножить на" to "*",
        "умножить" to "*",
        "разделить на" to "/",
        "разделить" to "/",
        "равно" to "=",
        "запятая" to ",",
        "точка" to ".",
        "двоеточие" to ":",
        "тире" to "—",
        "вопрос" to "?",
        "восклицание" to "!",
        "открыть скобку" to "(",
        "закрыть скобку" to ")",
        "кавычка" to """,
        "новая строка" to "\n",
        "красная строка" to "\n",
        "абзац" to "\n\n",
        "перенос" to "\n"
    )
    
    /**
     * Применение голосовых команд к тексту
     * Заменяет слова-команды на соответствующие символы
     */
    fun applyVoiceCommands(text: String): String {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided to applyVoiceCommands")
            return text
        }
        
        var result = text
        
        // Сортируем команды по длине (длинные сначала, чтобы "умножить на" заменилось раньше, чем "умножить")
        val sortedCommands = voiceCommands.toList().sortedByDescending { it.first.length }
        
        for ((command, symbol) in sortedCommands) {
            // Используем UNICODE_CASE для корректной работы с кириллицей
            val regex = Regex(
                "\\b${Regex.escape(command)}\\b",
                setOf(RegexOption.IGNORE_CASE, RegexOption.UNICODE_CASE)
            )
            result = regex.replace(result, symbol)
        }
        
        // Убираем лишние пробелы перед знаками препинания
        result = result.replace(Regex("""\s+([,.?!:;—])"""), "$1")
        
        // Добавляем пробел после знаков препинания, если его нет
        result = result.replace(Regex("""([,.?!:;—])(\S)"""), "$1 $2")
        
        Log.d(TAG, "Voice commands applied: '${text.take(20)}...' -> '${result.take(20)}...'")
        
        return result
    }
    
    /**
     * Вычисление математических выражений в тексте
     * Находит выражения вида "2 + 2" и добавляет результат в скобках
     */
    fun solveArithmetic(text: String): String {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided to solveArithmetic")
            return text
        }
        
        // Паттерн для простых выражений: число оператор число
        val pattern = """(\d+(?:\.\d+)?)\s*([+\-*/])\s*(\d+(?:\.\d+)?)""".toRegex()
        
        val result = pattern.replace(text) { matchResult ->
            try {
                val a = matchResult.groupValues[1].toDouble()
                val op = matchResult.groupValues[2]
                val b = matchResult.groupValues[3].toDouble()
                
                val calcResult = when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b != 0.0) a / b else Double.NaN
                    else -> Double.NaN
                }
                
                if (calcResult.isNaN() || calcResult.isInfinite()) {
                    Log.w(TAG, "Invalid arithmetic result for: ${matchResult.value}")
                    matchResult.value // Возвращаем оригинал, если результат некорректный
                } else {
                    // Форматируем результат (убираем лишние нули)
                    val resultStr = if (calcResult == calcResult.toLong().toDouble()) {
                        calcResult.toLong().toString()
                    } else {
                        String.format("%.2f", calcResult).trimEnd('0').trimEnd('.')
                    }
                    
                    Log.d(TAG, "Arithmetic solved: ${matchResult.value} = $resultStr")
                    "${matchResult.value} (=$resultStr)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error solving arithmetic: ${e.message}")
                matchResult.value // Возвращаем оригинал при ошибке
            }
        }
        
        return result
    }
    
    /**
     * Главная функция обработки текста
     * Применяет голосовые команды, затем вычисляет математику
     */
    fun processText(text: String): String {
        if (text.isBlank()) {
            Log.w(TAG, "Empty text provided to processText")
            return text
        }
        
        Log.d(TAG, "Processing text: '${text.take(30)}...'")
        
        val withCommands = applyVoiceCommands(text)
        val withMath = solveArithmetic(withCommands)
        
        Log.d(TAG, "Text processed: '${withMath.take(30)}...'")
        
        return withMath
    }
}