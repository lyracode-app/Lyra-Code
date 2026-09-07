package com.yukisoffd.lyracode.interaction.pet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import org.json.JSONObject

/** Script-declared controls only; size is always a host-owned setting. */
class DesktopPetSettingsActivity : Activity() {
    private val palette by lazy { com.yukisoffd.lyracode.interaction.overlay.OverlayPalette(this) }
    private lateinit var content: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 32); setBackgroundColor(palette.surface) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.surface); addView(content)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                val space = (16 * resources.displayMetrics.density).toInt()
                content.setPadding(space + bars.left, space + bars.top, space + bars.right, space + bars.bottom)
                insets
            }
        })
        val script = DevicePetStore.load(this)
        label("桌宠与悬浮对话", 24)
        label("${script.getString("name")}\n长按桌宠打开设置。拖动改变位置，首次点击唤醒，再次点击开关对话。")
        val options = DevicePetStore.effectiveOptions(this, script)
        range("桌宠大小（dp）", 40.0, 240.0, options.optDouble("size", 72.0)) { value -> save("size", value) }
        toggle("当前对话模型支持图片（自定义模型需手动开启）", DevicePetStore.options(this).optBoolean("current_model_vision")) { save("current_model_vision", it) }
        label("若已配置视觉补充模型，截图优先使用该模型；每次上传前会确认。修改后对新任务生效。")
        toggle("自动吸附屏幕侧边", options.optBoolean("autoDock", true)) { save("autoDock", it) }
        range("吸边等待（毫秒）", 500.0, 60000.0, options.optDouble("dockDelayMs", 3500.0)) { save("dockDelayMs", it) }
        range("侧边隐藏比例", 0.0, .8, options.optDouble("dockFraction", .5)) { save("dockFraction", it) }
        range("侧边不透明度", .1, 1.0, options.optDouble("dockOpacity", .42)) { save("dockOpacity", it) }
        range("展开不透明度", .2, 1.0, options.optDouble("activeOpacity", 1.0)) { save("activeOpacity", it) }
        toggle("允许宠物包播放声音（修改会重载桌宠）", options.optBoolean("soundEnabled", false)) { save("soundEnabled", it) }
        range("音量", 0.0, 1.0, options.optDouble("volume", .5)) { save("volume", it) }
        label("支持 GIF、APNG 和 PNG 帧动画。导入 ZIP 内的 manifest.json 决定入口和可调参数；目录可自由组织。")
        button("导入宠物包（ZIP，最多 64 MiB；兼容旧 JSON）") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1) }
        button("导出当前宠物包（ZIP）") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE, "desktop-pet.zip"), 2) }
        button("恢复默认桌宠") { DevicePetStore.reset(this); render() }
        val controls = script.optJSONArray("controls")
        val values = DevicePetStore.controls(script, options)
        if (controls != null) for (i in 0 until controls.length()) {
            val c = controls.getJSONObject(i); val key = c.getString("key"); val title = c.getString("label")
            when (c.getString("type")) {
                "range" -> range(title, c.getDouble("min"), c.getDouble("max"), values.getDouble(key)) { saveControl(key, it) }
                "boolean" -> toggle(title, values.getBoolean(key)) { saveControl(key, it) }
                "select" -> {
                    label(title)
                    val choices = c.getJSONArray("options").let { array -> (0 until array.length()).map { array.getString(it) } }
                    content.addView(Spinner(this).apply {
                        adapter = ArrayAdapter(this@DesktopPetSettingsActivity, android.R.layout.simple_spinner_dropdown_item, choices)
                        setSelection(choices.indexOf(values.getString(key)).coerceAtLeast(0))
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { saveControl(key, choices[position]) }
                        }
                    })
                }
                "text" -> {
                    label(title)
                    val editor = EditText(this).apply { setText(values.getString(key)); setTextColor(palette.text); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
                    content.addView(editor); button("应用$title") { saveControl(key, editor.text.toString()) }
                }
                "color" -> {
                    label(title)
                    val editor = EditText(this).apply { setText(values.getString(key)); setTextColor(palette.text); hint = "#RRGGBB" }
                    content.addView(editor)
                    button("应用颜色") { if (Regex("#[0-9a-fA-F]{6}").matches(editor.text.toString())) saveControl(key, editor.text.toString()) }
                }
            }
        }
        button("返回") { finish() }
    }
    private fun save(key: String, value: Any) = DevicePetStore.saveOptions(this, DevicePetStore.options(this).put(key, value))
    private fun saveControl(key: String, value: Any) {
        val options = DevicePetStore.options(this)
        options.put("controls", (options.optJSONObject("controls") ?: JSONObject()).put(key, value))
        DevicePetStore.saveOptions(this, options)
    }
    private fun label(text: String, size: Int = 14) = TextView(this).also {
        it.text = text; it.textSize = size.toFloat(); it.setTextColor(palette.text); it.setPadding(0, 12, 0, 12); content.addView(it)
    }
    private fun button(text: String, action: () -> Unit) { content.addView(Button(this).apply { this.text = text; setTextColor(palette.accent); background = android.graphics.drawable.GradientDrawable().apply { setColor(palette.container); cornerRadius = 72f }; setOnClickListener { action() } }) }
    private fun toggle(text: String, value: Boolean, change: (Boolean) -> Unit) {
        content.addView(Switch(this).apply { this.text = text; setTextColor(palette.text); isChecked = value; setOnCheckedChangeListener { _, checked -> change(checked) } })
    }
    private fun range(title: String, min: Double, max: Double, value: Double, change: (Double) -> Unit) {
        val text = label("$title · ${formatValue(value)}")
        content.addView(SeekBar(this).apply {
            this.max = 100; progress = ((value - min) / (max - min) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { val next = min + (max - min) * progress / 100; text.text = "$title · ${formatValue(next)}"; change(next) }
                }
            })
        })
    }
    private fun formatValue(value: Double) = if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(java.util.Locale.ROOT, "%.2f", value)
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode !in 1..2) return
        fun enable(view: android.view.View, value: Boolean) {
            view.isEnabled = value
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i), value)
        }
        label("正在处理宠物包…"); enable(content, false)
        Thread {
            val result = runCatching {
                if (requestCode == 1) {
                    contentResolver.openInputStream(uri)!!.buffered().use { input ->
                        input.mark(4); val first = input.read(); val second = input.read(); input.reset()
                        if (first == 'P'.code && second == 'K'.code) DevicePetStore.installZip(this, input)
                        else {
                            val bytes = input.readNBytes(DevicePetStore.MAX_BYTES + 1)
                            require(bytes.size <= DevicePetStore.MAX_BYTES) { "旧 JSON 文件超过 2 MiB" }
                            DevicePetStore.install(this, bytes.toString(Charsets.UTF_8))
                        }
                    }
                } else DevicePetStore.exportZip(this, contentResolver.openOutputStream(uri, "wt")!!)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    render()
                    Toast.makeText(this, result.fold({ if (requestCode == 1) "宠物包已导入" else "宠物包已导出" }, { "桌宠操作失败：${it.message}" }), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
