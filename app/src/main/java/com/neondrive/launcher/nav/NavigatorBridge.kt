package com.neondrive.launcher.nav

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * Работа с уже установленным Яндекс.Навигатором.
 *
 * Важно понимать границу возможного: подписка Плюс в самом Навигаторе относится
 * к аккаунту пользователя внутри того приложения и никак не даёт стороннему
 * лаунчеру право рисовать карту Яндекса у себя. Отрисовка карты внутри своего
 * окна — это MapKit/NaviKit SDK, а он требует собственный ключ разработчика.
 *
 * Поэтому оболочка использует два законных пути, которым ключ не нужен:
 *  1. URL-схемы `yandexnavi://` — построить маршрут, показать точку;
 *  2. запуск Навигатора в плавающем окне ровно по границам панели карты
 *     (freeform), если прошивка головного устройства это умеет.
 */
object NavigatorBridge {

    const val PKG_NAVI = "ru.yandex.yandexnavi"
    const val PKG_MAPS = "ru.yandex.yandexmaps"
    const val PKG_GMAPS = "com.google.android.apps.maps"

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** Первый из установленных навигационных пакетов. */
    fun resolvePackage(context: Context, preferred: String): String? =
        listOf(preferred, PKG_NAVI, PKG_MAPS, PKG_GMAPS)
            .distinct()
            .firstOrNull { isInstalled(context, it) }

    /* ─────────────────  ОБЫЧНЫЙ ЗАПУСК  ───────────────── */

    fun openFullscreen(context: Context, preferred: String) {
        val pkg = resolvePackage(context, preferred) ?: return notInstalled(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Запуск в окне по границам панели карты.
     *
     * Работает на прошивках с включённым freeform. На большинстве китайских ГУ он
     * есть, но выключен — включается один раз через adb:
     *   adb shell settings put global enable_freeform_support 1
     *   adb shell settings put global force_resizable_activities 1
     * Если система команду не приняла, аккуратно откатываемся на полный экран.
     */
    fun openWindowed(context: Context, preferred: String, bounds: Rect) {
        val pkg = resolvePackage(context, preferred) ?: return notInstalled(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds.isEmpty) {
            openFullscreen(context, preferred)
            return
        }

        val options = ActivityOptions.makeBasic()
        runCatching { options.setLaunchBounds(bounds) }

        val ok = runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
        if (!ok) openFullscreen(context, preferred)
    }

    /* ─────────────────  URL-СХЕМЫ  ───────────────── */

    /**
     * Маршрут до точки. Схема подтверждена документацией Яндекса:
     * `yandexnavi://build_route_on_map?lat_from=&lon_from=&lat_to=&lon_to=`
     */
    fun buildRoute(
        context: Context,
        latTo: Double,
        lonTo: Double,
        latFrom: Double? = null,
        lonFrom: Double? = null
    ) {
        val sb = StringBuilder("yandexnavi://build_route_on_map?lat_to=$latTo&lon_to=$lonTo")
        if (latFrom != null && lonFrom != null) {
            sb.append("&lat_from=$latFrom&lon_from=$lonFrom")
        }
        sendScheme(context, sb.toString())
    }

    /**
     * Показать точку на карте:
     * `yandexnavi://show_point_on_map?lat=&lon=&zoom=&desc=&no-balloon=`
     */
    fun showPoint(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Int = 16,
        description: String? = null
    ) {
        val desc = description?.let { "&desc=" + Uri.encode(it) }.orEmpty()
        sendScheme(
            context,
            "yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=$zoom$desc&no-balloon=0"
        )
    }

    private fun sendScheme(context: Context, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(PKG_NAVI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolvable = context.packageManager.resolveActivity(intent, 0) != null
        if (resolvable) {
            start(context, intent)
        } else {
            // Навигатор старой версии или схема не зарегистрирована — открываем как есть
            openFullscreen(context, PKG_NAVI)
        }
    }

    private fun start(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { toast(context, "Не удалось открыть навигатор") }
    }

    private fun notInstalled(context: Context) {
        toast(context, "Навигационное приложение не найдено")
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
