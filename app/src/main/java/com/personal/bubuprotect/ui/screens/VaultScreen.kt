package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.R
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.ui.components.BreachBadge
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuSnackbarHost
import com.personal.bubuprotect.ui.components.EmptyVaultPane
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.components.KindFilterRow
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.NoMatchesPane
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.accent
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu
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
        onRetry = viewModel::retryLoad,
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
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // derivedStateOf, not a plain read: firstVisibleItemIndex changes constantly while scrolling, and
    // reading it directly here would recompose the whole vault on every frame of a fling. This
    // recomposes only on the two frames where the boolean actually flips.
    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    val showFab = state.list !is VaultListState.Empty &&
        state.list !is VaultListState.Loading

    Box(modifier.fillMaxSize().imePadding()) {
        // Caps the column's width on a tablet or an unfolded foldable. A vault row stretched across
        // 1200dp puts the label and its copy button at opposite ends of the screen.
        ResponsiveContainer {
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(horizontal = BubuSpacing.screen, vertical = BubuSpacing.xs).padding(top = 12.dp)
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
                    modifier = Modifier.padding(bottom = BubuSpacing.xxs)
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

                    is VaultListState.Failed -> ErrorPane(message = pane.reason, onRetry = onRetry)

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

        AnimatedVisibility(
            visible = showFab,
            enter = scaleIn(BubuMotion.Playful) + fadeIn(),
            exit = scaleOut(tween(BubuMotion.FAST)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(BubuSpacing.md)
        ) {
            ExtendedFloatingActionButton(
                onClick = onAddEntry,
                expanded = fabExpanded,
                modifier = Modifier.border(
                    1.dp,
                    MaterialTheme.bubu.cardBorder.copy(alpha = 0.62f),
                    PillShape
                ),
                shape = PillShape,
                containerColor = MaterialTheme.bubu.champagneContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = BubuElevation.floating,
                    pressedElevation = BubuElevation.hero
                ),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add a secret") }
            )
        }
        BubuSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (showFab) 72.dp else 0.dp)
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
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.bubu.cardBorder,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        ),
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
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = BubuSpacing.screen,
            end = BubuSpacing.screen,
            top = BubuSpacing.xxs,
            bottom = 96.dp + navBottom
        ),
        verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
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
    val accent = item.kind.accent()
    val shape = MaterialTheme.shapes.large

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics {
                    role = Role.Button
                    // The badge inside carries its own description, but a screen reader walking the
                    // list reads this merged label first - so a breached entry has to announce
                    // itself as one before the user decides whether to open it.
                    contentDescription = buildString {
                        append("Open ${item.label}, ${item.kind.title}")
                        if (item.breach.isBreached) append(", found in known breach data")
                    }
                }
                .clickable(onClick = onOpen)
        ) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(
                        accent.container,
                        RoundedCornerShape(
                            topStart = BubuSpacing.lg,
                            bottomStart = BubuSpacing.lg
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = BubuSpacing.sm,
                        end = BubuSpacing.xxs,
                        top = BubuSpacing.sm,
                        bottom = BubuSpacing.sm
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Named for TalkBack: the row's own text only mentions the kind when there is no
                // subtitle to show instead, so without this a login and a card sound identical.
                KindBadge(kind = item.kind, contentDescription = item.kind.title)
                Spacer(Modifier.width(BubuSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
                    ) {
                        Text(
                            text = listOfNotNull(
                                item.subtitle.takeIf { it.isNotBlank() } ?: item.kind.title,
                                age.takeIf { it.isNotEmpty() }
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // weight, so a long username ellipsizes and the badge keeps its width
                            // rather than the badge being pushed off the row on a narrow phone.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // Kinds whose secret is not a password get no badge at all - a permanent
                        // "Not checked" on every card and note would be noise about a check that is
                        // never going to run.
                        if (item.isBreachCheckable) {
                            BreachBadge(status = item.breach)
                        }
                    }
                }
                BubuIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_copy),
                    contentDescription = "Copy the secret in ${item.label}",
                    onClick = onCopy
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Vault with entries")
@Preview(showBackground = true, name = "Vault · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Vault · tablet", widthDp = 840, heightDp = 900)
@Composable
private fun VaultScreenPreview() {
    val items = listOf(
        VaultItem("1", ItemKind.LOGIN, "Gmail", "bubu@example.com", updatedAt = 1),
        VaultItem("2", ItemKind.CARD, "Travel card", "R. Bear", updatedAt = 1),
        VaultItem("3", ItemKind.NOTE, "Where the spare key lives", "", updatedAt = 1),
        VaultItem("4", ItemKind.WIFI, "Home Wi-Fi", "DuduNet", updatedAt = 1)
    )
    BubuProtectTheme {
        MainScreen(
            topBar = {
                MainHeader(entryCount = items.size, onLock = {}, onOpenSettings = {})
            }
        ) { innerPadding ->
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
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Preview(showBackground = true, name = "Empty vault")
@Composable
private fun VaultScreenEmptyPreview() {
    BubuProtectTheme {
        MainScreen(
            topBar = { MainHeader(entryCount = 0, onLock = {}, onOpenSettings = {}) }
        ) { innerPadding ->
            VaultScreen(
                state = VaultUiState(list = VaultListState.Empty),
                snackbarHostState = remember { SnackbarHostState() },
                onQueryChange = {},
                onKindFilterChange = {},
                onOpenEntry = {},
                onCopySecret = {},
                onAddEntry = {},
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Preview(showBackground = true, name = "Loading")
@Composable
private fun VaultScreenLoadingPreview() {
    BubuProtectTheme {
        MainScreen(
            topBar = { MainHeader(entryCount = 0, onLock = {}, onOpenSettings = {}) }
        ) { innerPadding ->
            VaultScreen(
                state = VaultUiState(list = VaultListState.Loading),
                snackbarHostState = remember { SnackbarHostState() },
                onQueryChange = {},
                onKindFilterChange = {},
                onOpenEntry = {},
                onCopySecret = {},
                onAddEntry = {},
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
