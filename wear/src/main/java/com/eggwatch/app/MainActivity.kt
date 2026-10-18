package com.eggwatch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.eggwatch.shared.RideProtocol
import com.eggwatch.shared.RideStatus
import com.eggwatch.shared.RideStatus.State
import com.eggwatch.shared.Ticket
import com.eggwatch.shared.TicketProtocol
import com.eggwatch.shared.latestTicket
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A ticket this recent is shown straight away (exit gate, inspector); older ones open on the home screen. */
private const val FRESH_TICKET_MS = 3 * 60 * 60 * 1000L

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    EggWatchApp(onQrMode = ::setQrMode, onKeepAwake = ::setKeepAwake)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
    }

    override fun onStop() {
        isVisible = false
        super.onStop()
    }

    /** Gate scanners read a bright, steady screen best. */
    private fun setQrMode(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        setKeepAwake(on)
    }

    private fun setKeepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        @Volatile
        var isVisible = false
            private set
    }
}

/** A ride started from the watch, as the watch sees it. */
private sealed interface RideUi {
    /** Request sent, nothing back from the phone yet. */
    data object Sending : RideUi
    data class Phone(val status: RideStatus) : RideUi
    data class Failed(val message: String) : RideUi
}

@Composable
private fun EggWatchApp(onQrMode: (Boolean) -> Unit, onKeepAwake: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataClient = remember { Wearable.getDataClient(context) }
    val messageClient = remember { Wearable.getMessageClient(context) }
    var ticket by remember { mutableStateOf<Ticket?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showTicket by remember { mutableStateOf(false) }
    var ride by remember { mutableStateOf<RideUi?>(null) }

    LaunchedEffect(Unit) {
        val latest = runCatching { dataClient.latestTicket() }.getOrNull()
        ticket = latest
        showTicket = latest != null && System.currentTimeMillis() - latest.capturedAt < FRESH_TICKET_MS
        loaded = true
    }

    DisposableEffect(dataClient) {
        val listener = DataClient.OnDataChangedListener { events ->
            events
                .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == TicketProtocol.PATH }
                .mapNotNull { Ticket.readFrom(DataMapItem.fromDataItem(it.dataItem).dataMap) }
                .lastOrNull()
                ?.let {
                    ticket = it
                    showTicket = true
                    // The ticket is what a ride was waiting for.
                    if (ride != null && ride !is RideUi.Failed) ride = null
                }
        }
        dataClient.addListener(listener)
        onDispose { dataClient.removeListener(listener) }
    }

    DisposableEffect(messageClient) {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == RideProtocol.PATH_STATUS && ride != null) {
                RideStatus.fromBytes(event.data)?.let { ride = RideUi.Phone(it) }
            }
        }
        messageClient.addListener(listener)
        onDispose { messageClient.removeListener(listener) }
    }

    // Give up on a step the phone stops answering.
    LaunchedEffect(ride) {
        val current = ride
        val (waitMs, message) = when {
            current is RideUi.Sending -> 15_000L to "אין תשובה מהטלפון"
            current is RideUi.Phone && current.status.state == State.WORKING -> 45_000L to "הטלפון הפסיק לענות"
            current is RideUi.Phone && current.status.state == State.NEEDS_UNLOCK -> 75_000L to "הטלפון לא נפתח"
            current is RideUi.Phone && current.status.state == State.CONFIRM -> 32_000L to "עבר הזמן – לא נוצר כרטיס"
            current is RideUi.Phone && current.status.state == State.TICKET_READY -> 15_000L to "הכרטיס לא הגיע לשעון"
            else -> return@LaunchedEffect
        }
        delay(waitMs)
        if (ride == current) ride = RideUi.Failed(message)
    }

    val qrVisible = ride == null && showTicket && ticket != null
    LaunchedEffect(qrVisible) { onQrMode(qrVisible) }
    LaunchedEffect(ride != null) { if (!qrVisible) onKeepAwake(ride != null) }

    fun send(path: String) = scope.launch {
        if (!RideClient.send(context, path) && path == RideProtocol.PATH_PREPARE) {
            ride = RideUi.Failed("הטלפון לא מחובר")
        }
    }

    val current = ticket
    val currentRide = ride
    when {
        !loaded -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        currentRide != null -> RideScreen(
            ride = currentRide,
            onConfirm = { send(RideProtocol.PATH_CONFIRM) },
            onCancel = {
                send(RideProtocol.PATH_CANCEL)
                ride = null
            },
            onClose = { ride = null },
        )
        showTicket && current != null -> TicketScreen(current, onTap = { showTicket = false })
        else -> HomeScreen(
            lastTicket = current,
            onStartRide = {
                ride = RideUi.Sending
                send(RideProtocol.PATH_PREPARE)
            },
            onShowTicket = { showTicket = true },
        )
    }
}

