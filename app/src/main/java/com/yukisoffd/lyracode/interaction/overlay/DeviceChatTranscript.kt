package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.yukisoffd.lyracode.LyraCodeTheme
import com.yukisoffd.lyracode.MessageCard
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.session.DeviceChatState

/** Reuses the application's message, reasoning, tool and Markdown components in the overlay. */
internal class DeviceChatTranscript(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private var chat by mutableStateOf(DeviceChatState())

    init {
        // The overlay is a separate process and may never create MainActivity.
        com.yukisoffd.lyracode.AppStrings.initialize(context)
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        addView(ComposeView(context).apply {
            setContent {
                val settings = AppSettings(context)
                val dark = when (settings.themeMode) {
                    AppSettings.THEME_DARK -> true
                    AppSettings.THEME_LIGHT -> false
                    else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                }
                val fontScale = when (settings.fontScaleMode) {
                    AppSettings.FONT_SCALE_SMALL -> .9f
                    AppSettings.FONT_SCALE_NORMAL -> 1f
                    AppSettings.FONT_SCALE_LARGE -> 1.12f
                    AppSettings.FONT_SCALE_EXTRA_LARGE -> 1.25f
                    AppSettings.FONT_SCALE_CUSTOM -> settings.customFontScale
                    else -> resources.configuration.fontScale
                }.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
                LyraCodeTheme(dark, settings.dynamicColorEnabled, fontScale, settings, 0, 0) {
                    val rows = com.yukisoffd.lyracode.chatRenderItems(chat.messages.map { message ->
                        ChatRecord(id = message.id, role = message.role, content = message.text, thinking = message.thinking,
                            toolName = message.toolName, createdAt = message.createdAt)
                    }, isStreaming = chat.running, collapseStreamingProse = true)
                    val list = rememberLazyListState()
                    LaunchedEffect(rows.size) {
                        if (rows.isNotEmpty() && !list.canScrollForward && !list.isScrollInProgress)
                            list.scrollToItem(rows.lastIndex)
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rows.isEmpty()) item {
                            Text("输入任务即可开始。安全操作自动执行，敏感操作会说明拦截原因。")
                        }
                        items(rows, key = { it.key }) { row ->
                            if (row.process.isNotEmpty()) {
                                com.yukisoffd.lyracode.AgentProcessSummary(row.process, selectionResetKey = 0,
                                    active = chat.running && row.key == rows.lastOrNull()?.key,
                                    startedAtOverride = row.processStartedAt, finishedAtOverride = row.processFinishedAt,
                                    inlineToolDetails = true)
                            }
                            row.message?.let { MessageCard(it, inlineToolDetails = true) }
                        }
                    }
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun render(value: DeviceChatState) { chat = value }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isAttachedToWindow) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        // WindowRecomposer resolves its owner at the window root, above this nested transcript.
        if (rootView.findViewTreeLifecycleOwner() == null) rootView.setViewTreeLifecycleOwner(this)
        if (rootView.findViewTreeSavedStateRegistryOwner() == null) rootView.setViewTreeSavedStateRegistryOwner(this)
        super.onAttachedToWindow()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}
