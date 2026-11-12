package com.yukisoffd.lyracode

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Renders the queue dock above a composer-like surface and captures PR screenshots. */
class ComposerQueueDockScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val queued = ComposerQueueRowUi(
        id = "queued-1",
        excerpt = "Add the source list to the answer",
        attachmentCount = 0,
        state = ComposerQueueItemState.QUEUED,
    )
    private val parked = ComposerQueueRowUi(
        id = "parked-1",
        excerpt = "Then compare the two studies",
        attachmentCount = 0,
        state = ComposerQueueItemState.PARKED,
    )

    @Test
    fun captureDockStates() {
        val settings = AppSettings(context)
        var runActive by mutableStateOf(true)
        var items by mutableStateOf(listOf(queued, parked))
        compose.setContent {
            LyraCodeTheme(
                darkMode = false,
                dynamicColor = false,
                fontScale = 1f,
                settings = settings,
                settingsRevision = 0,
                dynamicColorRevision = 0,
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Card(
                            Modifier.fillMaxWidth().testTag("composer-card"),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ComposerQueueDock(
                                    conversationId = 7L,
                                    items = items,
                                    runActive = runActive,
                                    steerAccepted = true,
                                    onRemove = {},
                                    onSendParked = {},
                                    onSteer = {},
                                )
                                FakeComposerRow()
                            }
                        }
                    }
                }
            }
        }

        screenshot("queue-dock-collapsed.png")
        compose.onNodeWithContentDescription(context.getString(R.string.composer_queue_dock_expand))
            .performClick()
        screenshot("queue-dock-expanded.png")

        compose.runOnIdle {
            runActive = false
            items = listOf(parked)
        }
        compose.waitForIdle()
        screenshot("queue-dock-parked.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(300)
        val bitmap = compose.onNodeWithTag("composer-card").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

@androidx.compose.runtime.Composable
private fun FakeComposerRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Message",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
