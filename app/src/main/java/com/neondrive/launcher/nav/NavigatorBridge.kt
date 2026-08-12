package com.neondrive.launcher.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

data class NavApp(val packageName: String, val label: String)

/**
 * Работа с навигационными приложениями, установленными на головном устройстве.
 *
 * Важно понимать границу возможного: Навигатор не отдаёт наружу ни геометрию
 * маршрута, ни текущий манёвр — публичного API нет, URL-схемы односторонние.
 * Отрисовать его ведение в своей карте поэтому нельзя ни при каких настройках.
 *
 * Отсюда два режима показа карты, ни один из которых не требует ключа:
 *
 *  • EMBEDDED — карту рисует сама оболочка (osmdroid) и сама же ведёт по маршруту.
 *               Заводской режим: работает на любой прошивке без настройки;
 *  • OVERLAY  — приложение занимает весь экран, а панели оболочки висят поверх него.
 *
 * Третий режим, «во фрейме» (плавающее окно навигатора по границам панели), был
 * удалён: он требовал freeform-режима прошивки, которого на большинстве ГУ нет и
 * который приложение включить не может.
 */
object NavigatorBridge {

    const val PKG_NAVI = "ru.yandex.yandexnavi"
    const val PKG_MAPS = "ru.yandex.yandexmaps"
    const val PKG_GMAPS = "com.google.android.apps.maps"
    const val PKG_NAVITEL = "com.navitel"

    /** Пакеты, которые точно являются навигацией, но могут не отвечать на geo:. */
    private val KNOWN = linkedMapOf(
        PKG_NAVI to "Яндекс Навигатор",
        PKG_MAPS to "Яндекс Карты",
        PKG_NAVITEL to "Навител",
        PKG_GMAPS to "Google Карты",
        "ru.dublgis.dgismobile" to "2ГИС",
        "com.sygic.aura" to "Sygic",
        "com.waze" to "Waze",
        "com.mapswithme.maps.pro" to "Organic Maps"
    )

    /* ─────────────────  ЧТО УСТАНОВЛЕНО  ───────────────── */

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /**
     * Все навигационные приложения устройства.
     *
     * Собираем из трёх источников, чтобы не зависеть от хардкода: приложения,
     * умеющие открывать `geo:`-ссылки, приложения с категорией APP_MAPS и
     * заведомо известные пакеты. Так в списке окажется и Навител, и любой
     * штатный навигатор конкретной прошивки.
     */
    fun installedNavApps(context: Context): List<NavApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()

        fun collect(intent: Intent) {
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()
                ?.forEach { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@forEach
                    if (pkg == context.packageName) return@forEach
                    found.putIfAbsent(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg))
                }
        }

        collect(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
        collect(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS))

        KNOWN.forEach { (pkg, label) ->
            if (isInstalled(context, pkg)) found.putIfAbsent(pkg, label)
        }

        // Известные — вперёд, у них понятные названия
        return found.entries
            .map { NavApp(it.key, KNOWN[it.key] ?: it.value) }
            .sortedBy { KNOWN.keys.indexOf(it.packageName).takeIf { i -> i >= 0 } ?: 99 }
    }

    /** Первый доступный пакет: сначала выбранный, потом любой установленный навигатор. */
    fun resolvePackage(context: Context, preferred: String): String? {
        if (isInstalled(context, preferred)) return preferred
        return installedNavApps(context).firstOrNull()?.packageName
    }

    fun labelOf(context: Context, pkg: String): String =
        KNOWN[pkg] ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    /* ─────────────────  ЗАПУСК  ───────────────── */

    fun openFullscreen(context: Context, preferred: String): Boolean {
        val pkg = resolvePackage(context, preferred) ?: return notInstalled(context)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    /* ─────────────────  URL-СХЕМЫ  ───────────────── */

    /**
     * Маршрут до точки. У Яндекса схема документирована:
     * `yandexnavi://build_route_on_map?lat_from=&lon_from=&lat_to=&lon_to=`.
     * Для остальных приложений уходим на универсальный `geo:`-интент.
     */
    fun buildRoute(
        context: Context,
        pkg: String,
        latTo: Double,
        lonTo: Double,
        latFrom: Double? = null,
        lonFrom: Double? = null
    ) {
        if (pkg == PKG_NAVI) {
            val sb = StringBuilder("yandexnavi://build_route_on_map?lat_to=$latTo&lon_to=$lonTo")
            if (latFrom != null && lonFrom != null) sb.append("&lat_from=$latFrom&lon_from=$lonFrom")
            if (sendScheme(context, sb.toString(), pkg)) return
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latTo,$lonTo?q=$latTo,$lonTo"))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, geo)) openFullscreen(context, pkg)
    }

    /** Показать точку на карте. */
    fun showPoint(
        context: Context,
        pkg: String,
        lat: Double,
        lon: Double,
        zoom: Int = 16,
        description: String? = null
    ) {
        if (pkg == PKG_NAVI) {
            val desc = description?.let { "&desc=" + Uri.encode(it) }.orEmpty()
            val uri = "yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=$zoom$desc&no-balloon=0"
            if (sendScheme(context, uri, pkg)) return
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?z=$zoom"))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, geo)) openFullscreen(context, pkg)
    }

    private fun sendScheme(context: Context, uri: String, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        return resolvable && start(context, intent)
    }

    /* ─────────────────  СЛУЖЕБНОЕ  ───────────────── */

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    private fun notInstalled(context: Context): Boolean {
        toast(context, "Навигационное приложение не найдено")
        return false
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
