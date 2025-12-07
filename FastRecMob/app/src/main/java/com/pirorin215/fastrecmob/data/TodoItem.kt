package com.pirorin215.fastrecmob.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isCompleted: MutableState<Boolean> = mutableStateOf(false),
    val notes: String? = null,
    val updated: String? = null, // RFC 3339 timestamp
    val position: String? = null
) {
    companion object {
        val Saver: Saver<TodoItem, Any> = listSaver(
            save = { listOf(it.id, it.text, it.isCompleted.value, it.notes, it.updated, it.position) },
            restore = {
                TodoItem(
                    id = it[0] as String,
                    text = it[1] as String,
                    isCompleted = mutableStateOf(it[2] as Boolean),
                    notes = it[3] as String?,
                    updated = it[4] as String?,
                    position = it[5] as String?
                )
            }
        )
    }
}
