package com.yukisoffd.lyracode

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.Conversation
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the drawer sections expanded and collapsed, then captures PR screenshots. */
class AppDrawerSectionScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureSectionStates() {
        val settings = AppSettings(context)
        var functionsExpanded by mutableStateOf(true)
        var historyExpanded by mutableStateOf(true)
        compose.setContent {
            LyraCodeTheme(
                darkMode = false,
                dynamicColor = false,
                fontScale = 1f,
                settings = settings,
                settingsRevision = 0,
                dynamicColorRevision = 0,
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(
                        Modifier
                            .width(360.dp)
                            .testTag("drawer-shot")
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DrawerSectionHeader(
                            title = context.getString(R.string.label_functions),
                            expanded = functionsExpanded,
                            onToggle = { functionsExpanded = !functionsExpanded },
                        )
                        if (functionsExpanded) {
                            FunctionRow(Icons.Default.ChatBubble, "Chat", selected = true)
                            FunctionRow(Icons.Default.FolderOpen, "Skills")
                            FunctionRow(Icons.Default.Terminal, "Files")
                            Spacer(Modifier.height(6.dp))
                        }
                        DrawerSectionHeader(
                            title = context.getString(R.string.label_history_sessions),
                            expanded = historyExpanded,
                            onToggle = { historyExpanded = !historyExpanded },
                        )
                        if (historyExpanded) {
                            SampleConversation(1L, "Refactor the parser", pinned = true)
                            SampleConversation(2L, "Plan the release")
                            SampleConversation(3L, "Drawer collapse bug")
                        }
                    }
                }
            }
        }

        screenshot("drawer-sections-expanded.png")
        compose.runOnIdle {
            functionsExpanded = false
            historyExpanded = false
        }
        compose.waitForIdle()
        screenshot("drawer-sections-collapsed.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(300)
        val bitmap = compose.onNodeWithTag("drawer-shot").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

@Composable
private fun FunctionRow(icon: ImageVector, title: String, selected: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)
                } else {
                    Color.Transparent
                },
            )
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Box(Modifier.width(50.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            title,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SampleConversation(id: Long, title: String, pinned: Boolean = false) {
    KimiConversationRow(
        conversation = Conversation(
            id = id,
            title = title,
            status = "idle",
            profileId = "profile",
            model = "deepseek-chat",
            createdAt = 0L,
            updatedAt = 0L,
            pinnedAt = if (pinned) 1L else 0L,
            archivedAt = 0L,
        ),
        selected = false,
        multiSelected = false,
        selectionMode = false,
        onSelect = {},
        onLongPress = {},
    )
}
