package com.neondrive.launcher.phone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/** Состояние звонка в терминах, понятных экрану оболочки. */
enum class NeonCallState { NONE, DIALING, RINGING, ACTIVE, HOLDING, DISCONNECTED }

data class NeonCallInfo(
    val state: NeonCallState = NeonCallState.NONE,
    val number: String = "",
    /** Имя, которое отдало само телефонное соединение (не всегда есть — обычно пусто). */
    val callerDisplayName: String? = null,
    val incoming: Boolean = false,
    /** Момент, когда звонок стал активным (для таймера разговора). 0 — ещё не был активен. */
    val activeSinceMs: Long = 0L,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val canHold: Boolean = false,
    val canMerge: Boolean = false
) {
    val isEmpty: Boolean get() = state == NeonCallState.NONE
}

/**
 * Собственный экран звонка вместо системного.
 *
 * Android не даёt обычному приложению перехватить голосовой вызов иначе, чем через
 * платформенный Telecom — либо стать приложением-телефоном по умолчанию (слишком
 * навязчиво для оболочки ГУ), либо зарегистрировать [InCallService] с пометкой
 * «UI автомобильного режима» (`IN_CALL_SERVICE_CAR_MODE_UI`). Второй путь — именно
 * тот, которым пользуются Android Auto и аналоги: пока система находится в режиме
 * авто (см. [com.neondrive.launcher.NeonApp] — `UiModeManager.enableCarMode()`),
 * Telecom отдаёт управление экраном звонка этому сервису вместо стандартного
 * «Телефона», и разговор целиком остаётся внутри оболочки.
 *
 * Честная оговорка: это системный, но не универсальный механизм — некоторые
 * прошивки китайских ГУ по-своему патчят телефонию и могут всё равно поднимать
 * свой экран звонка поверх. Для звонков, которые оболочка инициирует сама
 * ([PhoneScreen]), это работает так же надёжно, как и вызов из системного номеронабирателя.
 */
class NeonInCallService : InCallService() {

    private var trackedCall: Call? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish(call)
    }

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
    }

    override fun onDestroy() {
        serviceRef = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // Один активный разговор — обычный случай для головного устройства;
        // конференц-звонки и вторую линию оболочка сознательно не усложняет.
        trackedCall = call
        call.registerCallback(callback)
        publish(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callback) }
        if (trackedCall == call) {
            trackedCall = null
            _current.value = NeonCallInfo(state = NeonCallState.DISCONNECTED)
            // Короткая пометка «разговор завершён» сама уйдёт в NONE — экран звонка
            // держит её на экране пару секунд, чтобы не мигать пустотой.
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        _current.value = _current.value.copy(
            muted = audioState.isMuted,
            speakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER
        )
    }

    private fun publish(call: Call) {
        val details = call.details
        val number = details?.handle?.schemeSpecificPart.orEmpty()
        val incoming = details?.callDirection == Call.Details.DIRECTION_INCOMING
        val neonState = when (call.state) {
            Call.STATE_RINGING -> NeonCallState.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT ->
                NeonCallState.DIALING
            Call.STATE_ACTIVE -> NeonCallState.ACTIVE
            Call.STATE_HOLDING -> NeonCallState.HOLDING
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> NeonCallState.DISCONNECTED
            else -> _current.value.state
        }
        val prev = _current.value
        val activeSince = when {
            neonState == NeonCallState.ACTIVE && prev.activeSinceMs == 0L -> System.currentTimeMillis()
            neonState == NeonCallState.ACTIVE -> prev.activeSinceMs
            neonState == NeonCallState.HOLDING -> prev.activeSinceMs
            else -> 0L
        }
        _current.value = NeonCallInfo(
            state = neonState,
            number = number,
            callerDisplayName = details?.callerDisplayName?.takeIf { it.isNotBlank() },
            incoming = incoming,
            activeSinceMs = activeSince,
            muted = prev.muted,
            speakerOn = prev.speakerOn,
            canHold = details?.can(Call.Details.CAPABILITY_HOLD) == true,
            canMerge = details?.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true
        )
    }

    companion object {
        private val _current = MutableStateFlow(NeonCallInfo())
        val current: StateFlow<NeonCallInfo> = _current

        private var serviceRef: WeakReference<NeonInCallService>? = null
        private val call: Call? get() = serviceRef?.get()?.trackedCall

        fun answer() = runCatching { call?.answer(0) }
        fun reject() = runCatching { call?.reject(false, null) }
        fun hangup() = runCatching { call?.disconnect() }

        fun toggleHold() = runCatching {
            val c = call ?: return@runCatching
            if (c.state == Call.STATE_HOLDING) c.unhold() else c.hold()
        }

        fun toggleMute() = runCatching {
            val svc = serviceRef?.get() ?: return@runCatching
            svc.setMuted(!_current.value.muted)
        }

        fun toggleSpeaker() = runCatching {
            val svc = serviceRef?.get() ?: return@runCatching
            svc.setAudioRoute(
                if (_current.value.speakerOn) CallAudioState.ROUTE_EARPIECE
                else CallAudioState.ROUTE_SPEAKER
            )
        }

        fun playDtmf(digit: Char) = runCatching { call?.playDtmfTone(digit) }
        fun stopDtmf() = runCatching { call?.stopDtmfTone() }

        /** Сброс «завершённого» состояния — оверлей вызывает после короткой паузы. */
        fun clearIfDisconnected() {
            if (_current.value.state == NeonCallState.DISCONNECTED) {
                _current.value = NeonCallInfo()
            }
        }
    }
}
