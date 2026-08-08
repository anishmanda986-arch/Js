package com.jarvis.ai.provider

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface AIProvider {
    val isInitialized: Boolean
    val loadedModelUri: Uri?
    suspend fun initialize(uri: Uri, contextSize: Int): Boolean
    fun generateResponse(prompt: String): Flow<String>
    fun stopGeneration()
    fun unloadModel()
}
