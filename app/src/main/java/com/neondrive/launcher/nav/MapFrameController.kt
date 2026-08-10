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
 * Рабочий стол сообщает сюда экранные границы ячейки под карту, а дальше контроллер сам решает,
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
            MapMode.FRAME -> {
                NeonOverlayService.hide(context)
                val bounds = _frameBounds.value
                val ok = NavigatorBridge.openInFrame(context, pkg, bounds)
                _active.value = ok
                if (ok) lastLaunchBounds = Rect(bounds)
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
<<<<<<< HEAD
     * Кнопка «Навигация» в доке работает как тумблер: карта поднята — сворачиваем,
     * свёрнута — поднимаем.
     *
     * Зачем это нужно. В режиме FRAME навигатор живёт в отдельном плавающем окне
     * системы, и когда оно есть, панель карты на рабочем столе не рисуется —
     * значит, и чипа «Убрать панели» на ней нет. Раньше из-за этого свернуть фрейм
     * изнутри оболочки было нечем: пользователь закрывал плавающее окно системными
     * средствами, оболочка об этом не узнавала, [active] навсегда оставался true,
     * и все вторичные экраны продолжали ужиматься в узкую полосу рядом с уже
     * несуществующим окном. Теперь состояние всегда можно поправить одним нажатием
     * на ту же плитку дока, которой карта и поднималась.
     */
    fun toggle(context: Context, settings: LauncherSettings): Boolean {
        if (_active.value) {
            stop(context)
            // Плавающее окно навигатора закрыть за пользователя нельзя — чужим
            // окном управляет система. Но поднять оболочку на передний план можно,
            // и этого достаточно, чтобы меню сразу стало доступным во весь экран.
            com.neondrive.launcher.automation.ForegroundLauncher.bringToFront(context)
            return false
        }
        return launch(context, settings)
    }

    /**
     * Сообщение от активности: оболочка снова одна на экране, плавающего окна
     * навигатора рядом нет. Значит, фрейм свёрнут — даже если сворачивали его
     * системными средствами, мимо оболочки.
     */
    fun markFrameClosed() {
        if (_active.value) _active.value = false
    }

    /**
     * Переставить уже поднятое окно навигатора под текущие границы панели.
     *
     * Плавающее окно — окно системы, а не наш элемент разметки: когда рабочий стол
     * перестраивается (сменили сторону карты, сторону дока, убрали строку приборов,
     * повернули экран), оно само никуда не переезжает и остаётся висеть на прежнем
     * месте. Единственный доступный способ подвинуть его — запустить навигатор
     * заново с новыми launch bounds.
     *
     * Вызывается только когда фрейм действительно поднят и границы заметно
     * изменились: перезапуск — операция видимая, дёргать её на каждый пиксель
     * пересчёта разметки нельзя.
     */
    fun relaunchIfActive(context: Context, settings: LauncherSettings) {
        if (!_active.value || settings.mapMode != MapMode.FRAME) return
        val bounds = _frameBounds.value
        if (bounds.isEmpty) return
        if (!lastLaunchBounds.isEmpty && movedLittle(lastLaunchBounds, bounds)) return
        lastLaunchBounds = Rect(bounds)
        NavigatorBridge.openInFrame(context, settings.mapPackage, bounds)
    }

    /** Порог «то же самое место» — меньше него перезапуск не оправдан. */
    private fun movedLittle(a: Rect, b: Rect): Boolean {
        val tolerance = 48
        return kotlin.math.abs(a.left - b.left) < tolerance &&
            kotlin.math.abs(a.top - b.top) < tolerance &&
            kotlin.math.abs(a.right - b.right) < tolerance &&
            kotlin.math.abs(a.bottom - b.bottom) < tolerance
    }

    private var lastLaunchBounds = Rect()

    /**
=======
>>>>>>> parent of 5eab26a (Add files via upload)
     * Автозапуск при старте оболочки. Ждём, пока панель сообщит свои границы,
     * иначе плавающее окно откроется не туда.
     */
    suspend fun autoStartIfNeeded(
        context: Context,
        settings: LauncherSettings,
        awaitBounds: suspend () -> Unit
    ) {
        if (autoStarted) return
        if (!settings.mapAutoStart) return
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
