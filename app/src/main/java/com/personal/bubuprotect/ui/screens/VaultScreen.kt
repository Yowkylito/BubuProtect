package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.R
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.EmptyVaultPane
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.components.KindFilterRow
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.NoMatchesPane
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.vm.VaultListState
import com.personal.bubuprotect.ui.vm.VaultUiState
import com.personal.bubuprotect.ui.vm.VaultViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel

@Composable
fun VaultRoute(
    onOpenEntry: (String) -> Unit,
    onAddEntry: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()
    val snackbarHostState = remember { SnackbarHostState() }

    // The ViewModel grants navigation after re-authenticating, rather than calling back from inside
    // the prompt coroutine - see VaultUiState.openGrantedFor.
    LaunchedEffect(state.openGrantedFor) {
        state.openGrantedFor?.let { id ->
            onOpenEntry(id)
            viewModel.consumeOpenGrant()
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    VaultScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onKindFilterChange = viewModel::onKindFilterChange,
        onOpenEntry = { item -> viewModel.requestOpen(item.id, item.label, gate) },
        onCopySecret = { item -> viewModel.copySecret(item.id, item.label, gate) },
        onAddEntry = onAddEntry,
        onLock = viewModel::lock,
        onToggleBiometricUnlock = { enable ->
            if (enable) viewModel.enableBiometricUnlock(gate) else viewModel.disableBiometricUnlock()
        },
        modifier = modifier
    )
}

/**
 * The vault list.
 *
 * Every state the list can be in gets its own pane - loading, empty vault, no matches, failure,
 * content - swapped through one [AnimatedContent]. Modelling them as
 * [com.personal.bubuprotect.ui.vm.VaultListState] rather than as `isLoading`/`isEmpty` booleans is
 * what makes that possible: there is no combination of flags that can render two panes or none.
 */
@Composable
fun VaultScreen(
    state: VaultUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onKindFilterChange: (ItemKind?) -> Unit,
    onOpenEntry: (VaultItem) -> Unit,
    onCopySecret: (VaultItem) -> Unit,
    onAddEntry: () -> Unit,
    onLock: () -> Unit,
    onToggleBiometricUnlock: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // derivedStateOf, not a plain read: firstVisibleItemIndex changes constantly while scrolling, and
    // reading it directly here would recompose the whole Scaffold on every frame of a fling. This
    // recomposes only on the two frames where the boolean actually flips.
    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VaultHeader(
                entryCount = state.totalCount,
                onLock = onLock,
                onOpenSettings = { settingsOpen = true }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddEntry,
                expanded = fabExpanded,
                shape = PillShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add a secret") }
            )
        }
    ) { innerPadding ->
        // Caps the column's width on a tablet or an unfolded foldable. A vault row stretched across
        // 1200dp puts the label and its copy button at opposite ends of the screen.
        ResponsiveContainer(modifier = Modifier.padding(innerPadding)) {
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            // The filter bar is pointless on an empty vault, and worse than pointless during the
            // first load, where it would appear before the thing it filters.
            AnimatedVisibility(
                visible = state.totalCount > 0,
                enter = fadeIn(tween(BubuMotion.MEDIUM)),
                exit = fadeOut(tween(BubuMotion.FAST))
            ) {
                KindFilterRow(
                    selected = state.kindFilter,
                    onSelect = onKindFilterChange,
                    counts = state.counts,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            AnimatedContent(
                targetState = state.list,
                transitionSpec = {
                    (fadeIn(tween(BubuMotion.MEDIUM)) +
                            scaleIn(tween(BubuMotion.MEDIUM), initialScale = 0.97f)) togetherWith
                            fadeOut(tween(BubuMotion.FAST))
                },
                contentKey = { it.paneKey() },
                label = "vaultPane",
                // weight, not fillMaxSize: the search field and filter row take their intrinsic
                // height first and the panes get exactly what is left.
                modifier = Modifier.weight(1f)
            ) { pane ->
                when (pane) {
                    VaultListState.Loading -> LoadingPane(label = "Opening the vault")

                    VaultListState.Empty -> EmptyVaultPane(onAddFirstEntry = onAddEntry)

                    is VaultListState.Failed -> ErrorPane(message = pane.reason)

                    is VaultListState.Content -> if (pane.items.isEmpty()) {
                        NoMatchesPane(query = state.query)
                    } else {
                        VaultList(
                            items = pane.items,
                            listState = listState,
                            onOpenEntry = onOpenEntry,
                            onCopySecret = onCopySecret
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        VaultSettingsSheet(
            biometricUnlockEnabled = state.biometricUnlockEnabled,
            canOfferBiometricUnlock = state.canOfferBiometricUnlock,
            entryCount = state.totalCount,
            onToggleBiometricUnlock = onToggleBiometricUnlock,
            onLock = {
                settingsOpen = false
                onLock()
            },
            onDismiss = { settingsOpen = false }
        )
    }
}

/**
 * Only the *kind* of pane keys the transition, not its contents.
 *
 * Without this, every keystroke in the search box produces a new `Content` instance and
 * [AnimatedContent] cross-fades the entire list on each character.
 */
private fun VaultListState.paneKey(): String = when (this) {
    VaultListState.Loading -> "loading"
    VaultListState.Empty -> "empty"
    is VaultListState.Failed -> "failed"
    is VaultListState.Content -> if (items.isEmpty()) "no-matches" else "content"
}

@Composable
private fun VaultHeader(
    entryCount: Int,
    onLock: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Bubu Protect",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // Animates so the count ticking up after a save is visible rather than a silent swap.
                AnimatedContent(
                    targetState = entryCount,
                    transitionSpec = {
                        fadeIn(tween(BubuMotion.FAST)) togetherWith fadeOut(tween(BubuMotion.FAST))
                    },
                    label = "entryCount"
                ) { count ->
                    Text(
                        text = when (count) {
                            0 -> "Nothing to guard yet"
                            1 -> "Guarding 1 secret"
                            else -> "Guarding $count secrets"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            BubuIconButton(
                icon = Icons.Filled.Lock,
                contentDescription = "Lock the vault now",
                onClick = onLock
            )
            BubuIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search the vault") },
        singleLine = true,
        shape = PillShape,
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = scaleIn(BubuMotion.Playful) + fadeIn(),
                exit = scaleOut(tween(BubuMotion.FAST)) + fadeOut()
            ) {
                BubuIconButton(
                    icon = Icons.Filled.Clear,
                    contentDescription = "Clear the search",
                    onClick = { onQueryChange("") }
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search,
            // The vault's labels are proper nouns; autocorrect fights them.
            autoCorrectEnabled = false
        )
    )
}

@Composable
private fun VaultList(
    items: List<VaultItem>,
    listState: LazyListState,
    onOpenEntry: (VaultItem) -> Unit,
    onCopySecret: (VaultItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items = items, key = { _, item -> item.id }) { index, item ->
            VaultRow(
                item = item,
                onOpen = { onOpenEntry(item) },
                onCopy = { onCopySecret(item) },
                modifier = Modifier
                    // animateItem handles insert, remove and reorder; the stagger is only for the
                    // first screenful, because a lazy list re-composes an item every time it scrolls
                    // back into view and a stagger there would look like the list was reloading.
                    .animateItem()
                    .then(
                        if (index in 0 until STAGGERED_ITEMS) {
                            Modifier.enterStaggered(index)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

private const val STAGGERED_ITEMS = 8

@Composable
private fun VaultRow(
    item: VaultItem,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val age = remember(item.updatedAt) { relativeAge(item.updatedAt) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Named for TalkBack: the row's own text only mentions the kind when there is no
            // subtitle to show instead, so without this a login and a card sound identical.
            KindBadge(kind = item.kind, contentDescription = item.kind.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        item.subtitle.takeIf { it.isNotBlank() } ?: item.kind.title,
                        age.takeIf { it.isNotEmpty() }
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BubuIconButton(
                icon = ImageVector.vectorResource(R.drawable.ic_copy),
                contentDescription = "Copy the secret in ${item.label}",
                onClick = onCopy
            )
        }
    }
}

@Preview(showBackground = true, name = "Vault with entries")
@Composable
private fun VaultScreenPreview() {
    val items = listOf(
        VaultItem("1", ItemKind.LOGIN, "Gmail", "bubu@example.com", updatedAt = 1),
        VaultItem("2", ItemKind.CARD, "Travel card", "R. Bear", updatedAt = 1),
        VaultItem("3", ItemKind.NOTE, "Where the spare key lives", "", updatedAt = 1),
        VaultItem("4", ItemKind.WIFI, "Home Wi-Fi", "DuduNet", updatedAt = 1)
    )
    BubuProtectTheme {
        VaultScreen(
            state = VaultUiState(
                list = VaultListState.Content(items, isFiltered = false),
                totalCount = items.size,
                counts = items.groupingBy { it.kind }.eachCount()
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onQueryChange = {},
            onKindFilterChange = {},
            onOpenEntry = {},
            onCopySecret = {},
            onAddEntry = {},
            onLock = {},
            onToggleBiometricUnlock = {}
        )
    }
}

@Preview(showBackground = true, name = "Empty vault")
@Composable
private fun VaultScreenEmptyPreview() {
    BubuProtectTheme {
        VaultScreen(
            state = VaultUiState(list = VaultListState.Empty),
            snackbarHostState = remember { SnackbarHostState() },
            onQueryChange = {},
            onKindFilterChange = {},
            onOpenEntry = {},
            onCopySecret = {},
            onAddEntry = {},
            onLock = {},
            onToggleBiometricUnlock = {}
        )
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun VaultScreenLoadingPreview() {
    BubuProtectTheme {
        VaultScreen(
            state = VaultUiState(list = VaultListState.Loading),
            snackbarHostState = remember { SnackbarHostState() },
            onQueryChange = {},
            onKindFilterChange = {},
            onOpenEntry = {},
            onCopySecret = {},
            onAddEntry = {},
            onLock = {},
            onToggleBiometricUnlock = {}
        )
    }
}
