package com.neondrive.launcher.nav

import android.content.Context
import android.graphics.Rect
import android.widget.Toast
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.overlay.NeonOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Единая точка управления «картой во фрейме».
 *
 * Панель карты сообщает сюда свои экранные границы, а дальше контроллер сам решает,
 * как поднять навигацию: плавающим окном по этим границам или на весь экран с
 * панелями оболочки поверх.
 */
object MapFrameController {

    /** Границы панели карты на экране — их присылает [com.neondrive.launcher.ui.home.MapPanel]. */
    private val _frameBounds = MutableStateFlow(Rect())
    val frameBounds: StateFlow<Rect> = _frameBounds

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** Автозапуск отрабатывает один раз за сессию оболочки. */
    private var autoStarted = false

    fun updateBounds(rect: Rect) {
        if (!rect.isEmpty) _frameBounds.value = rect
    }

    /**
     * Поднять навигацию в соответствии с выбранным режимом.
     * Возвращает false, если запустить не удалось — например, нет разрешения на оверлей.
     */
    fun launch(context: Context, settings: LauncherSettings): Boolean {
        val pkg = settings.mapPackage
        return when (settings.mapMode) {
            MapMode.OFF -> {
                NeonOverlayService.hide(context)
                _active.value = false
                NavigatorBridge.openFullscreen(context, pkg)
            }

            MapMode.FRAME -> {
                NeonOverlayService.hide(context)
                val bounds = _frameBounds.value
                val ok = NavigatorBridge.openInFrame(context, pkg, bounds)
                _active.value = ok
                if (ok && !NavigatorBridge.freeformSupported(context)) {
                    Toast.makeText(
                        context,
                        "Прошивка не сообщает о поддержке плавающих окон. " +
                            "Если карта открылась на весь экран — включите режим «Поверх карты»",
                        Toast.LENGTH_LONG
                    ).show()
                }
                ok
            }

            MapMode.OVERLAY -> {
                if (!NeonOverlayService.canDraw(context)) {
                    Toast.makeText(
                        context,
                        "Нужно разрешение «Поверх других приложений»",
                        Toast.LENGTH_LONG
                    ).show()
                    NeonOverlayService.requestPermission(context)
                    return false
                }
                val ok = NavigatorBridge.openFullscreen(context, pkg)
                if (ok) NeonOverlayService.show(context)
                _active.value = ok
                ok
            }
        }
    }

    /** Выключить режим: убрать панели поверх карты. */
    fun stop(context: Context) {
        NeonOverlayService.hide(context)
        _active.value = false
    }

    /**
     * Автозапуск при старте оболочки. Ждём, пока панель сообщит свои границы,
     * иначе плавающее окно откроется не туда.
     */
    suspend fun autoStartIfNeeded(
        context: Context,
        settings: LauncherSettings,
        awaitBounds: suspend () -> Unit
    ) {
        if (autoStarted) return
        if (!settings.mapAutoStart || settings.mapMode == MapMode.OFF) return
        autoStarted = true

        kotlinx.coroutines.delay(settings.mapAutoStartDelaySec * 1000L)
        if (settings.mapMode == MapMode.FRAME) awaitBounds()
        launch(context, settings)
    }

    /** Сбросить признак автозапуска — например, при перезапуске активности. */
    fun resetAutoStart() {
        autoStarted = false
    }
}
