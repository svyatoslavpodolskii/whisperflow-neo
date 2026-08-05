package com.pimax.whisperflow

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TasksApi.TaskResult data class and TasksApi constants.
 */
class TasksApiTest {

    @Test
    fun `TaskResult success with all fields`() {
        val result = TasksApi.TaskResult(
            success = true,
            taskId = 42,
            categoryName = "Work",
            error = null
        )
        assertTrue(result.success)
        assertEquals(42, result.taskId)
        assertEquals("Work", result.categoryName)
        assertNull(result.error)
    }

    @Test
    fun `TaskResult failure has error message`() {
        val result = TasksApi.TaskResult(
            success = false,
            error = "HTTP 503"
        )
        assertFalse(result.success)
        assertNull(result.taskId)
        assertEquals("HTTP 503", result.error)
    }

    @Test
    fun `TaskResult defaults taskId and categoryName to null`() {
        val result = TasksApi.TaskResult(success = true)
        assertNull(result.taskId)
        assertNull(result.categoryName)
    }

    @Test
    fun `DEFAULT_TASKS_URL is empty — no hardcoded IP`() {
        assertEquals("", TasksApi.DEFAULT_TASKS_URL)
    }
}
