# Модуль Data

## Описание
Слой работы с данными: Room Database, DAO, модели данных.

## Структура
data/
├── Recording.kt # Entity для Room
├── RecordingDao.kt # DAO для работы с БД
├── AppDatabase.kt # Room Database singleton
└── MODULE.md # Документация модуля

## Контракты

### Recording (Entity)
```kotlin
data class Recording(
    val id: Long = 0,
    val title: String = "",
    val transcription: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val audioPath: String? = null
)
@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<Recording>>
    
    @Insert
    suspend fun insert(recording: Recording)
    
    @Update
    suspend fun update(recording: Recording)
    
    @Delete
    suspend fun delete(recording: Recording)
    
    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
AppDatabase
Версия: 2
Миграция 1→2: добавлена колонка audioPath TEXT DEFAULT NULL
Singleton через getDatabase(context)
Зависимости
Room (runtime, compiler)
Kotlin Coroutines
Статус
✅ Завершён
Добавлено поле audioPath в Recording
Создана миграция 1→2 в AppDatabase
Интеграция с MainActivity выполнена

---

