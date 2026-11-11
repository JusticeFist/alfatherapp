package app.hermesandroid.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDetailTest {
    /** A completed run and its file, shaped like `GET /api/plugins/kanban/tasks/{id}` (asdict(Run), _attachment_dict). */
    @Test
    fun runSummaryAndAttachment() {
        val run = TasksState.runOf(Json.parseToJsonElement(
            """{"id":2,"profile":"default","status":"done","outcome":"completed","summary":"Top 5 batidos","error":null,"ended_at":1792706287,"metadata":{"top5":[]}}""",
        ).jsonObject)
        assertEquals(KRun("default", "completed", "Top 5 batidos", null, 1792706287L), run)

        val att = TasksState.attachmentOf(Json.parseToJsonElement(
            """{"id":1,"task_id":"t_d1b867eb","filename":"ofertas_batidos.txt","content_type":null,"size":1910,"stored_path":"/home/a/.hermes/kanban/attachments/t_d1b867eb/ofertas_batidos.txt"}""",
        ).jsonObject)
        assertEquals(KAttachment(1, "ofertas_batidos.txt", "/home/a/.hermes/kanban/attachments/t_d1b867eb/ofertas_batidos.txt", 1910), att)
    }

    @Test
    fun blankSummaryAndMissingId() {
        assertNull(TasksState.runOf(Json.parseToJsonElement("""{"status":"running","summary":""}""").jsonObject).summary)
        assertNull(TasksState.attachmentOf(Json.parseToJsonElement("""{"filename":"x.txt"}""").jsonObject))
    }
}
