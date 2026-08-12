package com.neondrive.launcher.data

import com.neondrive.launcher.media.FmStation
import com.neondrive.launcher.media.RadioStation

/* ─────────────────  МОДЕЛИ НАСТРОЕК  ───────────────── */

/** Что делать с музыкой, когда с подключённого телефона приходит уведомление. */
enum class NotificationReaction(val label: String, val hint: String) {
    IGNORE("Нет", "Играть дальше без изменений"),
    DUCK("Приглушать", "Временно снизить громкость на время уведомления"),
    PAUSE("Да, ставить на паузу", "Полная пауза, затем автоматическое возобновление");
}

enum class MusicSource(val label: String) {
    DEVICE("С устройства"),
    RADIO("Радио"),
    YANDEX("Яндекс.Музыка")
}

enum class SidebarSide { LEFT, RIGHT }

/** Источник радио: интернет-поток или обычный FM-тюнер по антенне ГУ. */
enum class RadioMode(val label: String) {
    INTERNET("Интернет-радио"),
    FM("FM-радио")
}

enum class SpeedUnits(val label: String, val factorFromMs: Float) {
    KMH("км/ч", 3.6f),
    MPH("mph", 2.2369363f)
}

/**
 * Один уровень схемы «громкость от скорости».
 * [fromKmh] — с какой скорости уровень активен, [gain] — прибавка в процентах
 * от базовой громкости (0..100).
 */
data class SpeedVolumeStep(val fromKmh: Int, val gain: Int)

/** Сохранённая точка: дом, работа, дача, что угодно. */
data class FavoritePlace(val name: String, val lat: Double, val lon: Double)

/** Действия, которые можно назначить на кнопку руля. */
enum class SwcAction(val label: String) {
    NONE("— не назначено —"),
    PLAY_PAUSE("Плей / Пауза"),
    NEXT("Следующий трек"),
    PREV("Предыдущий трек"),
    VOL_UP("Громче"),
    VOL_DOWN("Тише"),
    MUTE("Мьют"),
    SOURCE_NEXT("Смена источника"),
    ANSWER_CALL("Принять вызов"),
    END_CALL("Сбросить вызов"),
    VOICE("Голосовой помощник"),
    HOME("На главный экран"),
    NAVIGATION("Открыть навигацию"),
    APPS("Все приложения")
}

/** Заводские значения, вынесены наружу, чтобы не ловить циклы в конструкторе. */
object Defaults {
    val speedSteps = listOf(
        SpeedVolumeStep(0, 0),
        SpeedVolumeStep(60, 6),
        SpeedVolumeStep(90, 12),
        SpeedVolumeStep(120, 20)
    )

    val swcShort: Map<Int, SwcAction> = mapOf(
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to SwcAction.PLAY_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT to SwcAction.NEXT,
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS to SwcAction.PREV,
        android.view.KeyEvent.KEYCODE_VOLUME_UP to SwcAction.VOL_UP,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN to SwcAction.VOL_DOWN,
        android.view.KeyEvent.KEYCODE_CALL to SwcAction.ANSWER_CALL,
        android.view.KeyEvent.KEYCODE_ENDCALL to SwcAction.END_CALL,
        android.view.KeyEvent.KEYCODE_VOICE_ASSIST to SwcAction.VOICE
    )
}

