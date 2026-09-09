package com.yukisoffd.lyracode.interaction.pet

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.yukisoffd.lyracode.LyraCodeTheme
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.localizedContext
import com.yukisoffd.lyracode.rememberPredictiveBackGestureState
import com.yukisoffd.lyracode.predictiveBackTransform
import androidx.activity.compose.BackHandler
import com.yukisoffd.lyracode.data.AppSettings
import org.json.JSONObject
import java.util.Locale

class DesktopPetSettingsActivity : ComponentActivity() {
    private var appearanceRevision by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); appearanceRevision++ }
    private var revision by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf("")
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> transfer(uri, true) } }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> transfer(uri, false) } }
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.localizedContext(AppSettings(newBase).languageMode))
    }
    private var editing by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.yukisoffd.lyracode.AppStrings.initialize(this)
        enableEdgeToEdge()
        setContent {
            val settings = remember(appearanceRevision) { AppSettings(this) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) { AppSettings.THEME_DARK -> true; AppSettings.THEME_LIGHT -> false; else -> systemDark }
            val scale = when (settings.fontScaleMode) {
                AppSettings.FONT_SCALE_SMALL -> .9f
                AppSettings.FONT_SCALE_NORMAL -> 1f
                AppSettings.FONT_SCALE_LARGE -> 1.12f
                AppSettings.FONT_SCALE_EXTRA_LARGE -> 1.25f
                AppSettings.FONT_SCALE_CUSTOM -> settings.customFontScale
                else -> resources.configuration.fontScale
            }.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
            LyraCodeTheme(dark, settings.dynamicColorEnabled, scale, settings, 0, 0) { SettingsPage(settings.predictiveBackEnabled) }
        }
    }
    private fun save(key: String, value: Any) {
        DevicePetStore.saveOptions(this, DevicePetStore.options(this).put(key, value)); revision++
    }
    private fun saveControl(key: String, value: Any) {
        val options = DevicePetStore.options(this)
        save("controls", (options.optJSONObject("controls") ?: JSONObject()).put(key, value))
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun SettingsPage(predictiveEnabled: Boolean) {
        val manifest = remember(revision) { DevicePetStore.load(this) }
        val options = remember(revision) { DevicePetStore.effectiveOptions(this, manifest) }
        val catalog = remember(revision) { DevicePetStore.catalog(this) }
        val active = remember(revision) { DevicePetStore.activeKey(this) }
        var picker by remember { mutableStateOf(false) }
        var removing by remember { mutableStateOf<DevicePetStore.PetEntry?>(null) }
        val backState = rememberPredictiveBackGestureState(enabled = predictiveEnabled && !picker && removing == null && !editing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        BackHandler(enabled = !predictiveEnabled && !picker && removing == null && !editing) { finish() }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(notice) { if (notice.isNotEmpty()) { snackbar.showSnackbar(notice); notice = "" } }
        Scaffold(
            modifier = Modifier.predictiveBackTransform(backState),
            topBar = { TopAppBar(title = { Text(getString(R.string.pet_settings_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, getString(R.string.cd_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Pets, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column(Modifier.weight(1f)) {
                                Text(manifest.getString("name"), style = MaterialTheme.typography.titleLarge)
                                Text(getString(R.string.pet_available_count, catalog.size), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(getString(R.string.pet_usage_hint), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { picker = true }, enabled = !busy, modifier = Modifier.testTag("pet-library")) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(getString(R.string.pet_switch))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(getString(R.string.pet_import))
                    }
                    OutlinedButton(onClick = { exporter.launch("desktop-pet.zip") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(getString(R.string.pet_export))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Section(getString(R.string.pet_appearance)) {
                    SliderSetting(getString(R.string.pet_size), options.optDouble("size", 72.0), 40.0, 240.0, { "${it.toInt()} dp" }) { save("size", it) }
                    ToggleSetting(getString(R.string.pet_auto_dock), getString(R.string.pet_auto_dock_hint), options.optBoolean("autoDock", true)) { save("autoDock", it) }
                    if (options.optBoolean("autoDock", true)) {
                        SliderSetting(getString(R.string.pet_dock_delay), options.optDouble("dockDelayMs", 3500.0), 500.0, 60000.0, { getString(R.string.pet_seconds, decimal(it / 1000)) }) { save("dockDelayMs", it) }
                        SliderSetting(getString(R.string.pet_hide_fraction), options.optDouble("dockFraction", .5), 0.0, .8, ::percent) { save("dockFraction", it) }
                        SliderSetting(getString(R.string.pet_dock_opacity), options.optDouble("dockOpacity", .42), .1, 1.0, ::percent) { save("dockOpacity", it) }
                    }
                    SliderSetting(getString(R.string.pet_active_opacity), options.optDouble("activeOpacity", 1.0), .2, 1.0, ::percent) { save("activeOpacity", it) }
                }
                val controls = manifest.optJSONArray("controls")
                val values = DevicePetStore.controls(manifest, options)
                if (controls != null && controls.length() > 0) key(active) {
                    Section(getString(R.string.pet_customization)) {
                        for (i in 0 until controls.length()) {
                            val control = controls.getJSONObject(i); val name = control.getString("key"); val title = control.getString("label")
                            when (control.getString("type")) {
                                "range" -> SliderSetting(title, values.getDouble(name), control.getDouble("min"), control.getDouble("max"), ::decimal) { saveControl(name, it) }
                                "boolean" -> ToggleSetting(title, null, values.getBoolean(name)) { saveControl(name, it) }
                                else -> ChoiceSetting(control, values.getString(name)) { saveControl(name, it) }
                            }
                        }
                    }
                }
                Section(getString(R.string.pet_sound)) {
                    ToggleSetting(getString(R.string.pet_sound_allow), getString(R.string.pet_sound_hint), options.optBoolean("soundEnabled", false)) { save("soundEnabled", it) }
                    if (options.optBoolean("soundEnabled", false)) SliderSetting(getString(R.string.pet_volume), options.optDouble("volume", .5), 0.0, 1.0, ::percent) { save("volume", it) }
                }
                Section(getString(R.string.pet_vision)) {
                    ToggleSetting(getString(R.string.pet_vision_supported), getString(R.string.pet_vision_hint), options.optBoolean("current_model_vision")) { save("current_model_vision", it) }
                    Text(getString(R.string.pet_vision_detail), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(getString(R.string.pet_library_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
        if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text(getString(R.string.pet_choose)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    catalog.forEach { pet ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            runCatching { DevicePetStore.select(this@DesktopPetSettingsActivity, pet.key) }.onSuccess { revision++; picker = false }.onFailure { notice = getString(R.string.pet_switch_failed) }
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pet.key == active, onClick = null)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(pet.name, style = MaterialTheme.typography.bodyLarge)
                                Text(listOf(pet.author, pet.version).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (pet.key != "builtin") IconButton(onClick = { removing = pet }, enabled = !busy) {
                                Icon(Icons.Default.DeleteOutline, getString(R.string.pet_remove_named, pet.name))
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { picker = false }) { Text(getString(R.string.action_done)) } })
        removing?.let { pet -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(getString(R.string.pet_remove_title)) },
            text = { Text(getString(R.string.pet_remove_body, pet.name)) },
            confirmButton = { TextButton(onClick = {
                runCatching { DevicePetStore.remove(this@DesktopPetSettingsActivity, pet.key) }.onFailure { notice = getString(R.string.pet_remove_failed) }
                removing = null; revision++
            }) { Text(getString(R.string.pet_remove)) } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(getString(R.string.action_cancel)) } }) }
    }
    @Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
            }
        }
    }
    @Composable private fun ToggleSetting(title: String, detail: String?, checked: Boolean, change: (Boolean) -> Unit) {
        ListItem(headlineContent = { Text(title) }, supportingContent = detail?.let { { Text(it) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = change, enabled = !busy) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.clickable(enabled = !busy) { change(!checked) })
    }
    @Composable private fun SliderSetting(title: String, value: Double, min: Double, max: Double, format: (Double) -> String, change: (Double) -> Unit) {
        var draft by remember(value) { mutableFloatStateOf(value.coerceIn(min, max).toFloat()) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(format(draft.toDouble()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            }
            Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { change(draft.toDouble()) },
                valueRange = min.toFloat()..max.toFloat(), enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
    }
    @Composable private fun ChoiceSetting(schema: JSONObject, value: String, change: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }; var draft by remember(value) { mutableStateOf(value) }
        DisposableEffect(open) { editing = open; onDispose { editing = false } }
        val color = schema.getString("type") == "color"
        val select = schema.getString("type") == "select"
        val valid = !color || Regex("#[0-9a-fA-F]{6}").matches(draft)
        ListItem(headlineContent = { Text(schema.getString("label")) }, supportingContent = { Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingContent = { if (color) Surface(Modifier.size(28.dp), color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(value)), shape = RoundedCornerShape(50)) {} else Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.clickable(enabled = !busy) { draft = value; open = true })
        if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(schema.getString("label")) }, text = {
            if (select) Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val choices = schema.getJSONArray("options")
                for (i in 0 until choices.length()) { val item = choices.getString(i)
                    Row(Modifier.fillMaxWidth().clickable { draft = item }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(draft == item, onClick = { draft = item }); Text(item, Modifier.padding(start = 8.dp))
                    }
                }
            } else OutlinedTextField(value = draft, onValueChange = { draft = it.take(if (color) 7 else 200) }, singleLine = color,
                isError = !valid, supportingText = if (color) { { Text("#RRGGBB") } } else null)
        }, confirmButton = { TextButton(enabled = valid, onClick = { change(draft); open = false }) { Text(getString(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(getString(R.string.action_cancel)) } })
    }
    private fun decimal(value: Double) = if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(resources.configuration.locales[0], "%.1f", value)
    private fun percent(value: Double) = "${(value * 100).toInt()}%"
    private fun transfer(uri: Uri, importing: Boolean) {
        if (busy) return
        busy = true
        Thread {
            val result = runCatching {
                if (importing) contentResolver.openInputStream(uri)!!.buffered().use { input ->
                    input.mark(4); val first = input.read(); val second = input.read(); input.reset()
                    if (first == 'P'.code && second == 'K'.code) DevicePetStore.installZip(this, input)
                    else {
                        val bytes = input.readNBytes(DevicePetStore.MAX_BYTES + 1)
                        require(bytes.size <= DevicePetStore.MAX_BYTES) { "旧 JSON 文件超过 2 MiB" }
                        DevicePetStore.install(this, bytes.toString(Charsets.UTF_8))
                    }
                } else DevicePetStore.exportZip(this, contentResolver.openOutputStream(uri, "wt")!!)
            }
            runOnUiThread {
                busy = false; revision++
                notice = result.fold({ if (importing) getString(R.string.pet_imported) else getString(R.string.pet_exported) }, { getString(R.string.pet_operation_failed) })
            }
        }.start()
    }
}
