package com.piagent.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.piagent.agent.Level
import com.piagent.agent.ToolLoopDetector
import com.piagent.browser.BrowserActionInput
import com.piagent.browser.BrowserTabPool
import com.piagent.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.piagent.data.BPETokenizer
import com.piagent.data.ContextOffload
import com.piagent.data.ContextPolicy
import com.piagent.logging.AppLogger
import com.piagent.data.FileMentionIndex
import com.piagent.data.db.CompactMarkerEntity
import com.piagent.data.model.AgentContentPart
import com.piagent.data.model.AgentToolDefinition
import com.piagent.data.model.LLMMessage
import com.piagent.data.model.LLMModel
import com.piagent.data.model.LLMStreamChunk
import com.piagent.data.model.LLMUsage
import com.piagent.data.model.ModelGroup
import com.piagent.data.model.ThinkingLevel
import com.piagent.R
import com.piagent.data.repository.ChatRepository
import com.piagent.data.repository.MemoryRepository
import com.piagent.data.repository.ProviderRepository
import com.piagent.provider.ImageBudget
import com.piagent.provider.LLMProvider
import com.piagent.provider.ProviderFactory
import com.piagent.sandbox.ExecutionCoordinator
import com.piagent.terminal.MinisOpenUrlBroker
import com.piagent.terminal.MinisUrlMarker
import com.piagent.tools.AgentTools
import com.piagent.tools.FileEditTool
import com.piagent.tools.FileReadTool
import com.piagent.tools.FileWriteTool
import com.piagent.tools.MemoryTools
import com.piagent.tools.ReadImageTool
import com.piagent.tools.ToolExecutionResult
import com.piagent.offload.OffloadPermissionManager
import com.piagent.service.SessionActivityTracker
import com.piagent.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
