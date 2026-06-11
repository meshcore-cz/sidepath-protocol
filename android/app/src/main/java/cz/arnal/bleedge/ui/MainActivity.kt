package cz.arnal.bleedge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.onPermissionsGranted()
        }
        // If denied the user will see the inactive state and can press Play to retry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    // Check / request permissions exactly once after first composition.
                    // Using LaunchedEffect avoids triggering side-effects inline during
                    // composition, which can silently misfire on Compose re-entry.
                    LaunchedEffect(Unit) {
                        if (allPermissionsGranted()) {
                            viewModel.onPermissionsGranted()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    }
                    MainScreen(viewModel)
                }
            }
        }
    }

    /** Called by the play button when the service isn't bound yet (e.g. after data clear). */
    fun requestPermissionsAndStart() {
        if (allPermissionsGranted()) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
}
