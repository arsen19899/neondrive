package com.neondrive.launcher.assets

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

enum class DownloadPhase {
    IDLE,

    /** Перебираем адреса, ждём первого ответившего. */
    CONNECTING,

    DOWNLOADING,

    /** Распаковываем архив. */
    UNPACKING,

    DONE,
    ERROR,
    CANCELLED
}

data class DownloadState(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val error: String = "",
    /** С какого адреса реально качаем — их несколько, и это видно человеку. */
    val sourceHost: String = ""
) {
    val percent: Int
        get() = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0

    val active: Boolean
        get() = phase == DownloadPhase.CONNECTING ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.UNPACKING

    /** «12 из 304 МБ» — то, что показывается под строкой прогресса. */
    val sizeLabel: String
        get() {
            fun mb(v: Long) = (v / (1024.0 * 1024.0))
            return if (total > 0L) {
                String.format("%.0f из %.0f МБ", mb(bytes), mb(total))
            } else {
                String.format("%.0f МБ", mb(bytes))
            }
        }
}

/**
 * Состояние докачки файлов и единственная точка входа для интерфейса.
 *
 * Сама работа идёт в [DownloadService] — фоновом сервисе с уведомлением.
 * Разделение не формальное: карта весит триста мегабайт, на связи в машине это
 * десятки минут, и за это время активити успеет пересоздаться от поворота
 * экрана или нехватки памяти. Скачивание, привязанное к экрану настроек,
 * оборвалось бы на середине.
 *
 * Здесь же лежит очередь: одновременно качается ровно один файл. Тянуть карту и
 * модель распознавания параллельно на канале, раздаваемом с телефона, — верный
 * способ не получить вовремя ни того, ни другого.
 */
object AssetHub {

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    /** Очередь идентификаторов. Сервис разбирает её по одному. */
    internal val queue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Отменённые — сервис проверяет этот набор в цикле чтения. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun stateOf(id: String): DownloadState = _states.value[id] ?: DownloadState()

    /** Качается ли что-нибудь прямо сейчас. */
    val busy: Boolean
        get() = _states.value.values.any { it.active }

    /**
     * Поставить файл в очередь на скачивание.
     *
     * Вызывается только после явного согласия человека — диалог показывает
     * [ui.settings.AssetConsentDialog]. Согласие спрашивается один раз на файл:
     * дальше оболочка всё делает сама, включая распаковку и раскладку по
     * папкам, — этого и просили.
     */
    fun request(context: Context, asset: Asset) {
        cancelled.remove(asset.id)
        update(asset.id) { DownloadState(phase = DownloadPhase.CONNECTING) }
        queue.add(asset.id)

        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            update(asset.id) {
                DownloadState(phase = DownloadPhase.ERROR, error = "Не удалось запустить загрузку")
            }
        }
    }

    fun cancel(id: String) {
        cancelled.add(id)
        queue.remove(id)
        update(id) { it.copy(phase = DownloadPhase.CANCELLED) }
    }

    internal fun isCancelled(id: String): Boolean = id in cancelled

    /** Убрать с экрана отчёт о завершении — чтобы строка вернулась в обычный вид. */
    fun dismiss(id: String) {
        _states.value = _states.value - id
    }

    internal fun update(id: String, block: (DownloadState) -> DownloadState) {
        val current = _states.value[id] ?: DownloadState()
        _states.value = _states.value + (id to block(current))
    }
}
