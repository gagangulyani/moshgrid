package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.luminance
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetCenterTopBar
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.colorForPeer
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


/**
 * Sheet components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshPeerListSheet(
    isPresented: Boolean,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onShowVerification: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val conversationStoreState by viewModel.conversationStoreState.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()
    val geohashPeopleCount = geohashPeople.size
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val wifiAwarePeerIDs = remember(wifiAwareConnected) { wifiAwareConnected.keys.toSet() }
    val directPeerIdentityIDs = remember(peerDirect) {
        peerDirect
            .asSequence()
            .filter { (_, isDirect) -> isDirect }
            .mapTo(mutableSetOf()) { (peerID, _) -> peerID.lowercase() }
    }
    val wifiAwareIdentityIDs = remember(wifiAwarePeerIDs) {
        wifiAwarePeerIDs.mapTo(mutableSetOf()) { it.lowercase() }
    }
    val conversationIdentityAliases = remember(conversations) {
        conversations
            .flatMapTo(mutableSetOf()) { it.identityAliases }
    }
    val visibleConnectedPeers = connectedPeers.filterNot { peerID ->
        val aliases = runCatching {
            ContactDirectory.aliasesForConversation(peerID)
        }.getOrDefault(setOf(peerID))
        aliases.any { it.lowercase() in conversationIdentityAliases }
    }
    var pendingConversationDelete by remember {
        mutableStateOf<ConversationSummary?>(null)
    }
    var conversationQuery by rememberSaveable { mutableStateOf("") }
    val filteredConversations = remember(conversations, conversationQuery) {
        val query = conversationQuery.trim()
        if (query.isEmpty()) conversations else conversations.filter { conversation ->
            conversation.displayName.contains(query, ignoreCase = true) ||
                conversation.latestMessagePreview.contains(query, ignoreCase = true) ||
                conversation.draft?.contains(query, ignoreCase = true) == true
        }
    }
    val onlineConversations = remember(filteredConversations) {
        filteredConversations.filter(ConversationSummary::isConnected)
    }
    val offlineConversations = remember(filteredConversations) {
        filteredConversations.filterNot(ConversationSummary::isConnected)
    }
    val sheetScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Scroll state for animated top bar
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp)
                ) {
                    val peopleCount = when (selectedLocationChannel) {
                        is ChannelID.Location -> geohashPeopleCount
                        else -> visibleConnectedPeers.count { it != viewModel.myPeerID }
                    }

                    item(key = "private_conversations_header") {
                        SheetIconSectionHeader(
                            iconRes = R.drawable.ic_spec_envelope,
                            title = stringResource(R.string.conversations),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (conversations.size >= CONVERSATION_SEARCH_THRESHOLD) {
                        item(key = "private_conversations_search") {
                            OutlinedTextField(
                                value = conversationQuery,
                                onValueChange = { conversationQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding)
                                    .padding(top = 10.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = BitchatFontFamily
                                ),
                                placeholder = {
                                    Text(
                                        stringResource(R.string.search_conversations),
                                        fontFamily = BitchatFontFamily
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null)
                                },
                                trailingIcon = if (conversationQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { conversationQuery = "" }) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = stringResource(R.string.clear)
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    when {
                        conversationStoreState is
                            com.bitchat.android.services.ConversationStoreState.Loading &&
                            conversations.isEmpty() -> {
                            item(key = "private_conversations_loading") {
                                ConversationSectionStatus(
                                    icon = { CircularProgressIndicator(Modifier.size(20.dp)) },
                                    text = stringResource(R.string.loading_conversations)
                                )
                            }
                        }

                        conversationStoreState is
                            com.bitchat.android.services.ConversationStoreState.Error &&
                            conversations.isEmpty() -> {
                            item(key = "private_conversations_error") {
                                ConversationSectionStatus(
                                    icon = {
                                        Icon(
                                            Icons.Outlined.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    text = stringResource(R.string.conversation_storage_error)
                                )
                            }
                        }

                        conversations.isEmpty() -> {
                            item(key = "private_conversations_empty") {
                                ConversationSectionStatus(
                                    icon = {
                                        Icon(
                                            painterResource(R.drawable.ic_spec_envelope),
                                            contentDescription = null
                                        )
                                    },
                                    text = stringResource(R.string.no_conversations_yet)
                                )
                            }
                        }

                        filteredConversations.isEmpty() -> {
                            item(key = "private_conversations_no_results") {
                                ConversationSectionStatus(
                                    icon = {
                                        Icon(Icons.Outlined.SearchOff, contentDescription = null)
                                    },
                                    text = stringResource(R.string.no_conversation_results)
                                )
                            }
                        }
                    }

                    if (onlineConversations.isNotEmpty()) {
                        item(key = "private_conversations_online_label") {
                            ConversationGroupLabel(
                                text = stringResource(R.string.online_conversations)
                            )
                        }
                        itemsIndexed(
                            items = onlineConversations,
                            key = { _, conversation ->
                                "conversation:${conversation.conversationID}"
                            }
                        ) { index, conversation ->
                            ConversationSwipeItem(
                                conversation = conversation,
                                directPeerIdentityIDs = directPeerIdentityIDs,
                                wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                                viewModel = viewModel,
                                isFirst = index == 0,
                                isLast = index == onlineConversations.lastIndex,
                                onPrivateChatStart = { conversationID ->
                                    viewModel.showPrivateChatSheet(conversationID)
                                    onDismiss()
                                },
                                onDeleteRequested = { pendingConversationDelete = it },
                                onReadStateRequested = { item, isRead ->
                                    sheetScope.launch {
                                        viewModel.setConversationRead(item.conversationID, isRead)
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    if (offlineConversations.isNotEmpty()) {
                        item(key = "private_conversations_offline_label") {
                            ConversationGroupLabel(
                                text = stringResource(R.string.offline_conversations)
                            )
                        }
                        itemsIndexed(
                            items = offlineConversations,
                            key = { _, conversation ->
                                "conversation:${conversation.conversationID}"
                            }
                        ) { index, conversation ->
                            ConversationSwipeItem(
                                conversation = conversation,
                                directPeerIdentityIDs = directPeerIdentityIDs,
                                wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                                viewModel = viewModel,
                                isFirst = index == 0,
                                isLast = index == offlineConversations.lastIndex,
                                onPrivateChatStart = { conversationID ->
                                    viewModel.showPrivateChatSheet(conversationID)
                                    onDismiss()
                                },
                                onDeleteRequested = { pendingConversationDelete = it },
                                onReadStateRequested = { item, isRead ->
                                    sheetScope.launch {
                                        viewModel.setConversationRead(item.conversationID, isRead)
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    // Channels section
                    if (joinedChannels.isNotEmpty()) {
                        item(key = "channels_section") {
                            Column {
                                SheetIconSectionHeader(
                                    iconRes = R.drawable.ic_spec_chat_bubbles,
                                    title = stringResource(R.string.channels),
                                    modifier = Modifier.padding(
                                        top = if (conversations.isNotEmpty()) 20.dp else 8.dp
                                    )
                                )
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding)
                                        .padding(top = 10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column {
                                        joinedChannels.toList().forEachIndexed { index, channel ->
                                            if (index > 0) SheetCardDivider()
                                            val isSelected = channel == currentChannel
                                            val unreadCount = unreadChannelMessages[channel] ?: 0
                                            ChannelRow(
                                                channel = channel,
                                                isSelected = isSelected,
                                                unreadCount = unreadCount,
                                                colorScheme = colorScheme,
                                                onChannelClick = {
                                                    if (channel.startsWith("@")) {
                                                        val peerName = channel.removePrefix("@")
                                                        val peerID =
                                                            peerNicknames.entries.firstOrNull { it.value == peerName }?.key
                                                        if (peerID != null) {
                                                            viewModel.showPrivateChatSheet(peerID)
                                                            onDismiss()
                                                        }
                                                    } else {
                                                        viewModel.switchToChannel(channel)
                                                        onDismiss()
                                                    }
                                                },
                                                onLeaveChannel = {
                                                    viewModel.leaveChannel(channel)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // People / geohash participants
                    item(key = "people_section") {
                        when (selectedLocationChannel) {
                            is ChannelID.Location -> {
                                GeohashPeopleList(
                                    viewModel = viewModel,
                                    onTapPerson = onDismiss,
                                    excludedIdentityAliases = conversationIdentityAliases,
                                    modifier = Modifier.padding(
                                        top = if (
                                            joinedChannels.isNotEmpty() ||
                                            conversations.isNotEmpty()
                                        ) 20.dp else 8.dp
                                    )
                                )
                            }

                            else -> {
                                PeopleSection(
                                    modifier = Modifier.padding(
                                        top = if (
                                            joinedChannels.isNotEmpty() ||
                                            conversations.isNotEmpty()
                                        ) 20.dp else 8.dp
                                    ),
                                    connectedPeers = visibleConnectedPeers,
                                    peerNicknames = peerNicknames,
                                    peerRSSI = peerRSSI,
                                    nickname = nickname,
                                    colorScheme = colorScheme,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    wifiAwarePeerIDs = wifiAwarePeerIDs,
                                    peopleCount = peopleCount,
                                    excludedIdentityAliases = conversationIdentityAliases,
                                    viewModel = viewModel,
                                    onPrivateChatStart = { peerID ->
                                        viewModel.showPrivateChatSheet(peerID)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }

                // TopBar (animated)
                BitchatSheetTopBar(
                    title = {
                        BitchatSheetTitle(text = stringResource(id = R.string.your_network))
                    },
                    backgroundAlpha = topBarAlpha,
                    actions = {
                        if (selectedLocationChannel !is ChannelID.Location) {
                            IconButton(
                                onClick = onShowVerification,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCode,
                                    contentDescription = stringResource(R.string.verify_title),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(HeaderIconSize)
                                )
                            }
                        }
                    },
                    onClose = onDismiss,
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }

        pendingConversationDelete?.let { conversation ->
            ConversationDeleteDialog(
                conversation = conversation,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                scope = sheetScope,
                onDismiss = { pendingConversationDelete = null }
            )
        }
    }
}

/**
 * MoshGrid Chats list, shown inline on the Chats tab (public chat is off, so this replaces the
 * empty public timeline). Same data, rows, swipe actions and delete flow as the network sheet.
 */
