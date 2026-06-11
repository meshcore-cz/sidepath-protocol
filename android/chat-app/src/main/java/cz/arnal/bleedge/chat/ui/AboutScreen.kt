package cz.arnal.bleedge.chat.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import cz.arnal.bleedge.chat.R

private data class LicenseItem(
    val name: String,
    val license: String,
    val detail: String,
)

private val licenses = listOf(
    LicenseItem("AndroidX Core, Activity, Lifecycle, DataStore", "Apache License 2.0", "Jetpack runtime and app infrastructure."),
    LicenseItem("Jetpack Compose, Material 3, Material Icons", "Apache License 2.0", "Declarative Android UI toolkit and icon set."),
    LicenseItem("Room", "Apache License 2.0", "Local chat database and compiler."),
    LicenseItem("Kotlin", "Apache License 2.0", "Language, compiler plugin, and standard tooling."),
    LicenseItem("Android Gradle Plugin", "Apache License 2.0", "Android build system integration."),
    LicenseItem("ZXing Core", "Apache License 2.0", "QR code encoding and decoding."),
    LicenseItem("JourneyApps ZXing Android Embedded", "Apache License 2.0", "Android QR scanner integration."),
    LicenseItem("Bouncy Castle Provider", "Bouncy Castle Licence", "Ed25519, X25519, HKDF, and AES-GCM cryptography primitives."),
    LicenseItem("CBOR-Java by Peter Occil", "CC0-1.0", "Concise Binary Object Representation codec."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionName = packageInfo.versionName ?: "unknown"
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(132.dp),
            )
            Text(
                "BLEEdge Chat",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Local-first BLE mesh chat and diagnostics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            InfoCard("App") {
                InfoRow("Version", "$versionName ($versionCode)")
                InfoRow("Package", context.packageName)
                InfoRow("Android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            }

            InfoCard("Authors") {
                Text(
                    "Created by burningtree and BLEEdge contributors.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "BLEEdge experiments with phone-to-phone and microcontroller BLE mesh routing, encrypted direct messages, group channels, trace diagnostics, and long-range relay workflows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard("Software Licences") {
                licenses.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(item.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label == "Package") FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.65f),
        )
    }
}
