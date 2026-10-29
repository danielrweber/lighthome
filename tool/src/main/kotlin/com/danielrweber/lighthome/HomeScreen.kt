package com.danielrweber.lighthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(HaTokenStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.mode) {
                    HomeMode.Loading -> CenteredMessage(
                        title = "LightHome",
                        message = state.message ?: HaMessages.CHECKING,
                    )
                    HomeMode.Setup -> SetupContent(
                        state = state,
                        onBack = viewModel::leaveSetup,
                        onEditAddress = viewModel::openAddressEditor,
                        onEditToken = viewModel::openTokenEditor,
                        onScanToken = viewModel::openTokenScanner,
                        onForget = viewModel::askForget,
                        onConnect = viewModel::connect,
                    )
                    HomeMode.ScanToken -> TokenScanner(
                        onScanned = viewModel::submitScannedToken,
                        onBack = viewModel::cancelEditor,
                    )
                    HomeMode.EditAddress, HomeMode.EditToken -> {
                        key(state.editorSession) {
                            val editorState = rememberTextFieldState(
                                state.editorSeed,
                                TextRange(state.editorSeed.length),
                            )
                            val editingToken = state.mode == HomeMode.EditToken
                            LightTextInputEditor(
                                title = if (editingToken) HaMessages.TOKEN_LABEL else HaMessages.ADDRESS_LABEL,
                                state = editorState,
                                onSubmit = { text ->
                                    val copy = text.toString()
                                    editorState.edit { replace(0, length, "") }
                                    if (editingToken) viewModel.submitToken(copy) else viewModel.submitAddress(copy)
                                },
                                onBack = viewModel::cancelEditor,
                                keyboardOptionsFlow = keyboardOptions,
                                modifier = Modifier.fillMaxSize(),
                                submitLabel = "DONE",
                                singleLine = true,
                                initialCaps = false,
                            )
                        }
                    }
                    HomeMode.ConfirmForget -> ConfirmForgetContent(
                        onCancel = viewModel::cancelForget,
                        onForget = viewModel::forget,
                    )
                    HomeMode.Devices -> DeviceListContent(
                        state = state,
                        onSetup = viewModel::openSetup,
                        onRefresh = viewModel::refresh,
                        onActivate = viewModel::activate,
                        onChoose = viewModel::openChoose,
                        onFilter = viewModel::openFilter,
                    )
                    HomeMode.Choose -> ChooseContent(
                        state = state,
                        onBack = viewModel::showDevices,
                        onRefresh = viewModel::refresh,
                        onToggle = viewModel::toggleChosen,
                    )
                    HomeMode.Filter -> FilterContent(
                        state = state,
                        onBack = viewModel::showDevices,
                        onSelect = viewModel::setFilter,
                    )
                    HomeMode.Detail -> DetailContent(
                        state = state,
                        onBack = viewModel::closeDetail,
                        onOpen = viewModel::openCover,
                        onClose = viewModel::closeCover,
                        onLower = { viewModel.adjustClimate(-1) },
                        onHigher = { viewModel.adjustClimate(1) },
                        onMode = viewModel::setHvacMode,
                    )
                    HomeMode.Problem -> ProblemContent(
                        message = state.message ?: HaMessages.UNREACHABLE,
                        onSetup = viewModel::openSetup,
                        onRefresh = viewModel::refresh,
                    )
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupContent(
    state: HomeUiState,
    onBack: () -> Unit,
    onEditAddress: () -> Unit,
    onEditToken: () -> Unit,
    onScanToken: () -> Unit,
    onForget: () -> Unit,
    onConnect: () -> Unit,
) {
    Frame {
        LightTopBar(
            leftButton = if (state.canLeaveSetup) {
                LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                )
            } else {
                null
            },
            center = LightTopBarCenter.Text("LightHome"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = HaMessages.SETUP_TITLE,
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = HaMessages.CALLS_ONLY,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = HaMessages.TOKEN_STAYS,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = HaMessages.NO_LAN,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightTextField(
                label = HaMessages.ADDRESS_LABEL,
                value = state.setupAddress,
                placeholder = HaMessages.ADDRESS_PLACEHOLDER,
                onClick = onEditAddress,
            )
            LightTextField(
                label = HaMessages.TOKEN_LABEL,
                value = tokenFieldValue(state.tokenStatus),
                placeholder = HaMessages.TOKEN_PLACEHOLDER,
                onClick = onEditToken,
            )
            LightText(
                text = HaMessages.SCAN_TOKEN,
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp())
                    .lightClickable(onClick = onScanToken),
            )
            if (state.tokenStatus != TokenStatus.Missing || state.setupAddress.isNotBlank()) {
                LightText(
                    text = HaMessages.FORGET,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .padding(top = 1.5f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp())
                        .lightClickable(onClick = onForget),
                )
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CONNECT", onClick = onConnect),
            ),
        )
    }
}