@Composable
internal fun ChatsListPane(
    viewModel: ChatViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val conversationStoreState by viewModel.conversationStoreState.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val directPeerIdentityIDs = remember(peerDirect) {
        peerDirect.asSequence().filter { (_, isDirect) -> isDirect }
            .mapTo(mutableSetOf()) { (peerID, _) -> peerID.lowercase() }
    }
    val wifiAwareIdentityIDs = remember(wifiAwareConnected) {
        wifiAwareConnected.keys.mapTo(mutableSetOf()) { it.lowercase() }
    }
    var pendingConversationDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(conversations, query) {
        val q = query.trim()
        if (q.isEmpty()) conversations else conversations.filter { c ->
            c.displayName.contains(q, ignoreCase = true) ||
                c.latestMessagePreview.contains(q, ignoreCase = true) ||
                c.draft?.contains(q, ignoreCase = true) == true
        }
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.background(colorScheme.background)) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            if (conversations.isNotEmpty()) {
                item(key = "chats_search") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(colorScheme.surface)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text("Search chats", fontSize = 16.sp, color = colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontFamily = BitchatFontFamily
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "Search chats" }
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    }
                }
            }

            when {
                conversationStoreState is com.bitchat.android.services.ConversationStoreState.Loading &&
                    conversations.isEmpty() -> item(key = "chats_loading") {
                    ConversationSectionStatus(
                        icon = { CircularProgressIndicator(Modifier.size(20.dp)) },
                        text = stringResource(R.string.loading_conversations)
                    )
                }

                conversationStoreState is com.bitchat.android.services.ConversationStoreState.Error &&
                    conversations.isEmpty() -> item(key = "chats_error") {
                    ConversationSectionStatus(
                        icon = {
                            Icon(Icons.Outlined.Warning, contentDescription = null, tint = colorScheme.error)
                        },
                        text = stringResource(R.string.conversation_storage_error)
                    )
                }

                conversations.isEmpty() -> item(key = "chats_empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_spec_envelope),
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text("No chats yet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                        Text(
                            "Open a friend from the Friends tab to say hi.",
                            fontSize = 16.sp,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.showVerificationSheet() },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary
                            )
                        ) {
                            Text("Add a friend", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                filtered.isEmpty() -> item(key = "chats_no_results") {
                    ConversationSectionStatus(
                        icon = { Icon(Icons.Outlined.SearchOff, contentDescription = null) },
                        text = stringResource(R.string.no_conversation_results)
                    )
                }
            }

            itemsIndexed(
                items = filtered,
                key = { _, c -> "conversation:${c.conversationID}" }
            ) { index, conversation ->
                ConversationSwipeItem(
                    conversation = conversation,
                    directPeerIdentityIDs = directPeerIdentityIDs,
                    wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                    viewModel = viewModel,
                    isFirst = index == 0,
                    isLast = index == filtered.lastIndex,
                    onPrivateChatStart = { conversationID -> viewModel.showPrivateChatSheet(conversationID) },
                    onDeleteRequested = { pendingConversationDelete = it },
                    onReadStateRequested = { item, isRead ->
                        scope.launch { viewModel.setConversationRead(item.conversationID, isRead) }
                    },
                    modifier = Modifier.animateItem(),
                    flat = true
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    pendingConversationDelete?.let { conversation ->
        ConversationDeleteDialog(
            conversation = conversation,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            scope = scope,
            onDismiss = { pendingConversationDelete = null }
        )
    }
}

/** Icon size for trailing actions on peer rows (matches settings glyph scale). */
private val PeerRowIconSize = 22.dp
private const val CONVERSATION_SEARCH_THRESHOLD = 8

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit,
) {
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChannelClick)
            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(SheetRowLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(SheetRowSelectedDot)
                        .background(colorScheme.primary, CircleShape)
                )
            } else if (unreadCount > 0) {
                UnreadBadge(count = unreadCount, colorScheme = colorScheme)
            } else {
                Text(
                    text = "#",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.width(SheetRowLeadingGutter))

        Text(
            text = channel,
            fontFamily = BitchatFontFamily,
            fontSize = 14.sp,
            color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        CloseButton(onClick = onLeaveChannel)
    }
}



