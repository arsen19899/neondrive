package com.neondrive.launcher.nav

import android.content.Context
import android.widget.Toast
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.overlay.NeonOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Единая точка управления показом навигации.
 *
 * Раньше отсюда же поднимался режим «во фрейме» — чужое приложение в плавающем
 * окне по границам панели карты. Режим удалён вместе со всей обвязкой: границами
 * панели, безопасной зоной для вторичных экранов и отслеживанием многооконного
 * режима. Плавающие окна требуют freeform-режима прошивки, которого на
 * большинстве головных устройств нет и который нельзя включить из приложения, —
 * то есть функция у большинства просто не работала, а сложности добавляла всем.
 *
 * Осталось два понятных случая:
 *  • [MapMode.EMBEDDED] — карту рисует сама оболочка, кнопка просто открывает
 *    сторонний навигатор на весь экран, если он зачем-то понадобился;
 *  • [MapMode.OVERLAY] — навигатор на весь экран, панели оболочки поверх него.
 */
object MapFrameController {

    /** Подняты ли панели оболочки поверх полноэкранного навигатора. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** Автозапуск отрабатывает один раз за сессию оболочки. */
    private var autoStarted = false

    /**
     * Открыть навигацию в соответствии с выбранным режимом.
     * Возвращает false, если не удалось — например, нет разрешения на оверлей.
     */
    fun launch(context: Context, settings: LauncherSettings): Boolean {
        val pkg = settings.mapPackage
        return when (settings.mapMode) {
            // Карту рисует сама оболочка, поднимать поверх нечего: кнопка
            // означает буквально «открыть сторонний навигатор на весь экран».
            // Признак active не поднимаем — рабочий стол остаётся прежним, к нему
            // возвращаются кнопкой «Домой».
            MapMode.EMBEDDED -> {
                NeonOverlayService.hide(context)
                _active.value = false
                NavigatorBridge.openFullscreen(context, pkg)
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

    /** Убрать панели оболочки поверх карты. */
    fun stop(context: Context) {
        NeonOverlayService.hide(context)
        _active.value = false
    }

    /** Плитка «Навигация» в доке работает как тумблер: поднято — сворачиваем. */
    fun toggle(context: Context, settings: LauncherSettings): Boolean {
        if (_active.value) {
            stop(context)
            // Чужое полноэкранное окно закрыть за пользователя нельзя, но поднять
            // оболочку на передний план можно — этого достаточно, чтобы рабочий
            // стол сразу стал доступен целиком.
            com.neondrive.launcher.automation.ForegroundLauncher.bringToFront(context)
            return false
        }
        return launch(context, settings)
    }

    /** Автозапуск навигации при старте оболочки. */
    suspend fun autoStartIfNeeded(context: Context, settings: LauncherSettings) {
        if (autoStarted) return
        if (!settings.mapAutoStart) return

        // В режиме своей карты автозапускать нечего: карта уже на рабочем столе и
        // появляется вместе с ним. Открывать поверх неё ещё и чужой навигатор на
        // весь экран — ровно то, чего этим режимом и хотели избежать.
        if (settings.mapMode == MapMode.EMBEDDED) return

        // Автозапуск не должен угонять экран под системные настройки. Ручной
        // [launch] без разрешения на оверлей показывает тост и открывает системный
        // экран выдачи — это правильно, когда пользователь сам нажал плитку, но на
        // старте ГУ означало бы, что каждое включение зажигания встречает водителя
        // чужим экраном настроек. Поэтому молча пропускаем.
        if (!NeonOverlayService.canDraw(context)) return

        autoStarted = true
        kotlinx.coroutines.delay(settings.mapAutoStartDelaySec * 1000L)
        launch(context, settings)
    }

    /** Сбросить признак автозапуска — например, при перезапуске активности. */
    fun resetAutoStart() {
        autoStarted = false
    }
}
