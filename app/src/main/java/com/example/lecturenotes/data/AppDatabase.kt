package com.example.lecturenotes.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Recording::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun recordingDao(): RecordingDao
    
    companion object {
        private const val TAG = "AppDatabase"
        private const val DATABASE_NAME = "lecture_notes.db"
        
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Получение экземпляра базы данных (Singleton)
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration() // Временное решение: пересоздаёт БД при изменении версии
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            Log.i(TAG, "Database created successfully")
                        }
                        
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onOpen(db)
                            Log.i(TAG, "Database opened successfully")
                        }
                    })
                    .build()
                
                INSTANCE = instance
                Log.i(TAG, "Database instance created: $DATABASE_NAME")
                instance
            }
        }
        
        /**
         * Удаление базы данных (для сброса)
         */
        fun deleteDatabase(context: Context) {
            try {
                context.deleteDatabase(DATABASE_NAME)
                INSTANCE = null
                Log.i(TAG, "Database deleted: $DATABASE_NAME")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting database: ${e.message}", e)
            }
        }
    }
}