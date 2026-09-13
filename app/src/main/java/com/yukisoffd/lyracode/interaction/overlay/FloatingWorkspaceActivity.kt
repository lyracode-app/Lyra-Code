package com.yukisoffd.lyracode.interaction.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator
import org.json.JSONObject

/** Owns the SAF result in the main process; the overlay never receives account credentials. */
class FloatingWorkspaceActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                DeviceTaskCoordinator.configure(this, JSONObject().put("workspace", uri.toString()).toString())
            }.onFailure {
                android.widget.Toast.makeText(this, it.message.orEmpty(), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(null)
    }
}
