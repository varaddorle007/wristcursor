package com.drift.airmouse

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.drift.airmouse.core.PointerBus
import com.drift.airmouse.ui.DriftApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DriftApp(onClose = ::finish) }
    }

    /**
     * Any tap on any screen jolts the watch the way a pinch does. Marking every touch here
     * (not just on the steering screen) stops taps in menus from clicking or pausing by accident.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerBus.touching(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> PointerBus.touching(false)
        }
        return super.dispatchTouchEvent(event)
    }
}