@Composable
private fun TokenScanner(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingScan by remember { mutableStateOf<String?>(null) }
    LightQrCodeScanner(
        title = HaMessages.TOKEN_LABEL,
        onScanned = { pendingScan = it },
        onBack = onBack,
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    )
    LaunchedEffect(pendingScan) {
        val value = pendingScan ?: return@LaunchedEffect
        onScanned(value)
    }
}

@Composable
private fun DeviceListContent(
    state: HomeUiState,
    onSetup: () -> Unit,
    onRefresh: () -> Unit,
    onActivate: (String) -> Unit,
    onChoose: () -> Unit,
    onFilter: () -> Unit,
) {
    val visible = visibleDevices(state.devices, state.chosenIds, state.category)
    val grouped = sections(visible)
    val anyChosen = state.devices.any { it.entityId in state.chosenIds }
    Frame {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = onSetup,
                contentDescription = "Setup",
            ),
            center = LightTopBarCenter.Text(listStatusText(state.busy, state.listStatus)),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.REFRESH,
                onClick = onRefresh,
                contentDescription = "Refresh",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = if (state.category == null) HaMessages.SHOW_ALL else domainTitle(state.category),
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            when {
                state.devices.isEmpty() -> LightText(
                    text = HaMessages.EMPTY,
                    variant = LightTextVariant.Copy,
                )
                !anyChosen -> LightText(
                    text = HaMessages.PICK_DEVICES,
                    variant = LightTextVariant.Copy,
                )
                grouped.isEmpty() && state.category != null -> LightText(
                    text = HaMessages.filterEmpty(state.category),
                    variant = LightTextVariant.Copy,
                )
                grouped.isEmpty() -> LightText(
                    text = HaMessages.PICK_DEVICES,
                    variant = LightTextVariant.Copy,
                )
                else -> grouped.forEach { section ->
                    if (state.category == null) {
                        LightText(
                            text = section.title,
                            variant = LightTextVariant.Subheading,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    section.devices.forEach { device ->
                        DeviceRow(device.name, device.statusLabel) { onActivate(device.entityId) }
                    }
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CHOOSE", onClick = onChoose),
                LightBarButton.Text(text = "SHOW", onClick = onFilter),
            ),
        )
    }
}

@Composable
private fun ChooseContent(
    state: HomeUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
) {
    val grouped = sections(state.devices)
    Frame {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Choose"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.REFRESH,
                onClick = onRefresh,
                contentDescription = "Refresh",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = HaMessages.CHOOSE_HINT,
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (grouped.isEmpty()) {
                LightText(text = HaMessages.EMPTY, variant = LightTextVariant.Copy)
            } else {
                grouped.forEach { section ->
                    LightText(
                        text = section.title,
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
                    )
                    section.devices.forEach { device ->
                        val detail = if (device.entityId in state.chosenIds) {
                            HaMessages.ON_LIST
                        } else {
                            HaMessages.NOT_ON_LIST
                        }
                        DeviceRow(device.name, detail) { onToggle(device.entityId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterContent(
    state: HomeUiState,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    Frame {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Show"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            filterChoices().forEach { (category, label) ->
                val current = category == state.category
                val text = if (current) "$label · now" else label
                ActionRow(text) { onSelect(category) }
            }
        }
    }
}

@Composable
private fun DeviceRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy)
        LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
    }
}

@Composable
private fun DetailContent(
    state: HomeUiState,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onLower: () -> Unit,
    onHigher: () -> Unit,
    onMode: (String) -> Unit,
) {
    val device = state.devices.find { it.entityId == state.selectedId && it.entityId in state.chosenIds }
    if (device == null) {
        ProblemContent(
            message = HaMessages.UNAVAILABLE,
            onSetup = onBack,
            onRefresh = onBack,
        )
        return
    }
    Frame {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(device.name),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            when (device.domain) {
                "cover" -> CoverDetail(device, onOpen, onClose)
                "climate" -> ClimateDetail(device, onMode)
                else -> LightText(text = device.statusLabel, variant = LightTextVariant.Heading)
            }
        }
        if (device.domain == "climate") {
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "LOWER", onClick = onLower),
                    LightBarButton.Text(text = "HIGHER", onClick = onHigher),
                ),
            )
        }
    }
}

