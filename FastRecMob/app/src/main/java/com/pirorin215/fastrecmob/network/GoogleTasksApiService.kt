package com.pirorin215.fastrecmob.network

import com.pirorin215.fastrecmob.data.Task
import com.pirorin215.fastrecmob.data.TaskList
import com.pirorin215.fastrecmob.data.TaskListsResponse
import com.pirorin215.fastrecmob.data.TasksResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface GoogleTasksApiService {
    @GET("tasks/v1/users/@me/lists")
    suspend fun getTaskLists(): TaskListsResponse

    @POST("tasks/v1/users/@me/lists")
    suspend fun createTaskList(@Body taskList: TaskList): TaskList

    @GET("tasks/v1/lists/{taskListId}/tasks")
    suspend fun getTasks(@Path("taskListId") taskListId: String): TasksResponse

    @GET("tasks/v1/lists/{taskListId}/tasks/{taskId}")
    suspend fun getTask(
        @Path("taskListId") taskListId: String,
        @Path("taskId") taskId: String
    ): Task

    @POST("tasks/v1/lists/{taskListId}/tasks")
    suspend fun createTask(@Path("taskListId") taskListId: String, @Body task: Task): Task

    @PUT("tasks/v1/lists/{taskListId}/tasks/{taskId}")
    suspend fun updateTask(
        @Path("taskListId") taskListId: String,
        @Path("taskId") taskId: String,
        @Body task: Task
    ): Task
}
