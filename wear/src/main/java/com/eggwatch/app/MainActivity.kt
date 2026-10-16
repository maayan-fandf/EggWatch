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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.eggwatch.shared.Ticket
import com.eggwatch.shared.TicketProtocol
import com.eggwatch.shared.latestTicket
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay

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
                    TicketApp(onShowingQr = ::setQrMode)
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
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        @Volatile
        var isVisible = false
            private set
    }
}

@Composable
private fun TicketApp(onShowingQr: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dataClient = remember { Wearable.getDataClient(context) }
    var ticket by remember { mutableStateOf<Ticket?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ticket = runCatching { dataClient.latestTicket() }.getOrNull()
        loaded = true
    }

    DisposableEffect(dataClient) {
        val listener = DataClient.OnDataChangedListener { events ->
            events
                .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == TicketProtocol.PATH }
                .mapNotNull { Ticket.readFrom(DataMapItem.fromDataItem(it.dataItem).dataMap) }
                .lastOrNull()
                ?.let { ticket = it }
        }
        dataClient.addListener(listener)
        onDispose { dataClient.removeListener(listener) }
    }

    val current = ticket
    LaunchedEffect(current != null) { onShowingQr(current != null) }

    when {
        !loaded -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        current == null -> NoTicketScreen()
        else -> TicketScreen(current)
    }
}

@Composable
private fun NoTicketScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🥚", fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Text("אין כרטיס עדיין", textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "כשפותחים כרטיס QR באגד בטלפון, הוא יופיע כאן",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TicketScreen(ticket: Ticket) {
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
            .background(Color.White),
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
            text = ageLabel(now - ticket.capturedAt, ticket.isTest),
            color = Color.DarkGray,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

private fun ageLabel(ageMs: Long, isTest: Boolean): String {
    val minutes = ageMs / 60_000
    val age = when {
        minutes < 1 -> "עכשיו"
        minutes < 60 -> "לפני $minutes דק׳"
        else -> "לפני ${minutes / 60} שע׳"
    }
    return if (isTest) "בדיקה · $age" else age
}
