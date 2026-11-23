package com.iris.moew.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.moew.IrisApp
import com.iris.moew.memory.DiaryEntry
import com.iris.moew.memory.KnowledgeDocument
import com.iris.moew.memory.MemoryItem
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val diary = graph.memory.diary
    val memories = graph.memory.memories
    val knowledge = graph.memory.knowledge
    val links = graph.memory.links

    fun addDiary(entry: DiaryEntry) = graph.memory.addDiary(entry)

    fun updateDiary(entry: DiaryEntry) = graph.memory.updateDiary(entry)

    fun deleteDiary(id: String) = graph.memory.deleteDiary(id)

    fun addMemory(item: MemoryItem) = graph.memory.addMemory(item)

    fun updateMemory(item: MemoryItem) = graph.memory.updateMemory(item)

    fun deleteMemory(id: String) = graph.memory.deleteMemory(id)

    fun addKnowledge(document: KnowledgeDocument) = graph.memory.addKnowledge(document)

    fun updateKnowledge(document: KnowledgeDocument) =
        graph.memory.updateKnowledge(document)

    fun deleteKnowledge(id: String) = graph.memory.deleteKnowledge(id)

    fun importKnowledge(text: String, fileName: String) =
        graph.memory.importKnowledge(text, fileName)

    fun digestText(): String = graph.memory.digestText()

    fun embeddingStatus(): String = graph.memory.embeddingStatus

    fun folders(): List<String> = graph.memory.memoryFolders()

    fun filterMemories(query: String, folder: String?): List<MemoryItem> =
        graph.memory.filterMemories(query, folder)

    fun memoryGraph(folder: String?): com.iris.moew.memory.MemoryGraphData =
        graph.memory.memoryGraph(folder)

    fun autoLinkAll(): Int = graph.memory.autoLinkAll()

    fun linkMemories(
        sourceId: String,
        targetId: String,
        type: String,
        weight: Float,
        description: String,
    ): com.iris.moew.memory.MemoryLink? =
        graph.memory.linkMemories(sourceId, targetId, type, weight, description)

    fun deleteLink(id: String) = graph.memory.deleteLink(id)

    fun generateMissingDiaries(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = graph.memory.generateMissingDiaries()
            onResult(count)
        }
    }

    fun autoCaptureRecent(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            var total = 0
            graph.conversations.conversations.value
                .sortedByDescending { it.updatedAt }
                .take(5)
                .forEach { conversation ->
                    total += graph.memoryAutoCapture.capture(conversation.id, force = true)
                }
            onResult(total)
        }
    }
}
