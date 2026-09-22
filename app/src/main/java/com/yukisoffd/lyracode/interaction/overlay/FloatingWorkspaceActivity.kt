package com.yukisoffd.lyracode.interaction.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.AppStrings
import com.yukisoffd.lyracode.localizedContext
import com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import com.yukisoffd.lyracode.workspace.rememberWorkspacePicker
import org.json.JSONObject

/** Owns workspace selection in the main process. */
class FloatingWorkspaceActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.localizedContext(AppSettings(newBase).languageMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.initialize(this)
        setContent {
            MaterialTheme {
                val picker = rememberWorkspacePicker(onDismiss = { finish() }) { uri ->
                    val workspace = WorkspaceManager(this, AppSettings(this)).persistWorkspace(uri)
                    DeviceTaskCoordinator.configure(this, JSONObject().put("workspace", workspace).toString())
                    finish()
                }
                LaunchedEffect(Unit) { if (savedInstanceState == null) picker() }
            }
        }
    }
}
