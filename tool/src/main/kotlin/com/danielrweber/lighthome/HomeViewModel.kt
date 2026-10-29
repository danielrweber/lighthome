package com.danielrweber.lighthome

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HomeMode {
    Loading,
    Setup,
    EditAddress,
    EditToken,
    ConfirmForget,
    Devices,
    Detail,
    Problem,
}

internal enum class TokenStatus {
    Missing,
    Saved,
    ReplacementReady,
}

internal data class HomeUiState(
    val mode: HomeMode = HomeMode.Loading,
    val label: String = "LightHome",
    val devices: List<HaDevice> = emptyList(),
    val selectedId: String? = null,
    val message: String? = HaMessages.CHECKING,
    val errorModal: String? = null,
    val busy: Boolean = false,
    val setupAddress: String = "",
    val tokenStatus: TokenStatus = TokenStatus.Missing,
    val editorSession: Int = 0,
    val editorSeed: String = "",
    val canLeaveSetup: Boolean = false,
)

class HomeViewModel internal constructor(
    private val tokenStore: HaTokenStore,
    private val client: HaClient = HaClient(),
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    internal val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var saved: SavedConnection? = null
    private var draftToken: String? = null
    private var listKnown = false
    private var lastProblem: String? = null
    private var skipRefreshOnNextShow = true

    init {
        _uiState.update { it.copy(busy = true, mode = HomeMode.Loading, message = HaMessages.CHECKING) }
        viewModelScope.launch {
            try {
                when (val read = withContext(Dispatchers.IO) { tokenStore.read() }) {
                    HaTokenRead.Missing -> {
                        _uiState.value = HomeUiState(mode = HomeMode.Setup, message = null)
                    }
                    HaTokenRead.Unreadable -> {
                        _uiState.value = HomeUiState(
                            mode = HomeMode.Setup,
                            message = null,
                            errorModal = HaMessages.TOKEN_UNREADABLE,
                        )
                    }
                    is HaTokenRead.Ready -> {
                        saved = read.connection
                        rememberConnection(read.connection)
                        fetchDevices(read.connection, keepDetail = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = HomeUiState(
                    mode = HomeMode.Setup,
                    message = null,
                    errorModal = HaMessages.UNREADABLE,
                )
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (skipRefreshOnNextShow) {
            skipRefreshOnNextShow = false
            return
        }
        val mode = _uiState.value.mode
        if (mode == HomeMode.Devices || mode == HomeMode.Detail) {
            refresh()
        }
    }

    override fun onBackPressed(): Boolean = when (_uiState.value.mode) {
        HomeMode.EditAddress, HomeMode.EditToken -> {
            cancelEditor()
            true
        }
        HomeMode.ConfirmForget -> {
            cancelForget()
            true
        }
        HomeMode.Detail -> {
            closeDetail()
            true
        }
        HomeMode.Setup -> {
            if (_uiState.value.canLeaveSetup) {
                leaveSetup()
                true
            } else {
                false
            }
        }
        else -> false
    }

    fun openAddressEditor() {
        _uiState.update {
            it.copy(
                mode = HomeMode.EditAddress,
                editorSession = it.editorSession + 1,
                editorSeed = it.setupAddress,
                errorModal = null,
            )
        }
    }

    fun openTokenEditor() {
        _uiState.update {
            it.copy(
                mode = HomeMode.EditToken,
                editorSession = it.editorSession + 1,
                editorSeed = "",
                errorModal = null,
            )
        }
    }

    fun cancelEditor() {
        _uiState.update { it.copy(mode = HomeMode.Setup, editorSeed = "", errorModal = null) }
    }

    fun submitAddress(raw: CharSequence) {
        when (val parsed = parseHaBaseUrl(raw.toString())) {
            is HaUrlResult.Invalid -> _uiState.update {
                it.copy(mode = HomeMode.Setup, editorSeed = "", errorModal = parsed.message)
            }
            is HaUrlResult.Ok -> _uiState.update {
                it.copy(
                    mode = HomeMode.Setup,
                    setupAddress = parsed.endpoint.baseUrl,
                    label = parsed.endpoint.label,
                    editorSeed = "",
                    errorModal = null,
                )
            }
        }
    }

    fun submitToken(raw: CharSequence) {
        when (val parsed = normalizeToken(raw.toString())) {
            is TokenResult.Invalid -> _uiState.update {
                it.copy(mode = HomeMode.Setup, editorSeed = "", errorModal = parsed.message)
            }
            is TokenResult.Ok -> {
                draftToken = parsed.token
                _uiState.update {
                    it.copy(
                        mode = HomeMode.Setup,
                        tokenStatus = TokenStatus.ReplacementReady,
                        editorSeed = "",
                        errorModal = null,
                    )
                }
            }
        }
    }

    fun connect() {
        if (_uiState.value.busy) return
        val parsedUrl = parseHaBaseUrl(_uiState.value.setupAddress)
        if (parsedUrl is HaUrlResult.Invalid) {
            _uiState.update { it.copy(mode = HomeMode.Setup, errorModal = parsedUrl.message) }
            return
        }
        val endpoint = (parsedUrl as HaUrlResult.Ok).endpoint
        val token = draftToken ?: saved?.token
        if (token == null) {
            _uiState.update { it.copy(mode = HomeMode.Setup, errorModal = HaMessages.TOKEN_MISSING) }
            return
        }
        val parsedToken = normalizeToken(token)
        if (parsedToken is TokenResult.Invalid) {
            draftToken = null
            _uiState.update {
                it.copy(
                    mode = HomeMode.Setup,
                    tokenStatus = if (saved == null) TokenStatus.Missing else TokenStatus.Saved,
                    errorModal = parsedToken.message,
                )
            }
            return
        }
        val normalized = (parsedToken as TokenResult.Ok).token
        _uiState.update {
            it.copy(busy = true, mode = HomeMode.Loading, message = HaMessages.CHECKING, errorModal = null)
        }
        viewModelScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) { client.verify(endpoint, normalized) }
                if (verified !is HaCallResult.Ok) {
                    failConnect(messageFor(verified))
                    return@launch
                }
                val listed = withContext(Dispatchers.IO) { client.states(endpoint, normalized) }
                if (listed !is HaCallResult.Ok) {
                    failConnect(messageFor(listed))
                    return@launch
                }
                val wrote = withContext(Dispatchers.IO) { tokenStore.save(endpoint, normalized) }
                if (!wrote) {
                    draftToken = normalized
                    _uiState.update {
                        it.copy(
                            mode = HomeMode.Setup,
                            setupAddress = endpoint.baseUrl,
                            label = endpoint.label,
                            tokenStatus = TokenStatus.ReplacementReady,
                            canLeaveSetup = saved != null || listKnown,
                            errorModal = HaMessages.SAVE_FAILED,
                        )
                    }
                    return@launch
                }
                draftToken = null
                val connection = SavedConnection(endpoint, normalized)
                saved = connection
                publishDevices(connection, listed.value.toDevices(), keepDetail = false, selected = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failConnect(HaMessages.UNREADABLE)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun refresh() {
        val connection = saved ?: return
        if (_uiState.value.busy) return
        val keepDetail = _uiState.value.mode == HomeMode.Detail
        val showLoading = _uiState.value.mode == HomeMode.Problem
        _uiState.update {
            if (showLoading) {
                it.copy(busy = true, mode = HomeMode.Loading, message = HaMessages.CHECKING, errorModal = null)
            } else {
                it.copy(busy = true, errorModal = null)
            }
        }
        viewModelScope.launch {
            try {
                fetchDevices(connection, keepDetail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishFailure(HaMessages.UNREADABLE, keepDetail)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun openSetup() {
        val connection = saved
        _uiState.update {
            it.copy(
                mode = HomeMode.Setup,
                setupAddress = connection?.endpoint?.baseUrl ?: it.setupAddress,
                label = connection?.endpoint?.label ?: it.label,
                canLeaveSetup = connection != null || listKnown,
                errorModal = null,
                message = null,
            )
        }
    }

    fun leaveSetup() {
        val problem = lastProblem
        _uiState.update {
            when {
                listKnown -> it.copy(mode = HomeMode.Devices, errorModal = null, message = null)
                problem != null -> it.copy(mode = HomeMode.Problem, message = problem, errorModal = null)
                else -> it
            }
        }
    }

    fun askForget() {
        if (saved == null && draftToken == null && _uiState.value.setupAddress.isBlank()) return
        _uiState.update { it.copy(mode = HomeMode.ConfirmForget, errorModal = null) }
    }

    fun cancelForget() {
        _uiState.update { it.copy(mode = HomeMode.Setup, errorModal = null) }
    }

    fun forget() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val cleared = withContext(Dispatchers.IO) { tokenStore.clear() }
                if (!cleared) {
                    _uiState.update { it.copy(errorModal = HaMessages.CLEAR_FAILED) }
                    return@launch
                }
                saved = null
                draftToken = null
                listKnown = false
                lastProblem = null
                _uiState.value = HomeUiState(mode = HomeMode.Setup, message = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(errorModal = HaMessages.CLEAR_FAILED) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    fun activate(entityId: String) {
        val device = _uiState.value.devices.find { it.entityId == entityId } ?: return
        when (device.domain) {
            "light", "switch" -> {
                if (device.state == "unavailable") {
                    _uiState.update { it.copy(errorModal = HaMessages.UNAVAILABLE) }
                    return
                }
                callService(device.domain, "toggle", HaServiceBody(device.entityId), device.entityId)
            }
            "scene", "script" -> {
                if (device.state == "unavailable") {
                    _uiState.update { it.copy(errorModal = HaMessages.UNAVAILABLE) }
                    return
                }
                callService(device.domain, "turn_on", HaServiceBody(device.entityId), device.entityId)
            }
            "cover", "climate" -> _uiState.update {
                it.copy(mode = HomeMode.Detail, selectedId = entityId, errorModal = null)
            }
        }
    }

    fun closeDetail() {
        _uiState.update { it.copy(mode = HomeMode.Devices, selectedId = null, errorModal = null) }
    }

    fun openCover() = cover("open_cover")

    fun closeCover() = cover("close_cover")

    fun adjustClimate(direction: Int) {
        val device = selectedDevice() ?: return
        if (device.state == "unavailable") {
            _uiState.update { it.copy(errorModal = HaMessages.UNAVAILABLE) }
            return
        }
        val next = nextTarget(device, direction) ?: run {
            _uiState.update { it.copy(errorModal = HaMessages.NO_TEMPERATURE) }
            return
        }
        callService(
            "climate",
            "set_temperature",
            HaServiceBody(entityId = device.entityId, temperature = next),
            device.entityId,
        )
    }

    fun setHvacMode(mode: String) {
        val device = selectedDevice() ?: return
        if (device.state == "unavailable") {
            _uiState.update { it.copy(errorModal = HaMessages.UNAVAILABLE) }
            return
        }
        callService(
            "climate",
            "set_hvac_mode",
            HaServiceBody(entityId = device.entityId, hvacMode = mode),
            device.entityId,
        )
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }

    private fun cover(service: String) {
        val device = selectedDevice() ?: return
        if (device.state == "unavailable") {
            _uiState.update { it.copy(errorModal = HaMessages.UNAVAILABLE) }
            return
        }
        callService(device.domain, service, HaServiceBody(device.entityId), device.entityId)
    }

    private fun callService(domain: String, service: String, body: HaServiceBody, actedId: String) {
        val connection = saved ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, errorModal = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.call(connection.endpoint, connection.token, domain, service, body)
                }
                when (result) {
                    is HaCallResult.Ok -> {
                        val updates = result.value.toDevices()
                        val merged = if (updates.any { it.entityId == actedId }) {
                            mergeDevices(_uiState.value.devices, updates)
                        } else {
                            val listed = withContext(Dispatchers.IO) {
                                client.states(connection.endpoint, connection.token)
                            }
                            if (listed is HaCallResult.Ok) {
                                listed.value.toDevices()
                            } else {
                                _uiState.update { it.copy(errorModal = messageFor(listed)) }
                                mergeDevices(_uiState.value.devices, updates)
                            }
                        }
                        _uiState.update { state ->
                            val keepDetail = state.mode == HomeMode.Detail && state.selectedId == actedId
                            state.copy(
                                devices = merged,
                                mode = if (keepDetail) HomeMode.Detail else state.mode,
                                selectedId = if (keepDetail) actedId else state.selectedId,
                            )
                        }
                    }
                    else -> _uiState.update { it.copy(errorModal = messageFor(result)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(errorModal = HaMessages.UNREADABLE) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun fetchDevices(connection: SavedConnection, keepDetail: Boolean) {
        val selected = _uiState.value.selectedId
        val result = withContext(Dispatchers.IO) { client.states(connection.endpoint, connection.token) }
        when (result) {
            is HaCallResult.Ok -> publishDevices(connection, result.value.toDevices(), keepDetail, selected)
            else -> publishFailure(messageFor(result), keepDetail)
        }
    }

    private fun publishDevices(
        connection: SavedConnection,
        devices: List<HaDevice>,
        keepDetail: Boolean,
        selected: String?,
    ) {
        listKnown = true
        lastProblem = null
        saved = connection
        val modeNow = _uiState.value.mode
        val editing = isSetupFlow(modeNow)
        val showDetail = keepDetail && selected != null && devices.any { it.entityId == selected } && modeNow == HomeMode.Detail
        _uiState.update { state ->
            state.copy(
                devices = devices,
                label = connection.endpoint.label,
                setupAddress = connection.endpoint.baseUrl,
                tokenStatus = if (draftToken == null) TokenStatus.Saved else TokenStatus.ReplacementReady,
                canLeaveSetup = true,
                message = null,
                mode = when {
                    editing -> state.mode
                    showDetail -> HomeMode.Detail
                    else -> HomeMode.Devices
                },
                selectedId = when {
                    showDetail -> selected
                    editing -> state.selectedId
                    else -> null
                },
            )
        }
    }

    private fun publishFailure(message: String, keepDetail: Boolean) {
        lastProblem = message
        val modeNow = _uiState.value.mode
        val stayWithList = (keepDetail || modeNow == HomeMode.Devices || modeNow == HomeMode.Detail) &&
            _uiState.value.devices.isNotEmpty()
        _uiState.update {
            when {
                isSetupFlow(modeNow) || stayWithList -> it.copy(errorModal = message)
                else -> it.copy(mode = HomeMode.Problem, message = message, errorModal = null)
            }
        }
    }

    private fun failConnect(message: String) {
        lastProblem = message
        _uiState.update {
            it.copy(
                mode = HomeMode.Setup,
                errorModal = message,
                canLeaveSetup = saved != null || listKnown,
            )
        }
    }

    private fun rememberConnection(connection: SavedConnection) {
        _uiState.update {
            it.copy(
                label = connection.endpoint.label,
                setupAddress = connection.endpoint.baseUrl,
                tokenStatus = TokenStatus.Saved,
                canLeaveSetup = true,
            )
        }
    }

    private fun selectedDevice(): HaDevice? {
        val id = _uiState.value.selectedId ?: return null
        return _uiState.value.devices.find { it.entityId == id }
    }

    private fun isSetupFlow(mode: HomeMode): Boolean = when (mode) {
        HomeMode.Setup, HomeMode.EditAddress, HomeMode.EditToken, HomeMode.ConfirmForget -> true
        else -> false
    }
}