@Composable
fun PeopleSection(
    modifier: Modifier  = Modifier,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    wifiAwarePeerIDs: Set<String> = emptySet(),
    peopleCount: Int = 0,
    excludedIdentityAliases: Set<String> = emptySet(),
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    val context = LocalContext.current
    val identityStateManager = remember(context) {
        SecureIdentityStateManager(context.applicationContext)
    }

    val palette = LocalBitchatPalette.current

    Column(modifier = modifier) {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_people,
            title = stringResource(R.string.people_count_title, peopleCount)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
                .padding(top = 10.dp),
            color = colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                if (connectedPeers.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.no_one_connected),
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical)
                    )
                }

        // Observe reactive state for favorites and fingerprints
        val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
        val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
        val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
        val peerFavoritedUs by viewModel.peerFavoritedUs.collectAsStateWithLifecycle()
        val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
        val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

        // Reactive favorite computation for all peers
        val peerFavoriteStates = remember(favoritePeers, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                val fingerprint = peerFingerprints[peerID]
                if (fingerprint != null) favoritePeers.contains(fingerprint) else viewModel.isFavorite(peerID)
            }
        }

        // Same "they favorited us" signal the private-chat header uses for orange outline stars.
        val peerTheyFavoritedUsStates = remember(peerFavoritedUs, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                val fingerprint = peerFingerprints[peerID]
                if (fingerprint != null && peerFavoritedUs.contains(fingerprint)) {
                    true
                } else {
                    try {
                        FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.theyFavoritedUs == true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }

        val peerVerifiedStates = remember(verifiedFingerprints, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                viewModel.isPeerVerified(peerID, verifiedFingerprints)
            }
        }

        // Build mapping of connected peerID -> Noise key hex to unify with offline favorites.
        val noiseHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
            try {
                viewModel.getMeshPeerInfo(pid)?.noisePublicKey?.hexEncodedString()
                    ?: identityStateManager.getCachedNoiseKey(pid)
            } catch (_: Exception) { null }
        }.filterValues { it != null }.mapValues { it.value!! }

        val nostrHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
            try {
                FavoritesPersistenceService.shared
                    .findNostrPubkeyForPeerID(pid)
                    ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
            } catch (_: Exception) { null }
        }.filterValues { it != null }.mapValues { it.value!! }

        val connectedNoiseHexes = noiseHexByPeerID.values.map { it.lowercase() }.toSet()
        val connectedNostrHexes = nostrHexByPeerID.values.map { it.lowercase() }.toSet()

        fun isFavoriteMappedToConnected(favorite: FavoriteRelationship): Boolean {
            val noiseHex = ContactIdentityResolver.noiseKeyHex(favorite.peerNoisePublicKey).lowercase()
            if (connectedNoiseHexes.contains(noiseHex)) return true

            val nostrHex = favorite.peerNostrPublicKey
                ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
                ?.lowercase()
            return nostrHex != null && connectedNostrHexes.contains(nostrHex)
        }

        Log.d("SidebarComponents", "Recomposing with ${favoritePeers.size} favorites, peer states: $peerFavoriteStates")

        // Smart sorting: unread DMs first, then by most recent DM, then favorites, then alphabetical
        val sortedPeers = connectedPeers.sortedWith(
            compareBy<String> { !hasUnreadPrivateMessages.contains(it) } // Unread DM senders first
            .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L } // Most recent DM (convert Date to Long)
            .thenBy { !(peerFavoriteStates[it] ?: false) } // Favorites first
            .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() } // Alphabetical
        )
        
        // Build a map of base name counts across all people shown in the list (connected + offline + nostr)
        // Helper to compute display name used for a given key
        fun computeDisplayNameForPeerId(key: String): String {
            return if (key == nickname) "You" else (com.bitchat.android.mosh.MoshRuntime.friendNameFor(key) ?: peerNicknames[key] ?: (privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
        }

        val baseNameCounts = mutableMapOf<String, Int>()

        // Connected peers
        sortedPeers.forEach { pid ->
            val dn = computeDisplayNameForPeerId(pid)
            val (b, _) = splitSuffix(dn)
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }

        // Offline favorites (exclude ones mapped to connected)
        val offlineFavorites = FavoritesPersistenceService.shared.getOurFavorites()
        offlineFavorites.forEach { fav ->
            val favPeerID = ContactIdentityResolver.noiseKeyHex(fav.peerNoisePublicKey)
            if (
                favPeerID.lowercase() !in excludedIdentityAliases &&
                !isFavoriteMappedToConnected(fav)
            ) {
                val dn = com.bitchat.android.mosh.MoshRuntime.friendNameFor(favPeerID) ?: peerNicknames[favPeerID] ?: fav.peerNickname
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }
        }

        // Every row this card will show, in final order, so the animated list can key on identity
        // and animate reordering. Offline favourites are appended after the connected peers.
        // Collected once for the whole card rather than once per row.
        val directMap by viewModel.peerDirect.collectAsStateWithLifecycle()

        val offlineFavoriteRows = offlineFavorites.filterNot { favorite ->
            val favoriteNoiseKey = ContactIdentityResolver.noiseKeyHex(
                favorite.peerNoisePublicKey
            )
            favoriteNoiseKey.lowercase() in excludedIdentityAliases ||
                isFavoriteMappedToConnected(favorite)
        }
        val rowKeys: List<String> = sortedPeers +
            offlineFavoriteRows.map { ContactIdentityResolver.noiseKeyHex(it.peerNoisePublicKey) }

        AnimatedRowColumn(items = rowKeys, key = { it }) { rowIndex, rowKey ->
        Column {
        if (rowIndex > 0) SheetCardDivider()
        val connectedPeerForRow = sortedPeers.firstOrNull { it == rowKey }
        if (connectedPeerForRow != null) {
            val peerID = connectedPeerForRow
            val conversationID = ContactDirectory.canonicalConversationId(peerID)
            val isFavorite = peerFavoriteStates[peerID] ?: false
            val theyFavoritedUs = peerTheyFavoritedUsStates[peerID] ?: false
            val isVerified = peerVerifiedStates[peerID] ?: false
            // fingerprint and favorite relationship resolution not needed here; UI will show Nostr globe for appended offline favorites below

            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = hasUnreadPrivateMessages.contains(conversationID) || hasUnreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) hasUnreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            val combinedUnreadCount = (
                privateChats[conversationID]?.count { msg -> msg.sender != nickname && meshUnread } ?: 0
            ) + (
                if (noiseHex != null) privateChats[noiseHex]?.count { msg -> msg.sender != nickname && nostrUnread } ?: 0 else 0
            )

            val displayName = if (peerID == nickname) "You" else (com.bitchat.android.mosh.MoshRuntime.friendNameFor(peerID) ?: peerNicknames[peerID] ?: (privateChats[peerID]?.lastOrNull()?.sender ?: peerID.take(12)))
            val (bName, _) = splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isDirectLive = directMap[peerID] ?: try { viewModel.getMeshPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isWifiAware = peerID in wifiAwarePeerIDs,
                isConnected = true,
                isSelected = conversationID == selectedPrivatePeer || peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                theyFavoritedUs = theyFavoritedUs,
                isVerified = isVerified,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(peerID) },
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash
            )
        } else {
            // Offline favourite: still worth showing, reachable over Nostr.
            val fav = offlineFavoriteRows.first {
                ContactIdentityResolver.noiseKeyHex(it.peerNoisePublicKey) == rowKey
            }
            val favPeerID = rowKey

            val nostrConvKey: String? = try {
                FavoritesPersistenceService.shared.findNostrPubkey(fav.peerNoisePublicKey)
                    ?.let { ContactIdentityResolver.nostrAliasForPubkey(it) }
            } catch (_: Exception) { null }

            val conversationID = ContactDirectory.canonicalConversationId(favPeerID)
            val hasUnread = hasUnreadPrivateMessages.contains(conversationID) ||
                hasUnreadPrivateMessages.contains(favPeerID) ||
                (nostrConvKey != null && hasUnreadPrivateMessages.contains(nostrConvKey))

            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = com.bitchat.android.mosh.MoshRuntime.friendNameFor(favPeerID) ?: peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isVerified = viewModel.isNoisePublicKeyVerified(fav.peerNoisePublicKey, verifiedFingerprints)

            val unreadCount = (
                privateChats[conversationID]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(conversationID) } ?: 0
            ) + (
                if (nostrConvKey != null) privateChats[nostrConvKey]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0
            )

            PeerItem(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isConnected = false,
                isSelected = conversationID == selectedPrivatePeer || (mappedConnectedPeerID ?: favPeerID) == selectedPrivatePeer,
                isFavorite = true,
                theyFavoritedUs = fav.theyFavoritedUs,
                isVerified = isVerified,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(mappedConnectedPeerID ?: favPeerID) },
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = com.bitchat.android.mosh.MoshFeatures.GLOBE && (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash
            )
        }
        }
        }
            }
        }

    }
}