@Composable
private fun HomeScreen(lastTicket: Ticket?, onStartRide: () -> Unit, onShowTicket: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🥚", fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onStartRide,
            label = { Text("כרטיס לרכבת קלה", fontWeight = FontWeight.Bold) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (lastTicket != null) {
            Spacer(Modifier.height(6.dp))
            CompactChip(
                onClick = onShowTicket,
                label = { Text("הכרטיס האחרון · ${ageLabel(System.currentTimeMillis() - lastTicket.capturedAt)}") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun RideScreen(ride: RideUi, onConfirm: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val status = (ride as? RideUi.Phone)?.status
        when {
            ride is RideUi.Sending || status?.state == State.WORKING || status?.state == State.TICKET_READY -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        status == null -> "מתחבר לטלפון…"
                        status.state == State.TICKET_READY -> "${status.message} – שולח לשעון…"
                        else -> status.message
                    },
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                CompactChip(onClick = onCancel, label = { Text("ביטול") }, colors = ChipDefaults.secondaryChipColors())
            }
            status?.state == State.NEEDS_UNLOCK -> {
                Text("🔓", fontSize = 26.sp)
                Spacer(Modifier.height(6.dp))
                Text(status.message, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                Text("אחר כך הכל ממשיך לבד", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                CompactChip(onClick = onCancel, label = { Text("ביטול") }, colors = ChipDefaults.secondaryChipColors())
            }
            status?.state == State.CONFIRM -> {
                Text(status.station, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(
                    listOf(status.price, passengersLabel(status.passengers)).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 14.sp,
                    color = Color.LightGray,
                )
                Spacer(Modifier.height(10.dp))
                Chip(
                    onClick = onConfirm,
                    label = { Text("יצירת כרטיס", fontWeight = FontWeight.Bold) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                CompactChip(onClick = onCancel, label = { Text("ביטול") }, colors = ChipDefaults.secondaryChipColors())
            }
            else -> {
                val message = (ride as? RideUi.Failed)?.message ?: status?.message.orEmpty()
                Text(if (status?.state == State.CANCELLED) "✋" else "⚠️", fontSize = 24.sp)
                Spacer(Modifier.height(6.dp))
                Text(message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                CompactChip(onClick = onClose, label = { Text("סגירה") }, colors = ChipDefaults.secondaryChipColors())
            }
        }
    }
}

@Composable
private fun TicketScreen(ticket: Ticket, onTap: () -> Unit) {
    val image = remember(ticket.hash, ticket.capturedAt) { ticket.modules.toBitmap().asImageBitmap() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // 70% of the width is the largest square that fits a round face;
        // the white around it doubles as the QR quiet zone.
        Image(
            bitmap = image,
            contentDescription = "QR",
            filterQuality = FilterQuality.None,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(0.70f),
        )
        Text(
            text = ageLabel(now - ticket.capturedAt).let { if (ticket.isTest) "בדיקה · $it" else it },
            color = Color.DarkGray,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

private fun ageLabel(ageMs: Long): String {
    val minutes = ageMs / 60_000
    return when {
        minutes < 1 -> "עכשיו"
        minutes < 60 -> "לפני $minutes דק׳"
        minutes < 48 * 60 -> "לפני ${minutes / 60} שע׳"
        else -> "לפני ${minutes / (24 * 60)} ימים"
    }
}

private fun passengersLabel(count: Int) = if (count == 1) "נוסע אחד" else "$count נוסעים"
