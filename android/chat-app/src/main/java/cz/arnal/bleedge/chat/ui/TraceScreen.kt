package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.TraceResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceScreen(vm: ChatViewModel, peerHex: String, onBack: () -> Unit) {
    val trace by vm.trace.collectAsState()
    val title = vm.nameForHex(peerHex)

    // Kick off a trace when the page opens.
    LaunchedEffect(peerHex) { vm.startTrace(peerHex) }

    val back = {
        vm.clearTrace()
        onBack()
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("Trace route", fontWeight = FontWeight.SemiBold)
                        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.startTrace(peerHex) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Trace again")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val t = trace
            when {
                t == null || t.peerHex != peerHex -> Loading("Starting trace…")
                t.error != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.startTrace(peerHex) }) { Text("Try again") }
                }
                t.running -> Loading("Tracing route to $title…")
                t.result != null -> TraceResultView(t.result!!, t.rttMs, vm) { vm.startTrace(peerHex) }
            }
        }
    }
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TraceResultView(result: TraceResult, rttMs: Long?, vm: ChatViewModel, onRetry: () -> Unit) {
    val hops = result.forwardNodes.size
    Text("Reached in $hops hop${if (hops == 1) "" else "s"} · metric: ${result.metric}", fontWeight = FontWeight.Medium)
    if (rttMs != null) {
        Text(
            "RTT: ${rttMs} ms",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }

    HorizontalDivider()
    Text("Forward path", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (result.forwardNodes.isEmpty()) {
        Text("Direct (no relays)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        result.forwardNodes.forEachIndexed { i, node ->
            HopRow(i + 1, node, result.forwardSamples.getOrNull(i), result.metric, vm)
        }
    }

    if (result.returnNodes.isNotEmpty()) {
        HorizontalDivider()
        Text("Return path", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        result.returnNodes.forEachIndexed { i, node ->
            HopRow(i + 1, node, result.returnSamples.getOrNull(i), result.metric, vm)
        }
    }

    Spacer(Modifier.size(8.dp))
    Button(onClick = onRetry) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Trace again")
    }
}

@Composable
private fun HopRow(index: Int, node: NodeID, sample: Byte?, metric: String, vm: ChatViewModel) {
    val hex = node.toHexString()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$index.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Avatar(seed = hex, label = vm.nameForHex(hex), size = 28)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.nameForHex(hex), fontWeight = FontWeight.Medium, maxLines = 1)
            Text(hex.take(16), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sample != null) {
            Text(
                "${sample.toInt()} ${if (metric == "snr") "dB" else "dBm"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