/** Полный снимок конфигурации оболочки. */
data class LauncherSettings(
    /* Внешний вид */
    val accent: String = "CYAN",
    val sidebarSide: SidebarSide = SidebarSide.RIGHT,
    val animatedBackground: Boolean = true,
    val show24h: Boolean = true,
    val units: SpeedUnits = SpeedUnits.KMH,
    val mapPackage: String = "ru.yandex.yandexnavi",
    /** Показывать спидометр на рабочем столе; при выключении плеер занимает его место. */
    val showSpeedometer: Boolean = true,
    /** Упрощённая графика — меньше фоновых анимаций для слабых ГУ. */
    val reducedEffects: Boolean = false,
    /** Путь к пользовательской картинке фона во внутреннем хранилище; пусто — стандартный фон. */
    val backgroundImagePath: String = "",
    /** Затемнение пользовательского фона (0 — совсем не темнить, 1 — почти чёрный), чтобы яркая картинка не резала глаз. */
    val backgroundDarken: Float = 0.55f,

    /**
     * Папки на SD/USB, добавленные вручную через системный выбор (SAF) — резервный
     * путь на случай, если прошивка ГУ не индексирует съёмный накопитель в MediaStore
     * (тогда обычное сканирование [com.neondrive.launcher.media.MediaLibrary] его не видит).
     */
    val extraMusicFolders: List<String> = emptyList(),

    /* Радиостанции, сохранённые пользователем поиском (см. вкладку «Поиск» в Музыке) */
    val customStations: List<RadioStation> = emptyList(),
    /** Интернет-радио или обычный FM-приём по антенне ГУ. */
    val radioMode: RadioMode = RadioMode.INTERNET,
    /** FM-станции, сохранённые вручную (частота + название). */
    val fmStations: List<FmStation> = emptyList(),

    /* Навигация */
    val mapSide: SidebarSide = SidebarSide.RIGHT,
    /**
     * Какую долю ширины экрана занимает навигация, %.
     *
     * Одно число управляет обоими режимами, чтобы «половина экрана» означала
     * половину везде:
     *  • на рабочем столе по нему считается ширина панели карты;
     *  • в режиме OVERLAY по нему считается суммарная ширина панелей оболочки,
     *    и свободной под чужую карту остаётся ровно эта же доля.
     *
     * 50 — навигация на половине экрана. Раньше доля была зашита константой
     * (0.75 на рабочем столе и ~0.53 в оверлее), и подогнать её под конкретное
     * ГУ было нельзя.
     */
    val mapScreenPercent: Int = 50,
    /**
     * Голосовое ведение собственной навигации (режим «Своя карта»).
     * На сторонний навигатор не влияет — у него свои настройки звука.
     */
    val navVoice: Boolean = true,
    /** Громкость подсказок, % — независимо от громкости музыки. */
    val navVoiceVolume: Int = 90,
    /** Приглушать музыку на время подсказки (audio focus «ducking»). */
    val navDuckMusic: Boolean = true,
    /** Предупреждать о камерах на маршруте. */
    val navCameraWarn: Boolean = true,
    /** Показывать знак ограничения скорости и предупреждать о превышении. */
    val navSpeedLimitWarn: Boolean = true,
    /** На сколько км/ч можно превысить, прежде чем оболочка предупредит. */
    val navSpeedTolerance: Int = 10,
    /** Поворачивать карту по курсу движения вместо «север сверху». */
    val navRotateMap: Boolean = true,
    /** Менять масштаб автоматически: в городе ближе, на трассе дальше. */
    val navAutoZoom: Boolean = true,
    /** Предпочитать вариант маршрута без грунтовых дорог. */
    val navAvoidUnpaved: Boolean = false,
    /** Предпочитать вариант маршрута без платных участков. */
    val navAvoidToll: Boolean = false,
    /** Предпочитать офлайн-граф, если он загружен на устройство. */
    val navOfflineRouting: Boolean = true,
    /** Избранные точки: «имя|lat|lon». */
    val navFavorites: List<FavoritePlace> = emptyList(),
    /** История поиска, последние запросы. */
    val navSearchHistory: List<String> = emptyList(),
    /**
     * Незавершённый маршрут: точка назначения и её название.
     *
     * Оболочка на магнитоле перезапускается чаще, чем обычное приложение — от
     * нехватки памяти, от пересоздания при смене конфигурации, просто от того,
     * что водитель заглушил машину на заправке. Терять из-за этого проложенный
     * маршрут нельзя: набирать адрес заново за рулём — худшее, что можно
     * предложить. NaN — активного маршрута нет.
     */
    val lastDestLat: Double = Double.NaN,
    val lastDestLon: Double = Double.NaN,
    val lastDestTitle: String = "",

    /** Сохранённая точка «Дом»; NaN — не задана. */
    val homeLat: Double = Double.NaN,
    val homeLon: Double = Double.NaN,

    /* 1. Автопроигрывание музыки */
    val autoplay: Boolean = true,
    val autoplayDelaySec: Int = 0,
    val autoplaySource: MusicSource = MusicSource.DEVICE,

    /* 2. Реакция на уведомления с подключённого девайса */
    val notificationReaction: NotificationReaction = NotificationReaction.DUCK,
    /** Насколько приглушать, % от текущей громкости (режим DUCK). */
    val duckPercent: Int = 35,
    /** Сколько держать приглушение после уведомления, мс. */
    val duckHoldMs: Int = 2500,
    /** Реагировать только на уведомления Bluetooth-телефона, а не всей системы. */
    val onlyPairedDeviceNotifications: Boolean = true,

    /* 3. Громкость от скорости */
    val speedVolumeEnabled: Boolean = true,
    val speedSteps: List<SpeedVolumeStep> = Defaults.speedSteps,
    /** Насколько плавно подтягивать громкость, мс на шаг. */
    val speedVolumeSmoothMs: Int = 1200,

    /* 4. Возврат музыки после телефонного вызова */
    val resumeAfterCall: Boolean = true,
    val resumeAfterCallDelaySec: Int = 2,

    /* Кнопки руля */
    val swcEnabled: Boolean = true,
    val swcLongPressMs: Int = 600,
    /** keyCode -> действие (короткое нажатие). */
    val swcShort: Map<Int, SwcAction> = Defaults.swcShort,
    /** keyCode -> действие (долгое нажатие). */
    val swcLong: Map<Int, SwcAction> = emptyMap(),
    /** Резистивные кнопки читаются напрямую из ADC-ноды ядра. */
    val swcAdcEnabled: Boolean = false,
    val swcAdcPath: String = "/sys/class/adc_key/value",
    /** Разброс АЦП, в пределах которого значение считается той же кнопкой. */
    val swcAdcTolerance: Int = 25,
    /** ADC-значение -> действие. */
    val swcAdcMap: Map<Int, SwcAction> = emptyMap(),

    /**
     * MAC-адрес выбранного пользователем «телефонного» Bluetooth-устройства —
     * настройки → Bluetooth. Пусто — устройство не выбрано явно, оболочка
     * определяет подключённый телефон по первому Bluetooth-профилю HEADSET,
     * как и раньше.
     */
    val phoneBluetoothAddress: String = "",

    /* Прочее */
    val startOnBoot: Boolean = true,
    /** Поднимать оболочку при каждом включении экрана — «пробуждении» магнитолы. */
    val startOnScreenOn: Boolean = true,
    /** Пользователь хочет, чтобы NeonDrive был лаунчером по умолчанию. */
    val beDefaultLauncher: Boolean = false,
    val keepScreenOn: Boolean = true
) {
    val hasHomePoint: Boolean get() = !homeLat.isNaN() && !homeLon.isNaN()

    /** Прибавка громкости в % для текущей скорости. */
    fun gainForSpeed(kmh: Float): Int {
        if (!speedVolumeEnabled) return 0
        return speedSteps
            .sortedBy { it.fromKmh }
            .lastOrNull { kmh >= it.fromKmh }
            ?.gain ?: 0
    }
}
