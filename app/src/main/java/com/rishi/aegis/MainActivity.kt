package com.rishi.aegis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rishi.aegis.tools.ApkScreens
import com.rishi.aegis.tools.BreachScreens
import com.rishi.aegis.tools.CryptoScreens
import com.rishi.aegis.tools.HotspotScreens
import com.rishi.aegis.tools.DeviceScreens
import com.rishi.aegis.tools.GuardianScreens
import com.rishi.aegis.tools.NetworkScreens
import com.rishi.aegis.tools.TermuxScreens
import com.rishi.aegis.tools.TrafficScreens
import com.rishi.aegis.tools.VaultScreens
import com.rishi.aegis.tools.WebScreens
import com.rishi.aegis.tools.WifiScreens
import com.rishi.aegis.ui.AegisTheme
import com.rishi.aegis.ui.Note
import com.rishi.aegis.ui.SectionLabel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AegisTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun open(tool: Tool) = backStack.add(Screen.ToolScreen(tool))
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    BackHandler(enabled = backStack.size > 1) { back() }

    when (val screen = backStack.last()) {
        is Screen.Home -> Dashboard(onOpen = ::open)
        is Screen.ToolScreen -> ToolRouter(screen.tool, onBack = ::back)
    }
}

@Composable
private fun ToolRouter(tool: Tool, onBack: () -> Unit) {
    when (tool) {
        Tool.NET_INFO -> NetworkScreens.NetInfo(onBack)
        Tool.HOST_DISCOVERY -> NetworkScreens.HostDiscovery(onBack)
        Tool.PORT_SCAN -> NetworkScreens.PortScan(onBack)
        Tool.PING -> NetworkScreens.Ping(onBack)
        Tool.DNS -> NetworkScreens.Dns(onBack)
        Tool.WHOIS -> NetworkScreens.Whois(onBack)
        Tool.SUBNET -> NetworkScreens.Subnet(onBack)
        Tool.TRAFFIC_MON -> TrafficScreens.Monitor(onBack)
        Tool.WIFI_INFO -> WifiScreens.Connection(onBack)
        Tool.WIFI_SCAN -> WifiScreens.Scan(onBack)
        Tool.HOTSPOT_MON -> HotspotScreens.Monitor(onBack)
        Tool.HTTP_HEADERS -> WebScreens.Headers(onBack)
        Tool.FINGERPRINT -> WebScreens.Fingerprint(onBack)
        Tool.DIR_BRUTE -> WebScreens.DirBrute(onBack)
        Tool.REQ_BUILDER -> WebScreens.RequestBuilder(onBack)
        Tool.HASH_GEN -> CryptoScreens.HashGen(onBack)
        Tool.HASH_ID -> CryptoScreens.HashId(onBack)
        Tool.HASH_CRACK -> CryptoScreens.HashCrack(onBack)
        Tool.ENCODER -> CryptoScreens.Encoder(onBack)
        Tool.PW_TOOLS -> CryptoScreens.PasswordTools(onBack)
        Tool.FILE_HASH -> CryptoScreens.FileHash(onBack)
        Tool.BREACH_CHECK -> BreachScreens.BreachCheck(onBack)
        Tool.TLS_INSPECT -> WebScreens.TlsInspector(onBack)
        Tool.HEADER_AUDIT -> WebScreens.HeaderAudit(onBack)
        Tool.SEC_CHECKUP -> DeviceScreens.SecurityCheckup(onBack)
        Tool.PERM_AUDIT -> DeviceScreens.PermissionAuditor(onBack)
        Tool.APK_ANALYZE -> ApkScreens.Analyzer(onBack)
        Tool.TOTP_AUTH -> VaultScreens.Authenticator(onBack)
        Tool.SECURE_VAULT -> VaultScreens.SecureNotes(onBack)
        Tool.CLI_SETUP -> TermuxScreens.Setup(onBack)
        Tool.CLI_RUN -> TermuxScreens.RunCommand(onBack)
        Tool.CLI_NMAP -> TermuxScreens.Nmap(onBack)
        Tool.CLI_TOOLBOX -> TermuxScreens.Toolbox(onBack)
        Tool.GUARD_SETUP -> GuardianScreens.Setup(onBack)
        Tool.GUARD_LOG -> GuardianScreens.Log(onBack)
    }
}

@Composable
private fun Dashboard(onOpen: (Tool) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Aegis", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "On-device security toolkit",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
        )
        Note(
            "For use on networks and systems you own or are authorized to test. " +
                "No root required — some tools are limited by Android's sandbox."
        )
        Spacer(Modifier.height(4.dp))

        for (cat in Category.entries) {
            SectionLabel(cat.label)
            Note(cat.blurb)
            Spacer(Modifier.height(2.dp))
            val tools = Tool.entries.filter { it.category == cat }
            for (row in tools.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (tool in row) ToolTile(tool, Modifier.weight(1f), onOpen)
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, modifier: Modifier, onOpen: (Tool) -> Unit) {
    Card(
        modifier = modifier
            .height(116.dp)
            .clickable { onOpen(tool) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(tool.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                tool.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
