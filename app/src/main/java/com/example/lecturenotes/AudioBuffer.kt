package com.example.lecturenotes

object AudioBuffer {
    private val chunks = mutableListOf<ByteArray>()
    private val lock = Any()

    fun addChunk(data: ByteArray) {
        synchronized(lock) {
            val copy = ByteArray(data.size)
            System.arraycopy(data, 0, copy, 0, data.size)
            chunks.add(copy)
        }
    }

    fun getAllData(): ByteArray {
        synchronized(lock) {
            if (chunks.isEmpty()) return ByteArray(0)
            val totalSize = chunks.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        }
    }

    fun clear() {
        synchronized(lock) {
            chunks.clear()
        }
    }
}