@Composable
private fun CoverDetail(
    device: HaDevice,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    LightText(
        text = device.statusLabel,
        variant = LightTextVariant.Heading,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
    ActionRow("Open", onOpen)
    ActionRow("Close", onClose)
}

@Composable
private fun ClimateDetail(
    device: HaDevice,
    onMode: (String) -> Unit,
) {
    LightText(text = "Now", variant = LightTextVariant.Detail)
    LightText(
        text = formatTemperature(device.currentTemperature, device.temperatureUnit),
        variant = LightTextVariant.Heading,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
    LightText(text = "Set to", variant = LightTextVariant.Detail)
    LightText(
        text = formatTemperature(device.targetTemperature, device.temperatureUnit),
        variant = LightTextVariant.Heading,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
    LightText(
        text = wordLabel(device.state),
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
    if (device.hvacModes.isEmpty()) {
        LightText(text = HaMessages.NO_MODES, variant = LightTextVariant.Detail)
    } else {
        device.hvacModes.forEach { mode ->
            val label = if (device.state == mode) "${wordLabel(mode)} · now" else wordLabel(mode)
            ActionRow(label) { onMode(mode) }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 1f.gridUnitsAsDp()),
    )
}

@Composable
private fun ConfirmForgetContent(
    onCancel: () -> Unit,
    onForget: () -> Unit,
) {
    Frame {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onCancel,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(HaMessages.FORGET),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = HaMessages.FORGET_BODY,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CANCEL", onClick = onCancel),
                LightBarButton.Text(text = "FORGET", onClick = onForget),
            ),
        )
    }
}

@Composable
private fun ProblemContent(
    message: String,
    onSetup: () -> Unit,
    onRefresh: () -> Unit,
) {
    Frame {
        LightTopBar(center = LightTopBarCenter.Text("LightHome"))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.SETTINGS,
                    onClick = onSetup,
                    contentDescription = "Setup",
                ),
                LightBarButton.Text(text = "REFRESH", onClick = onRefresh),
            ),
        )
    }
}

@Composable
private fun CenteredMessage(title: String, message: String) {
    Frame {
        LightTopBar(center = LightTopBarCenter.Text(title))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Frame(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), content = content)
}

private fun tokenFieldValue(status: TokenStatus): String = when (status) {
    TokenStatus.Missing -> ""
    TokenStatus.Saved -> HaMessages.TOKEN_SAVED
    TokenStatus.ReplacementReady -> HaMessages.TOKEN_READY
}
