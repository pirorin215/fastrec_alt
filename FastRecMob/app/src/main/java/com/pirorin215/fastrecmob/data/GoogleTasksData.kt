package com.pirorin215.fastrecmob.data

import kotlinx.serialization.Serializable

// Data classes for JSON parsing from Google Tasks API
@Serializable
data class TaskListsResponse(val items: List<TaskList> = emptyList())

@Serializable
data class TaskList(val id: String, val title: String)

@Serializable
data class TasksResponse(val items: List<Task>? = null)

@Serializable
data class Task(
    val id: String? = null,
    val title: String? = null,
    val status: String? = null,
    val notes: String? = null,
    val updated: String? = null, // RFC 3339 timestamp
    val position: String? = null,
    val due: String? = null, // RFC 3339 timestamp
    val webViewLink: String? = null
)
