package com.kar.reader.data.repo

import com.kar.reader.data.db.NotebookDao
import com.kar.reader.data.db.NotebookEntity
import com.kar.reader.data.db.NotebookPageEntity
import com.kar.reader.data.db.NotebookStrokeEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class PageTemplate(val label: String) { BLANK("Blank"), LINED("Lined"), GRID("Grid"), DOTTED("Dotted") }

@Singleton
class NotebookRepository @Inject constructor(private val dao: NotebookDao) {

    fun observeNotebooks(profileId: String): Flow<List<NotebookEntity>> = dao.observeForProfile(profileId)

    fun observeNotebook(id: String): Flow<NotebookEntity?> = dao.observe(id)

    fun observePages(notebookId: String): Flow<List<NotebookPageEntity>> = dao.observePages(notebookId)

    fun observeStrokes(pageId: String): Flow<List<NotebookStrokeEntity>> = dao.observeStrokes(pageId)

    suspend fun create(profileId: String, title: String, template: PageTemplate): NotebookEntity {
        val now = System.currentTimeMillis()
        val notebook = NotebookEntity(UUID.randomUUID().toString(), profileId, title.trim().ifEmpty { "Notebook" }.take(80), now)
        dao.upsert(notebook)
        dao.upsertPage(NotebookPageEntity(UUID.randomUUID().toString(), notebook.id, 0, template.name, now))
        return notebook
    }

    suspend fun rename(notebook: NotebookEntity, title: String) =
        dao.upsert(notebook.copy(title = title.trim().ifEmpty { notebook.title }.take(80), modifiedAt = System.currentTimeMillis()))

    suspend fun addPage(notebookId: String, template: PageTemplate): NotebookPageEntity {
        val index = dao.getPages(notebookId).maxOfOrNull { it.pageIndex + 1 } ?: 0
        val page = NotebookPageEntity(UUID.randomUUID().toString(), notebookId, index, template.name, System.currentTimeMillis())
        dao.upsertPage(page)
        return page
    }

    suspend fun setTemplate(page: NotebookPageEntity, template: PageTemplate) =
        dao.upsertPage(page.copy(template = template.name, modifiedAt = System.currentTimeMillis()))

    suspend fun saveStroke(stroke: NotebookStrokeEntity) = dao.upsertStroke(stroke.copy(deletedAt = null, modifiedAt = System.currentTimeMillis()))

    suspend fun deleteStrokes(ids: List<String>) { if (ids.isNotEmpty()) dao.softDeleteStrokes(ids, System.currentTimeMillis()) }

    suspend fun restoreStrokes(ids: List<String>) { if (ids.isNotEmpty()) dao.restoreStrokes(ids, System.currentTimeMillis()) }

    suspend fun delete(id: String) = dao.softDelete(id, System.currentTimeMillis())

    suspend fun restore(id: String) = dao.restore(id, System.currentTimeMillis())
}
