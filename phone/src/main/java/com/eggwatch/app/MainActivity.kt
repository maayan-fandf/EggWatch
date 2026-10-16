package com.eggwatch.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eggwatch.shared.Ticket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the screen re-checks settings the user may have just changed. */
    private val resumes = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideLog.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) {
                        val resumeCount by resumes.collectAsState()
                        HomeScreen(resumeCount)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.value++
    }
}

@Composable
private fun HomeScreen(resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by WatchLink.latest.collectAsState()
    val log by RideLog.entries.collectAsState()
    var serviceOn by remember { mutableStateOf(false) }
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resumeCount) {
        serviceOn = isWatcherEnabled(context)
        watches = WatchLink.connectedWatches(context)
        WatchLink.refreshLatest(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("EggWatch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("שיקוף כרטיס הנסיעה מאגד לשעון", style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckRow(
                        label = "שירות השיקוף",
                        ok = serviceOn,
                        detail = if (serviceOn) "פועל" else "כבוי – יש להפעיל בהגדרות נגישות",
                        action = if (serviceOn) null else "הפעלה" to {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )
                    CheckRow(
                        label = "שעון",
                        ok = !watches.isNullOrEmpty(),
                        detail = when {
                            watches == null -> "בודק…"
                            watches!!.isEmpty() -> "לא מחובר כרגע (הכרטיס יגיע כשיתחבר)"
                            else -> watches!!.joinToString()
                        },
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("הכרטיס בשעון", style = MaterialTheme.typography.titleMedium)
                    val ticket = latest
                    if (ticket == null) {
                        Text("עוד לא נשלח כרטיס. כשפותחים כרטיס QR באגד, הוא יועתק לשעון.")
                    } else {
                        TicketPreview(ticket)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !sending,
                            onClick = {
                                sending = true
                                scope.launch {
                                    val count = WatchLink.publishTest(context)
                                    sendResult = when {
                                        count < 0 -> "השליחה נכשלה"
                                        count == 0 -> "נשמר – יגיע לשעון כשיתחבר"
                                        else -> "נשלח לשעון"
                                    }
                                    sending = false
                                }
                            },
                        ) { Text("שליחת QR בדיקה") }
                        OutlinedButton(onClick = {
                            context.packageManager.getLaunchIntentForPackage(TicketWatcherService.EGG_PACKAGE)
                                ?.let(context::startActivity)
                        }) { Text("פתיחת אגד") }
                    }
                    sendResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("יומן", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { RideLog.clear() }) { Text("ניקוי") }
                }
                if (log.isEmpty()) Text("ריק. היומן מתמלא כשאגד פתוחה והשירות פועל.")
            }
        }

        items(log) { entry ->
            LogRow(entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String, action: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⬜", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        action?.let { (text, onClick) -> Button(onClick = onClick) { Text(text) } }
    }
}

@Composable
private fun TicketPreview(ticket: Ticket) {
    val image = remember(ticket.hash, ticket.capturedAt) { ticket.modules.toBitmap().asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = image,
            contentDescription = "QR",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .size(120.dp)
                .background(Color.White)
                .padding(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (ticket.isTest) "קוד בדיקה" else "כרטיס מאגד", fontWeight = FontWeight.SemiBold)
            Text("נשלח ב-${formatTime(ticket.capturedAt)}", style = MaterialTheme.typography.bodySmall)
            Text("${ticket.modules.size}×${ticket.modules.size} · ${ticket.hash}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogRow(entry: JSONObject) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(
            formatTime(entry.optLong("t")),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.width(72.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(describe(entry), fontSize = 13.sp)
        }
    }
}

private fun describe(e: JSONObject): String = when (e.optString("type")) {
    "service_on" -> "השירות הופעל"
    "egg_open" -> "אגד נפתחה"
    "egg_closed" -> "אגד נסגרה / המסך כבה"
    "screen" -> "מסך: " + (e.optJSONArray("texts")?.let { a -> (0 until minOf(a.length(), 3)).joinToString(" | ") { a.optString(it) } } ?: "")
    "qr" -> buildString {
        append("קוד חדש ${e.optString("hash")} (${e.optInt("size")}×${e.optInt("size")})")
        if (e.has("replaces")) append(" — מחליף את ${e.optString("replaces")}")
        if (e.optInt("errorsCorrected") > 0) append(" · תיקונים: ${e.optInt("errorsCorrected")}")
    }
    "qr_gone" -> "הקוד ${e.optString("hash")} נעלם אחרי ${e.optLong("shownMs") / 1000} ש׳ (${e.optInt("scans")} סריקות)"
    "sent" -> when (val n = e.optInt("watches")) {
        -1 -> "שליחה לשעון נכשלה"
        0 -> "נשמר לשעון (לא מחובר כרגע)"
        else -> "נשלח לשעון ($n מחוברים)"
    }
    "shot_error" -> "צילום מסך נכשל (קוד ${e.optInt("code")})"
    "black_frame" -> "המסך יצא שחור – כנראה מסך מוגן"
    else -> e.toString()
}

private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(millis))

private fun isWatcherEnabled(context: Context): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java)
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        val info = it.resolveInfo.serviceInfo
        info.packageName == context.packageName && info.name == TicketWatcherService::class.java.name
    }
}
