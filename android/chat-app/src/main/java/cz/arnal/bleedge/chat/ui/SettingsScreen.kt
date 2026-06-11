package cz.arnal.bleedge.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cz.arnal.bleedge.chat.ChatViewModel
import cz.arnal.bleedge.chat.hexToBytes
import cz.arnal.bleedge.chat.toHex
import cz.arnal.bleedge.core.Identity
import cz.arnal.bleedge.core.PHYMode

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val seedHex by vm.seedHex.collectAsState()
    val description by vm.description.collectAsState()
    val phyMode by vm.phyMode.collectAsState()

    val pubKeyHex = remember(seedHex) {
        runCatching { Identity.fromSeed(seedHex.hexToBytes()).publicKey.toHex() }.getOrDefault("")
    }
    val nodeHex = remember(pubKeyHex) { pubKeyHex.take(16) }

    var descDraft by remember(description) { mutableStateOf(description) }
    var seedDraft by remember(seedHex) { mutableStateOf(seedHex) }
    var revealSeed by remember { mutableStateOf(false) }
    var seedError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Identity
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("My identity", style = MaterialTheme.typography.titleMedium)
                    Field("Node ID", nodeHex)
                    SelectionContainer { Field("Public key", pubKeyHex) }
                }
            }

            // Description
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Display name / description", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = descDraft,
                        onValueChange = { descDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. Jan's phone") },
                    )
                    Button(
                        onClick = { vm.setDescription(descDraft.trim()) },
                        enabled = descDraft.trim() != description,
                    ) { Text("Save description") }
                }
            }

            // Private key (seed)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Private key (seed)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "32-byte hex seed. Changing it gives you a new identity and node ID, and restarts the radio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = seedDraft,
                        onValueChange = { seedDraft = it.trim(); seedError = false },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = seedError,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        visualTransformation = if (revealSeed) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = { if (seedError) Text("Must be 64 hex characters") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { revealSeed = !revealSeed }) {
                            Text(if (revealSeed) "Hide" else "Reveal")
                        }
                        OutlinedButton(onClick = {
                            vm.regenerateSeed()
                        }) { Text("Regenerate") }
                        Button(
                            onClick = { if (!vm.applySeed(seedDraft)) seedError = true },
                            enabled = seedDraft != seedHex,
                        ) { Text("Apply") }
                    }
                }
            }

            // PHY mode
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Radio (PHY mode)", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PHYMode.entries.forEach { mode ->
                            FilterChip(
                                selected = phyMode == mode,
                                onClick = { vm.setPhyMode(mode) },
                                label = { Text(mode.value) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Keep the seed text field in sync if the seed changes underneath (e.g. regenerate).
    LaunchedEffect(seedHex) { seedDraft = seedHex }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}