@Composable
private fun ConversationSectionStatus(
    icon: @Composable () -> Unit,
    text: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = AboutCardShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = BitchatFontFamily
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationGroupLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding + 4.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSwipeItem(
    conversation: ConversationSummary,
    directPeerIdentityIDs: Set<String>,
    wifiAwareIdentityIDs: Set<String>,
    viewModel: ChatViewModel,
    isFirst: Boolean,
    isLast: Boolean,
    onPrivateChatStart: (String) -> Unit,
    onDeleteRequested: (ConversationSummary) -> Unit,
    onReadStateRequested: (ConversationSummary, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** MoshGrid: the inline Chats list sits flat on the screen background, not in a card. */
    flat: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current
    val deleteDescription = stringResource(R.string.delete_conversation_action)
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFavoritedUs by viewModel.peerFavoritedUs.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val favoriteTargetID = conversation.connectedPeerID ?: conversation.conversationID
    val favoriteRelationship = remember(
        conversation.identityAliases,
        favoritePeers,
        peerFavoritedUs
    ) {
        conversation.identityAliases
            .asSequence()
            .mapNotNull { alias ->
                runCatching {
                    FavoritesPersistenceService.shared.getFavoriteStatus(alias)
                }.getOrNull()
            }
            .firstOrNull()
    }
    val fingerprint = conversation.connectedPeerID
        ?.let(peerFingerprints::get)
        ?: conversation.identityAliases
            .asSequence()
            .mapNotNull(peerFingerprints::get)
            .firstOrNull()
        ?: ContactIdentityResolver.fingerprintFromContactConversationId(
            conversation.conversationID
        )
        ?: favoriteRelationship?.peerNoisePublicKey?.let {
            ContactIdentityResolver.fingerprintHex(it)
        }
    val isFavorite = if (fingerprint != null) {
        fingerprint in favoritePeers
    } else {
        viewModel.isFavorite(favoriteTargetID)
    }
    val theyFavoritedUs =
        (fingerprint != null && fingerprint in peerFavoritedUs) ||
            favoriteRelationship?.theyFavoritedUs == true
    val isVerified = fingerprint != null && fingerprint in verifiedFingerprints
    val dismissState = rememberSwipeToDismissBoxState()
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp
    )

    LaunchedEffect(dismissState.currentValue, conversation.conversationID) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onReadStateRequested(conversation, conversation.unreadCount > 0)
                dismissState.reset()
            }

            SwipeToDismissBoxValue.EndToStart -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onDeleteRequested(conversation)
                dismissState.reset()
            }

            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    val rowColor = if (flat) colorScheme.background else colorScheme.surface
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (flat) 0.dp else AboutHorizontalPadding)
            .clip(if (flat) androidx.compose.ui.graphics.RectangleShape else shape),
        color = rowColor,
        shape = if (flat) androidx.compose.ui.graphics.RectangleShape else shape
    ) {
        Column {
            if (!isFirst && !flat) SheetCardDivider()
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    val markRead = conversation.unreadCount > 0
                    val startToEnd =
                        dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (startToEnd) {
                                    colorScheme.secondaryContainer
                                } else {
                                    colorScheme.errorContainer
                                }
                            )
                            .padding(horizontal = SheetRowHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (startToEnd) {
                            Arrangement.Start
                        } else {
                            Arrangement.End
                        }
                    ) {
                        Icon(
                            imageVector = if (startToEnd) {
                                if (markRead) Icons.Outlined.MarkEmailRead
                                else Icons.Outlined.MarkEmailUnread
                            } else {
                                Icons.Outlined.Delete
                            },
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (startToEnd) {
                                stringResource(
                                    if (markRead) R.string.mark_read else R.string.mark_unread
                                )
                            } else {
                                stringResource(R.string.delete)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = BitchatFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            ) {
                ConversationRow(
                    conversation = conversation,
                    directPeerIdentityIDs = directPeerIdentityIDs,
                    wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                    viewModel = viewModel,
                    isFavorite = isFavorite,
                    theyFavoritedUs = theyFavoritedUs,
                    isVerified = isVerified,
                    deleteDescription = deleteDescription,
                    rowColor = rowColor,
                    horizontalPadding = if (flat) 20.dp else SheetRowHorizontal,
                    onClick = { onPrivateChatStart(conversation.conversationID) },
                    onTogglePinned = {
                        viewModel.toggleConversationPinned(conversation.conversationID)
                    },
                    onToggleMuted = {
                        viewModel.toggleConversationMuted(conversation.conversationID)
                    },
                    onReadStateRequested = {
                        onReadStateRequested(conversation, conversation.unreadCount > 0)
                    },
                    onDeleteRequested = { onDeleteRequested(conversation) }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    directPeerIdentityIDs: Set<String>,
    wifiAwareIdentityIDs: Set<String>,
    viewModel: ChatViewModel,
    isFavorite: Boolean,
    theyFavoritedUs: Boolean,
    isVerified: Boolean,
    deleteDescription: String,
    rowColor: Color,
    horizontalPadding: Dp,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleMuted: () -> Unit,
    onReadStateRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    val liveIdentityIDs = conversation.identityAliases +
        listOfNotNull(conversation.connectedPeerID?.lowercase())
    val isWifiAware = liveIdentityIDs.any(wifiAwareIdentityIDs::contains)
    val isDirect = liveIdentityIDs.any(directPeerIdentityIDs::contains) ||
        conversation.connectedPeerID?.let { peerID ->
            runCatching {
                viewModel.getMeshPeerInfo(peerID)?.isDirectConnection == true
            }.getOrDefault(false)
        } == true
    val connectionDescription = meshConnectionDescription(
        isWifiAware = isWifiAware,
        isDirect = isDirect
    )
    val basePreview = when {
        !conversation.draft.isNullOrBlank() -> stringResource(
            R.string.conversation_draft_preview,
            conversation.draft
        )
        conversation.latestMessageType == BitchatMessageType.Image ->
            stringResource(R.string.notification_sent_image)
        conversation.latestMessageType == BitchatMessageType.Audio ->
            stringResource(R.string.notification_sent_voice)
        conversation.latestMessageType == BitchatMessageType.File ->
            conversation.latestMessagePreview
                .takeIf(String::isNotBlank)
                ?.let { "📎 $it" }
                ?: stringResource(R.string.notification_sent_file)
        else -> conversation.latestMessagePreview.ifBlank { "…" }
    }
    val messagePreview = if (
        conversation.latestMessageIsOutgoing && conversation.draft.isNullOrBlank()
    ) {
        stringResource(R.string.conversation_you_preview, basePreview)
    } else {
        basePreview
    }
    // MoshGrid: plain words for where a friend is, no "mesh" or "Nostr".
    val presenceDescription = when {
        conversation.isConnected -> "In reach"
        conversation.transport == DirectMessageTransport.NOSTR -> "Over the internet"
        else -> "Not in reach"
    }
    val peerIdentity = conversation.nostrPubkey
        ?.let(viewModel::peerIdentityForNostrPubkey)
        ?: viewModel.peerIdentityForMeshPeer(conversation.conversationID)
    val assignedColor = colorForPeer(peerIdentity, palette)
    val (baseNameRaw, suffix) = splitSuffix(conversation.displayName)
    val relativeTime by produceState(
        initialValue = conversationRelativeTime(conversation.latestMessageAt),
        conversation.latestMessageAt
    ) {
        while (true) {
            value = conversationRelativeTime(conversation.latestMessageAt)
            delay(DateUtils.MINUTE_IN_MILLIS)
        }
    }
    val unreadDescription = if (conversation.unreadCount > 0) {
        stringResource(R.string.conversation_unread_count, conversation.unreadCount)
    } else {
        stringResource(R.string.conversation_read)
    }
    val pinDescription = stringResource(
        if (conversation.isPinned) R.string.unpin_conversation else R.string.pin_conversation
    )
    val muteDescription = stringResource(
        if (conversation.isMuted) R.string.unmute_conversation else R.string.mute_conversation
    )
    val readActionDescription = stringResource(
        if (conversation.unreadCount > 0) R.string.mark_read else R.string.mark_unread
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(
                    conversation.displayName,
                    unreadDescription,
                    messagePreview,
                    relativeTime,
                    presenceDescription
                ).joinToString(", ")
                stateDescription = presenceDescription
                customActions = listOf(
                    CustomAccessibilityAction(readActionDescription) {
                        onReadStateRequested()
                        true
                    },
                    CustomAccessibilityAction(pinDescription) {
                        onTogglePinned()
                        true
                    },
                    CustomAccessibilityAction(muteDescription) {
                        onToggleMuted()
                        true
                    },
                    CustomAccessibilityAction(deleteDescription) {
                        onDeleteRequested()
                        true
                    }
                )
            }
            .clickable(onClick = onClick)
            .padding(
                start = horizontalPadding,
                end = 4.dp,
                top = 10.dp,
                bottom = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MoshGrid row: round avatar, bold name and time, one-line preview with your message's
        // status and an acid unread badge, then a short "where" line.
        com.bitchat.android.ui.mosh.MoshAvatar(
            name = baseNameRaw,
            key = conversation.connectedPeerID ?: conversation.conversationID,
            size = 52.dp
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = truncateNickname(baseNameRaw),
                    fontSize = 17.sp,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isVerified) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.fingerprint_verified_label),
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (conversation.isMuted) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = muteDescription,
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
                if (conversation.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = pinDescription,
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = relativeTime,
                    fontSize = 13.sp,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (conversation.unreadCount > 0) colorScheme.primary else colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (conversation.latestMessageIsOutgoing && conversation.draft.isNullOrBlank()) {
                    conversation.latestDeliveryStatus?.let { status ->
                        DeliveryStatusIcon(status = status, sentAtMs = conversation.latestMessageAt)
                    }
                }
                Text(
                    text = if (conversation.latestMessageIsOutgoing && conversation.draft.isNullOrBlank()) basePreview else messagePreview,
                    fontSize = 15.sp,
                    fontWeight = if (!conversation.draft.isNullOrBlank()) FontWeight.Medium else FontWeight.Normal,
                    color = if (!conversation.draft.isNullOrBlank()) {
                        palette.accentOrange
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                UnreadBadge(
                    count = conversation.unreadCount,
                    colorScheme = colorScheme
                )
            }
            Text(
                text = presenceDescription,
                fontSize = 13.sp,
                color = if (conversation.isConnected) colorScheme.onSurfaceVariant else MoshWaitColor(),
                maxLines = 1
            )
        }

        Box {
            IconButton(
                onClick = { showActions = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.conversation_actions),
                    tint = palette.textTertiary
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false }
            ) {
                DropdownMenuItem(
                    text = { Text(pinDescription) },
                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = {
                        showActions = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text(muteDescription) },
                    leadingIcon = {
                        Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
                    },
                    onClick = {
                        showActions = false
                        onToggleMuted()
                    }
                )
                DropdownMenuItem(
                    text = { Text(readActionDescription) },
                    leadingIcon = {
                        Icon(
                            if (conversation.unreadCount > 0) {
                                Icons.Outlined.MarkEmailRead
                            } else {
                                Icons.Outlined.MarkEmailUnread
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        showActions = false
                        onReadStateRequested()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete),
                            color = colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = colorScheme.error
                        )
                    },
                    onClick = {
                        showActions = false
                        onDeleteRequested()
                    }
                )
            }
        }
    }
}

private fun conversationRelativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isWifiAware: Boolean = false,
    isConnected: Boolean = true,
    isSelected: Boolean,
    isFavorite: Boolean,
    theyFavoritedUs: Boolean = false,
    isVerified: Boolean,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    onItemClick: () -> Unit,
    unreadCount: Int = 0,
    showNostrGlobe: Boolean = false,
    showHashSuffix: Boolean = true
) {
    val currentNickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectionDescription = meshConnectionDescription(
        isWifiAware = isWifiAware,
        isDirect = isDirect
    )
    // Split display name for hashtag suffix support (iOS-compatible)
    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You" || peerID == currentNickname

    // Get consistent peer color (iOS-compatible)
    val palette = LocalBitchatPalette.current
    val assignedColor = colorForPeer(
        viewModel.peerIdentityForMeshPeer(peerID),
        palette
    )
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = SheetRowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = baseColor,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            isVerified = isVerified,
            badge = {
                when {
                    isConnected -> Icon(
                        painter = painterResource(
                            conversationTransportIcon(
                                isReachedOverInternet = false,
                                isWifiAware = isWifiAware,
                                isDirect = isDirect
                            )
                        ),
                        contentDescription = connectionDescription,
                        modifier = Modifier.size(13.dp),
                        tint = colorScheme.primary
                    )

                    showNostrGlobe -> Icon(
                        painter = painterResource(R.drawable.ic_spec_globe),
                        contentDescription = stringResource(R.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(13.dp),
                        tint = palette.accentPurple
                    )

                    else -> Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.cd_offline_favorite),
                        modifier = Modifier.size(11.dp),
                        tint = palette.textTertiary
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = baseName,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }
        }

        UnreadBadge(
            count = unreadCount,
            colorScheme = colorScheme,
            modifier = Modifier.padding(start = 4.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(SheetRowSelectedDot)
                    .background(colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun meshConnectionDescription(
    isWifiAware: Boolean,
    isDirect: Boolean
): String = stringResource(
    when {
        isWifiAware -> R.string.cd_direct_wifi_aware
        isDirect -> R.string.cd_direct_bluetooth
        else -> R.string.cd_routed_mesh
    }
)

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    // Scale/fade in and out so a badge appearing while the sheet is open is noticed, and one
    // clearing does not just blink away.
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
            scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
            ),
        exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
            scaleOut(
                targetScale = 0.5f,
                animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
            ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = colorScheme.primary,
                    shape = RoundedCornerShape(11.dp)
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 22.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedCountLabel(
                count = count,
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BitchatFontFamily,
                color = colorScheme.onPrimary
            )
        }
    }
}

/**
 * Convert RSSI value (dBm) to signal strength percentage (0-100)
 * RSSI typically ranges from -30 (excellent) to -100 (very poor)
 * Maps to 0-100 scale where:
 * - 0-32: No signal (0 bars)
 * - 33-65: Weak (1 bar) 
 * - 66-98: Good (2 bars)
 * - 99-100: Excellent (3 bars)
 */
private fun convertRSSIToSignalStrength(rssi: Int?): Int {
    if (rssi == null) return 0
    
    return when {
        rssi >= -40 -> 100  // Excellent signal
        rssi >= -55 -> 85   // Very good signal  
        rssi >= -70 -> 70   // Good signal
        rssi >= -85 -> 50   // Fair signal
        rssi >= -100 -> 25  // Poor signal
        else -> 0           // Very poor or no signal
    }
}

/**
 * Nested Private Chat Sheet - iOS-style nested bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatSheet(
    isPresented: Boolean,
    peerID: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    /** MoshGrid: opens Find on this friend; shown as a Find pill when they share a location. */
    onFind: ((String) -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerDirectMap by viewModel.peerDirect.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFavoritedUs by viewModel.peerFavoritedUs.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()

    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val contactResolution = remember(peerID, connectedPeers, favoritePeers) {
        ContactDirectory.resolve(peerID)
    }
    val activeMeshPeerID = contactResolution.meshPeerID
    val isWifiAware = activeMeshPeerID in wifiAwareConnected.keys || peerID in wifiAwareConnected.keys

    // Start private chat when screen opens
    LaunchedEffect(peerID) {
        viewModel.startPrivateChat(peerID)
    }

    val isNostrPeer = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")
    val favoriteRelationship = remember(peerID, favoritePeers, peerFavoritedUs) {
        try {
            FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
        } catch (_: Exception) {
            null
        }
    }
    val isDirect = activeMeshPeerID?.let { peerDirectMap[it] } == true || peerDirectMap[peerID] == true
    val isConnected = activeMeshPeerID?.let { connectedPeers.contains(it) } == true || connectedPeers.contains(peerID) || isDirect
    val isNostrReachableFavorite =
        !isConnected && favoriteRelationship?.isMutual == true && favoriteRelationship.peerNostrPublicKey != null

    // Compute display name and title text reactively
    val displayName = remember(peerID, peerNicknames, favoriteRelationship) {
        // MoshGrid: the name from the QR scan wins (a nickname in a packet can be anyone's); then the rest.
        com.bitchat.android.mosh.MoshRuntime.friendNameFor(peerID)
            ?: peerNicknames[peerID]?.takeIf { it.isNotBlank() }
            ?: activeMeshPeerID?.let { peerNicknames[it] }?.takeIf { it.isNotBlank() }
            ?: contactResolution.displayName?.takeIf { it.isNotBlank() }
            ?: favoriteRelationship?.peerNickname?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?: com.bitchat.android.mosh.MoshRuntime.friendNameFor(peerID)
            ?: viewModel.resolvePeerDisplayNameForFingerprint(peerID)
                .takeIf { it.isNotBlank() && !com.bitchat.android.services.ContactIdentityResolver.isContactConversationId(it) }
            ?: "Friend"
    }
    val titleText = remember(peerID, peerNicknames, favoriteRelationship) {
        if (isNostrPeer) {
            val gh = GeohashConversationRegistry.get(peerID) ?: "geohash"
            val fullPubkey = GeohashAliasRegistry.get(peerID) ?: ""
            val name = if (fullPubkey.isNotEmpty()) {
                viewModel.geohashViewModel.displayNameForGeohashConversation(fullPubkey, gh)
            } else {
                peerNicknames[peerID] ?: "Unknown"
            }
            // MoshGrid: just the name; the '#area/@' decoration is gone.
            name
        } else {
            displayName
        }
    }

    val conversationID = contactResolution.conversationID
    val messages = privateChats[conversationID] ?: privateChats[peerID] ?: emptyList()
    val sessionState = resolveConversationSessionState(
        conversationID = peerID,
        activeMeshPeerID = activeMeshPeerID,
        peerSessionStates = peerSessionStates
    )
    val fingerprint = activeMeshPeerID?.let { peerFingerprints[it] }
        ?: peerFingerprints[peerID]
        ?: ContactIdentityResolver.fingerprintFromContactConversationId(peerID)
    val isFavorite = remember(favoritePeers, fingerprint, peerID, favoriteRelationship) {
        if (fingerprint != null) favoritePeers.contains(fingerprint) else viewModel.isFavorite(peerID)
    }
    val theyFavoritedUs = remember(peerFavoritedUs, fingerprint, favoriteRelationship) {
        (fingerprint != null && peerFavoritedUs.contains(fingerprint)) ||
            favoriteRelationship?.theyFavoritedUs == true
    }

    // Celebrate being favorited: a springy wobble of the header star. Springs rather than
    // keyframed tweens, matching the app's press feedback, so the settle overshoots slightly.
    val starWobbleRotation = remember { Animatable(0f) }
    val starWobbleScale = remember { Animatable(1f) }
    var previousTheyFavoritedUs by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(theyFavoritedUs) {
        val wasFavoritedUs = previousTheyFavoritedUs
        previousTheyFavoritedUs = theyFavoritedUs
        if (theyFavoritedUs && wasFavoritedUs == false) {
            starWobbleRotation.snapTo(-16f)
            starWobbleScale.snapTo(1.35f)
            launch {
                starWobbleRotation.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium)
                )
            }
            launch {
                starWobbleScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    )
                )
            }
        }
    }

    val isVerified = remember(peerID, verifiedFingerprints) {
        viewModel.isPeerVerified(peerID, verifiedFingerprints)
    }

    val palette = LocalBitchatPalette.current
    // Three-state star: grey outline (no relation), orange outline (they favorited us),
    // filled orange (we favorited them, mutual or not).
    val favoriteStarTint by animateColorAsState(
        targetValue = when {
            isFavorite || theyFavoritedUs -> palette.accentOrange
            else -> colorScheme.onSurfaceVariant
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "favoriteStarTint"
    )
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // MoshGrid: where the friend is, as in the design's chat header ("64 m away · 2 min ago").
    val moshFriend = remember(peerID, favoritePeers) { com.bitchat.android.mosh.MoshRuntime.friendFor(peerID) }
    val moshLocations by com.bitchat.android.mosh.MoshRuntime.store.locations.collectAsStateWithLifecycle()
    val moshMe by com.bitchat.android.mosh.MoshRuntime.myLocation.collectAsStateWithLifecycle()
    val moshLoc = moshFriend?.let { moshLocations[it.peerID] }
    val moshWhere = moshLoc?.let { l ->
        val mins = ((System.currentTimeMillis() - l.takenAtMs).coerceAtLeast(0) / 60_000)
        val age = if (mins < 1) "just now" else if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
        val away = moshMe?.let { m ->
            val d = com.bitchat.android.mosh.distanceM(m.lat, m.lon, l.lat, l.lon)
            (if (d < 1000) "${d.toInt()} m" else "%.1f km".format(d / 1000)) + " away · "
        } ?: ""
        away + age
    }

    if (isPresented) {
        BitchatBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(modifier = Modifier.height(ChatHeaderHeight))

                    HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)

                    // Messages list
                    var forceScrollToBottom by remember { mutableStateOf(false) }
                    var isScrolledUp by remember { mutableStateOf(false) }

                    if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No messages here. Say hi.", color = colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    } else MessagesList(
                        messages = messages,
                        currentUserNickname = nickname,
                        meshService = viewModel.meshServiceFacade,
                        modifier = Modifier.weight(1f),
                        conversationKey = "dm:$peerID",
                        forceScrollToBottom = forceScrollToBottom,
                        onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                        onNicknameClick = { /* handle mention */ },
                        onMessageLongPress = { /* handle long press */ },
                        onCancelTransfer = { msg -> viewModel.cancelMediaSend(msg.id) },
                        onImageClick = { _, _, _ -> /* handle image click */ }
                    )

                    // Input section. No divider here: ChatInputSection draws its own fade and
                    // hairline.
                    var messageText by remember(peerID) {
                        mutableStateOf(
                            androidx.compose.ui.text.input.TextFieldValue(
                                viewModel.conversationDraft(peerID)
                            )
                        )
                    }

                    ChatInputSection(
                        messageText = messageText,
                        onMessageTextChange = { newText ->
                            messageText = newText
                            viewModel.setConversationDraft(peerID, newText.text)
                            // Do not update the shared suggestion state here: this sheet
                            // renders its own popups as hidden, so an update only leaves
                            // a stale popup behind for the main composer.
                        },
                        onSend = {
                            if (messageText.text.trim().isNotEmpty()) {
                                viewModel.sendMessage(messageText.text.trim()) { accepted ->
                                    if (accepted) {
                                        messageText =
                                            androidx.compose.ui.text.input.TextFieldValue("")
                                        viewModel.setConversationDraft(peerID, "")
                                        forceScrollToBottom = !forceScrollToBottom
                                    }
                                }
                            }
                        },
                        onSendVoiceNote = { peer, channel, path ->
                            viewModel.sendVoiceNote(peer, channel, path)
                        },
                        onSendImageNote = { peer, channel, path ->
                            viewModel.sendImageNote(peer, channel, path)
                        },
                        onSendFileNote = { peer, channel, path ->
                            viewModel.sendFileNote(peer, channel, path)
                        },
                        recorderFactory = viewModel::createVoiceRecorder,
                        showCommandSuggestions = false,
                        commandSuggestions = emptyList(),
                        showMentionSuggestions = false,
                        mentionSuggestions = emptyList(),
                        onCommandSuggestionClick = { },
                        onMentionSuggestionClick = { },
                        selectedPrivatePeer = peerID,
                        currentChannel = null,
                        nickname = nickname,
                        colorScheme = colorScheme,
                        showMediaButtons = true
                    )
                }

                // Header. Built from the same tokens as the main chat header rather than a
                // TopAppBar, so moving between the timeline and a conversation does not shift the
                // bar's height, insets or type.
                val dismiss = LocalSheetDismiss.current
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = colorScheme.background
                ) {
                    ConversationHeader(
                        leadingIconRes = conversationTransportIcon(
                            isReachedOverInternet = isNostrPeer || isNostrReachableFavorite,
                            isWifiAware = isWifiAware,
                            isDirect = isDirect
                        ),
                        leadingIconTint = colorScheme.primary,
                        leadingContentDescription = when {
                            isNostrPeer || isNostrReachableFavorite ->
                                stringResource(R.string.cd_nostr_reachable)
                            else -> null
                        },
                        title = titleText,
                        avatarKey = peerID,
                        subtitle = moshWhere ?: when {
                            isConnected -> "In reach"
                            isNostrPeer || isNostrReachableFavorite -> "Over the internet"
                            else -> "Not in reach"
                        },
                        onBack = { dismiss?.invoke() ?: onDismiss() },
                    ) {
                        if (moshFriend != null && moshLoc != null && onFind != null) {
                            Box(
                                Modifier.padding(end = 4.dp).heightIn(min = 40.dp)
                                    .clip(com.bitchat.android.ui.mosh.MoshShapes.Pill)
                                    .background(colorScheme.primary.copy(alpha = 0.16f))
                                    .clickable { onFind(moshFriend.peerID) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Find", color = colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        // MoshGrid: friends are made by scanning QR codes; a star tap here would
                        // silently unfriend someone, so it is hidden.
                        if (com.bitchat.android.mosh.MoshFeatures.FAVORITE_STAR) ConversationHeaderAction(
                            onClick = { viewModel.toggleFavorite(peerID) },
                            contentDescription = if (isFavorite) {
                                stringResource(R.string.cd_remove_favorite)
                            } else {
                                stringResource(R.string.cd_add_favorite)
                            }
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isFavorite) {
                                        R.drawable.ic_spec_star_filled
                                    } else {
                                        R.drawable.ic_spec_star
                                    }
                                ),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(HeaderIconSize)
                                    .graphicsLayer {
                                        rotationZ = starWobbleRotation.value
                                        scaleX = starWobbleScale.value
                                        scaleY = starWobbleScale.value
                                    },
                                tint = favoriteStarTint
                            )
                        }

                        if (isVerified) {
                            ConversationHeaderStatus {
                                Icon(
                                    imageVector = Icons.Filled.Verified,
                                    contentDescription = stringResource(
                                        R.string.fingerprint_verified_label
                                    ),
                                    modifier = Modifier.size(HeaderIconSize),
                                    tint = colorScheme.primary
                                )
                            }
                        }

                        // Keep the lock nearest the close action: from right to left the security
                        // cluster reads close, encryption, verification, then favorite.
                        if (!isNostrPeer && !isNostrReachableFavorite) {
                            ConversationHeaderAction(
                                onClick = { viewModel.showSecurityVerificationSheet() },
                                contentDescription = stringResource(R.string.verify_title)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    NoiseSessionIcon(
                                        sessionState = sessionState,
                                        modifier = Modifier.size(HeaderIconSize)
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }
    }
}

/** Amber "not in reach" colour from the design, darker on light backgrounds for contrast. */
@Composable
private fun MoshWaitColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) com.bitchat.android.ui.mosh.MoshColors.Amber
    else Color(0xFF8A5A00)

@Composable
private fun ConversationDeleteDialog(
    conversation: ConversationSummary,
    viewModel: ChatViewModel,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
) {
    val deleteFailedMessage = stringResource(R.string.conversation_delete_failed)
    val deletedMessage = stringResource(
        R.string.conversation_deleted,
        conversation.displayName
    )
    val undoLabel = stringResource(R.string.undo)
    val restoreFailedMessage =
        stringResource(R.string.conversation_restore_failed)
    AlertDialog(
        onDismissRequest = { onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = stringResource(R.string.delete_conversation_title),
                fontFamily = BitchatFontFamily
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.delete_conversation_message,
                    conversation.displayName
                ),
                fontFamily = BitchatFontFamily
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    scope.launch {
                        val deletion = viewModel.deletePrivateConversation(
                            conversation.conversationID
                        )
                        if (deletion == null) {
                            snackbarHostState.showSnackbar(
                                message = deleteFailedMessage
                            )
                            return@launch
                        }
                        val result = snackbarHostState.showSnackbar(
                            message = deletedMessage,
                            actionLabel = undoLabel,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            if (!viewModel.restoreDeletedConversation(deletion)) {
                                snackbarHostState.showSnackbar(
                                    restoreFailedMessage
                                )
                            }
                        }
                    }
                }
            ) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = BitchatFontFamily
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(
                    text = stringResource(android.R.string.cancel),
                    fontFamily = BitchatFontFamily
                )
            }
        }
    )
}
