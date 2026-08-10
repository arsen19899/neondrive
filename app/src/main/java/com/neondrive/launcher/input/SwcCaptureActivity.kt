package com.neondrive.launcher.input

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Невидимое окно для режима обучения кнопок руля: некоторые ГУ отдают часть клавиш
 * только активному окну. Активность закрывается сама, как только обучение выключено.
 */
class SwcCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setLayout(1, 1)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SteeringWheelManager.learning.value) {
            SteeringWheelManager.handleKeyEvent(event)
            return true
        }
        finish()
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }
}
