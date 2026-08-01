================================================================================
МОДУЛЬ: TextProcessor
ПАКЕТ: com.example.lecturenotes.textprocessor
================================================================================

НАЗНАЧЕНИЕ
----------
Постобработка сырого текста из WhisperTranscriber:
1. Замена голосовых команд на символы ("плюс" → "+")
2. Вычисление арифметических выражений ("2 + 2" → "2 + 2 (=4)")
3. Нормализация пробелов и пунктуации

МЕСТО В PIPELINE
----------------
WhisperTranscriber.processChunk()
    → TextProcessor.process()
        → Сохранение в Room (RecordingDao)
        → Отображение в UI (StreamingViewModel)

USB-ПРИНЦИП
-----------
Интерфейс TextProcessor — контракт.
DefaultTextProcessor — реализация по умолчанию.
ViewModel получает TextProcessor через конструктор (DI).
Можно подменить на:
  - LLM-структуратор (GPT/Claude API)
  - Пустую заглушку (NoOpTextProcessor) для отладки
  - Тестовый мок

ФАЙЛЫ МОДУЛЯ
------------
TextProcessor.kt          — интерфейс (контракт)
DefaultTextProcessor.kt   — реализация + встроенный ArithmeticParser
README.txt                — этот файл

ИНТЕГРАЦИЯ В STREAMINGVIEWMODEL
--------------------------------
class StreamingViewModel(
    private val textProcessor: TextProcessor = DefaultTextProcessor()
) : ViewModel() {

    fun onTranscriptionReceived(raw: String) {
        val processed = textProcessor.process(raw)
        _uiState.update { it.copy(currentText = processed) }
    }
}

КОНФИГУРАЦИЯ
------------
// Кастомные команды:
val processor = DefaultTextProcessor(
    commands = mapOf("плюс" to "+", "минус" to "-"),
    enableArithmetic = false  // отключить математику
)

// Стандартные:
val processor = DefaultTextProcessor()

ПОТОКОБЕЗОПАСНОСТЬ
------------------
Все поля immutable. State между вызовами не хранится.
Безопасно вызывать из любого потока (IO, Main, Default).

ЗАВИСИМОСТИ
-----------
Внешние: НЕТ. Чистый Kotlin, без Android SDK.
Внутренние: НЕТ. Модуль полностью автономный.

ТЕСТИРОВАНИЕ
------------
Так как нет android.util.Log и Android-зависимостей,
тесты пишутся как обычные JUnit-тесты без Robolectric:

@Test
fun `voice commands replace cyrillic phrases`() {
    val processor = DefaultTextProcessor()
    assertEquals("2 + 2", processor.applyVoiceCommands("2 плюс 2"))
}

@Test
fun `arithmetic solves inline expressions`() {
    val processor = DefaultTextProcessor()
    assertEquals("2 + 2 (=4)", processor.solveArithmetic("2 + 2"))
}

ИЗВЕСТНЫЕ ОГРАНИЧЕНИЯ
---------------------
1. Голосовые команды матчатся по точному совпадению фразы.
   "прибавь пять" НЕ заменится — нужен явный словарь.
2. Арифметика ищет паттерн "число оператор число".
   Одиночное число "42" не трогается.
3. Вложенные скобки поддерживаются, но ^ (степень) и √ (корень)
   в парсере НЕ вычисляются — только заменяются как символы.

ВЕРСИЯ: 2.0
ДАТА: 2026-08-01
================================================================================