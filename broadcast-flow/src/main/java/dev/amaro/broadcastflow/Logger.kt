package dev.amaro.broadcastflow

import android.util.Log

internal object Logger {
    private var isActive = false
    fun setOn() {
        isActive = true
    }

    fun setOff() {
        isActive = false
    }

    fun log(value: String) {
        if (isActive)
            Log.d("FLOW_CASTER", value)
    }
}