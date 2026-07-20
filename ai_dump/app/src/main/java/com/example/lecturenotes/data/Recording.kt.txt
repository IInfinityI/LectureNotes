package com.example.lecturenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val transcription: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0
)
