package com.hikari.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalContext
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import com.hikari.app.lock.AppLock
import com.hikari.app.tv.tvTextFieldKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MyStuff {
    const val LIBRARY = com.hikari.app.data.MyStuffSection.LIBRARY
    const val HISTORY = com.hikari.app.data.MyStuffSection.HISTORY
    const val DOWNLOADS = com.hikari.app.data.MyStuffSection.DOWNLOADS

    /** The sections, in the order the strip draws them. */
    val ALL = com.hikari.app.data.MyStuffSection.ALL
}

/** Process-memory unlock for the My Stuff lock: false on every fresh process
 *  (so the tab asks once per app launch), true once the password is entered.
 *  Never persisted — and the password/switch themselves are device-local (see
 *  AppStore.DeviceLocal), so neither travels in a backup nor a pairing. */
object MyStuffUnlock {
    @Volatile
    var unlocked: Boolean = false
}

@Composable
fun MyStuffScreen(nav: NavHostController, initial: String = MyStuff.LIBRARY) {
    // The section is remembered across leaving and returning to the tab, and a
    // route that names one (history / downloads) wins on arrival.
    var section by rememberSaveable { mutableStateOf(initial) }
    // Arriving on a different route (e.g. the player's "Continue watching"
    // link) must move the strip, not leave the previous section showing.
    androidx.compose.runtime.LaunchedEffect(initial) { section = initial }

    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HikariApp
    val libraryFlow = remember { app.store.myStuffSectionFlow(MyStuff.LIBRARY) }
    val historyFlow = remember { app.store.myStuffSectionFlow(MyStuff.HISTORY) }
    val downloadsFlow = remember { app.store.myStuffSectionFlow(MyStuff.DOWNLOADS) }
    val libraryOn by libraryFlow.collectAsState(initial = true)
    val historyOn by historyFlow.collectAsState(initial = true)
    val downloadsOn by downloadsFlow.collectAsState(initial = true)
    val myStuffLockFlow = remember { app.store.myStuffLockFlow() }
    val myStuffLocked by myStuffLockFlow.collectAsState(initial = false)
    var myStuffOpen by remember { mutableStateOf(MyStuffUnlock.unlocked) }
    if (myStuffLocked && !myStuffOpen) {
        MyStuffLockGate(
            onUnlock = {
                MyStuffUnlock.unlocked = true
                myStuffOpen = true
            },
        )
        return
    }
    val visible = MyStuff.ALL.filter { section ->
        when (section) {
            MyStuff.HISTORY -> historyOn
            MyStuff.DOWNLOADS -> downloadsOn
            else -> libraryOn
        }
    }
    LaunchedEffect(visible) {
        if (visible.isNotEmpty() && section !in visible) section = visible.first()
    }
    val strip = if (visible.isEmpty()) listOf(section) else visible

    val tvPager = com.hikari.app.tv.TvMode.current()
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = strip.indexOf(section).coerceAtLeast(0),
        pageCount = { strip.size },
    )
    val scope = rememberCoroutineScope()
    val stripKey = strip.joinToString(",")
    // Guard against the classic bi-directional race: tapping a pill sets
    // `section`, which starts animateScrollToPage; mid-animation
    // pager.currentPage can flicker and the reverse effect would set
    // `section` back, leaving the pager stuck between two pages. Sliding
    // worked because only the reverse effect ran. Use settledPage for the
    // swipe→section direction and suppress it while a programmatic
    // animation is in flight.
    var pagingProgrammatically by remember { mutableStateOf(false) }
    LaunchedEffect(section, stripKey) {
        val target = strip.indexOf(section)
        if (target < 0 || target == pager.settledPage) return@LaunchedEffect
        if (target == pager.currentPage && !pager.isScrollInProgress) return@LaunchedEffect
        pagingProgrammatically = true
        try {
            // TV jumps: an animation the D-pad can interrupt mid-flight is how
            // a section change visibly shuttled right-and-left.
            if (tvPager) pager.scrollToPage(target)
            else pager.animateScrollToPage(target)
        } finally {
            pagingProgrammatically = false
        }
    }
    LaunchedEffect(pager, stripKey) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }
            .collect { page ->
                if (pagingProgrammatically) return@collect
                val shown = strip.getOrNull(page)
                if (shown != null && shown != section) section = shown
            }
    }

    Column(Modifier.fillMaxSize()) {
        // One section left means the strip is a single pill: hide it rather than
        // draw a lone button that does nothing but take a row of the screen.
        if (strip.size > 1) {
            MyStuffStrip(strip, section) { picked ->
                section = picked
            }
        }
        if (tvPager) {
            Box(Modifier.fillMaxSize()) { MyStuffPage(nav, section) }
        } else {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // With one section there is nothing to swipe to, and a pager that
            // still slides is a page the user can drag into empty space.
            userScrollEnabled = strip.size > 1,
        ) { page ->
            MyStuffPage(nav, strip.getOrNull(page))
        }
        }
    }
}

