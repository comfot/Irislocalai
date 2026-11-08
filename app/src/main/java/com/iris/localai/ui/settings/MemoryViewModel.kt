package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.iris.localai.IrisApp
import com.iris.localai.memory.DiaryEntry
import com.iris.localai.memory.KnowledgeDocument
import com.iris.localai.memory.MemoryItem

class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val diary = graph.memory.diary
    val memories = graph.memory.memories
    val knowledge = graph.memory.knowledge

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
}
