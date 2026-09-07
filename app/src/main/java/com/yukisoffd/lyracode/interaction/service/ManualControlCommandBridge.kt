package com.yukisoffd.lyracode.interaction.service

/** Process-local bridge; the foreground overlay never keeps an AccessibilityService reference. */
internal object ManualControlCommandBridge {
    @Volatile
    private var confirmAction: ((String, String, String) -> Unit)? = null

    fun attach(confirmAction: (String, String, String) -> Unit) {
        this.confirmAction = confirmAction
    }

    fun detach() {
        confirmAction = null
    }

    fun requestConfirm(snapshotId: String?, handle: String?, requestId: String?) {
        if (snapshotId == null || handle == null || requestId == null) return
        confirmAction?.invoke(snapshotId, handle, requestId)
    }
}