/** The body of one My Stuff section — shared by the pager's pages (phone)
 *  and the direct draw (TV, which has no pager). */
@Composable
private fun MyStuffPage(nav: NavHostController, name: String?) {
    when (name) {
        MyStuff.HISTORY -> HistoryScreen(nav, embedded = true)
        MyStuff.DOWNLOADS -> DownloadsScreen(nav, embedded = true)
        else -> LibraryScreen(nav, embedded = true)
    }
}

/** The password card drawn instead of the tab while the My Stuff lock is
 *  engaged: the password field, an Unlock button, and nothing else — the
 *  sections behind it are never composed while it is up, so no title, poster
 *  or download name can flash through first. */
@Composable
private fun MyStuffLockGate(onUnlock: () -> Unit) {
    val app = LocalContext.current.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr("My Stuff is locked"),
                style = MaterialTheme.typography.titleLarge,
                color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextPrimary
                else Color.Unspecified,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Enter the My Stuff password to see your library, history and downloads."),
                style = MaterialTheme.typography.bodyMedium,
                color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    wrong = false
                },
                label = { Text(tr("Password")) },
                singleLine = true,
                isError = wrong,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(password),
            )
            if (wrong) {
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("That is not the My Stuff password"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val attempt = password
                    scope.launch {
                        busy = true
                        val ok = withContext(Dispatchers.Default) {
                            val secret = runCatching { app.store.myStuffLockSecret() }
                                .getOrDefault("")
                            AppLock.verify(attempt, secret)
                        }
                        busy = false
                        if (ok) {
                            password = ""
                            onUnlock()
                        } else {
                            wrong = true
                        }
                    }
                },
                enabled = password.isNotBlank() && !busy,
            ) {
                Text(tr("Unlock"))
            }
        }
    }
}

@Composable
private fun MyStuffStrip(sections: List<String>, current: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (com.hikari.app.tv.TvMode.current()) 20.dp else 16.dp,
                end = if (com.hikari.app.tv.TvMode.current()) 20.dp else 16.dp,
                top = if (com.hikari.app.tv.TvMode.current()) 14.dp else 12.dp,
                bottom = 2.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(if (com.hikari.app.tv.TvMode.current()) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The labels are read HERE (a composable position) because `tr` is
        // itself composable and cannot be called from inside a click lambda.
        sections.forEach { section ->
            MyStuffPill(
                label = when (section) {
                    MyStuff.HISTORY -> tr("History")
                    MyStuff.DOWNLOADS -> tr("Downloads")
                    else -> tr("Library")
                },
                icon = when (section) {
                    MyStuff.HISTORY -> Icons.Filled.History
                    MyStuff.DOWNLOADS -> Icons.Filled.Download
                    else -> Icons.Filled.Favorite
                },
                selected = current == section,
                modifier = Modifier.weight(1f),
            ) { onPick(section) }
        }
    }
}

@Composable
private fun MyStuffPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextMuted
    else MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val measuredStyle = remember(labelStyle, density.fontScale) {
        labelStyle.copy(fontSize = 100.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    }
    val reference = remember(label, measuredStyle, density.density) {
        measurer.measure(
            text = AnnotatedString(label),
            style = measuredStyle,
            maxLines = 1,
            softWrap = false,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = 100000),
        ).size.width
    }

    BoxWithConstraints(modifier.height(38.dp)) {
        // The pill's own padding (8dp a side), the 16dp icon and the 6dp gap —
        // the room a label has when the icon is drawn.
        val withIcon = with(density) { (maxWidth - 16.dp - 16.dp - 6.dp).toPx() }
        // …and what is left of the pill once the icon is gone.
        val withoutIcon = with(density) { (maxWidth - 16.dp).toPx() }
        fun sizeFor(room: Float): Float {
            if (reference <= 0 || room <= 0f) return labelStyle.fontSize.value
            val fontScale = density.fontScale.coerceAtLeast(0.5f)
            return (room * 0.98f * 100f * fontScale / reference)
                .coerceAtMost(labelStyle.fontSize.value)
        }
        val sizedWithIcon = sizeFor(withIcon)
        val showIcon = sizedWithIcon >= 11f
        val sizeSp = if (showIcon) sizedWithIcon else sizeFor(withoutIcon)
        val scale = density.fontScale.coerceAtLeast(0.5f)
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary
            else if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.SurfaceCard
            else MaterialTheme.colorScheme.surfaceVariant,
            border = if (!selected && com.hikari.app.tv.TvMode.current())
                androidx.compose.foundation.BorderStroke(1.dp, com.hikari.app.tv.TvDesktop.GlassEdge)
            else null,
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = labelStyle,
                    fontSize = (sizeSp / scale).sp,
                    letterSpacing = 0.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
