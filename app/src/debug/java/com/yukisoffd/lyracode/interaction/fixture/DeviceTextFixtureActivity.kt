package com.yukisoffd.lyracode.interaction.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.yukisoffd.lyracode.R

/** Local debug-only action/verification fixture. No network or submit handler. */
class DeviceTextFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 2, padding, padding)
            isFocusableInTouchMode = true
        }
        body.addView(TextView(this).apply { text = "设备文本动作测试"; textSize = 22f })
        fun editor(id: Int, hint: String, kind: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.id = id
            this.hint = hint
            inputType = kind
            textSize = 16f
            body.addView(this)
        }
        editor(R.id.device_fixture_draft, "普通草稿").setText("原始内容")
        editor(R.id.device_fixture_filtered, "限长字段").filters = arrayOf(InputFilter.LengthFilter(5))
        if (intent.getBooleanExtra("sensitive", false)) {
            editor(R.id.device_fixture_password, "密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            editor(R.id.device_fixture_otp, "验证码")
        }
        editor(R.id.device_fixture_disabled, "不可编辑").isEnabled = false
        setContentView(body)
        body.requestFocus()
    }
}
