package cz.meshcore.sidepath.debug.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.Sidepath
import cz.meshcore.sidepath.service.SidepathService
import cz.meshcore.sidepath.service.LogEntry
import cz.meshcore.sidepath.service.NeighborEntry
import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.ReceivedMessage
import cz.meshcore.sidepath.service.TopologyEntry
import cz.meshcore.sidepath.transport.PHYMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sidepath_prefs")
private val SEED_KEY = stringPreferencesKey("seed")
private val ALLOWLIST_KEY = stringPreferencesKey("allowlist")

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _service = MutableStateFlow<SidepathService?>(null)
    private var serviceBound = false

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _allowlistInput = MutableStateFlow("")
    val allowlistInput: StateFlow<String> = _allowlistInput.asStateFlow()

    private val _allowlist = MutableStateFlow<Set<String>>(emptySet())
    val allowlist: StateFlow<Set<String>> = _allowlist.asStateFlow()

    // Forwarded StateFlows from SidepathService (fall back to defaults when unbound)
    val nodeId: StateFlow<NodeId> = _service.flatMapLatest {
        it?.nodeId ?: flowOf(NodeId.BROADCAST)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodeId.BROADCAST)

    val bleMacAddress: StateFlow<String> = _service.flatMapLatest {
        it?.bleMacAddress ?: flowOf("")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val phyMode: StateFlow<PHYMode> = _service.flatMapLatest {
        it?.phyMode ?: flowOf(PHYMode.ONE_M)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PHYMode.ONE_M)

    val codedPhySupported: StateFlow<Boolean> = _service.flatMapLatest {
        it?.codedPhySupported ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // True when hardware doesn't support requested PHY and 1M fallback is active
    val phyFallback: StateFlow<Boolean> = _service.flatMapLatest {
        it?.phyFallback ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isRunning: StateFlow<Boolean> = _service.flatMapLatest {
        it?.isRunning ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val advertisingActive: StateFlow<Boolean> = _service.flatMapLatest {
        it?.advertisingActive ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val scanningActive: StateFlow<Boolean> = _service.flatMapLatest {
        it?.scanningActive ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val connectedPeers: StateFlow<List<PeerInfo>> = _service.flatMapLatest {
        it?.connectedPeers ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val receivedMessages: StateFlow<List<ReceivedMessage>> = _service.flatMapLatest {
        it?.receivedMessages ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val routingLog: StateFlow<List<LogEntry>> = _service.flatMapLatest {
        it?.routingLog ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val neighborTable: StateFlow<List<NeighborEntry>> = _service.flatMapLatest {
        it?.neighborTable ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val knownTopology: StateFlow<List<TopologyEntry>> = _service.flatMapLatest {
        it?.knownTopology ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as SidepathService.LocalBinder).getService()
            _service.value = service
            viewModelScope.launch { initializeService(service) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            serviceBound = false
        }
    }

    fun bindService() {
        if (serviceBound) return
        serviceBound = true
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SidepathService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onPermissionsGranted() {
        _permissionsGranted.value = true
        bindService()
    }

    private suspend fun initializeService(service: SidepathService) {
        val ctx = getApplication<Application>()
        // Use first() to read once — avoids re-entrancy issues with collect + edit.
        val pref = ctx.dataStore.data.first()

        // Ed25519 identity seed (32 bytes). NodeID is derived as pubkey[:8].
        val seedHex = pref[SEED_KEY] ?: run {
            val newSeed = generateSeedHex()
            ctx.dataStore.edit { it[SEED_KEY] = newSeed }
            newSeed
        }
        val allowlistStr = pref[ALLOWLIST_KEY] ?: ""
        val allowlistSet = if (allowlistStr.isBlank()) emptySet()
        else allowlistStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

        _allowlist.value = allowlistSet

        val seed = ByteArray(Sidepath.SEED_BYTES) { i -> seedHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val identity = Identity.fromSeed(seed)
        service.initialize(identity, PHYMode.ONE_M, allowlistSet)
    }

    fun sendMessage(destination: String, message: String, ttl: Int) {
        val service = _service.value ?: return
        val dstId = if (destination.isBlank()) NodeId.BROADCAST
        else runCatching { NodeId.fromHex(destination) }.getOrElse { return }
        service.sendMessage(message, dstId, ttl.toByte())
    }

    fun addAllowlistEntry(nodeIdHex: String) {
        viewModelScope.launch {
            val trimmed = nodeIdHex.trim()
            if (trimmed.length != 16) return@launch
            val newSet = _allowlist.value + trimmed
            _allowlist.value = newSet
            saveAllowlist(newSet)
            _service.value?.setAllowlist(newSet)
        }
    }

    fun removeAllowlistEntry(nodeIdHex: String) {
        viewModelScope.launch {
            val newSet = _allowlist.value - nodeIdHex
            _allowlist.value = newSet
            saveAllowlist(newSet)
            _service.value?.setAllowlist(newSet)
        }
    }

    fun clearData() {
        _service.value?.clearData()
    }

    fun toggleRunning(activity: MainActivity? = null) {
        val service = _service.value
        if (service == null) {
            // Service not bound yet — re-trigger the whole permission + bind flow.
            if (_permissionsGranted.value) {
                serviceBound = false // allow re-bind
                bindService()
            } else {
                activity?.requestPermissionsAndStart()
            }
            return
        }
        if (service.isRunning.value) service.stopBLE() else service.startBLE()
    }

    fun onAllowlistInputChange(value: String) {
        _allowlistInput.value = value
    }

    private suspend fun saveAllowlist(set: Set<String>) {
        getApplication<Application>().dataStore.edit {
            it[ALLOWLIST_KEY] = set.joinToString(",")
        }
    }

    private fun generateSeedHex(): String {
        val bytes = ByteArray(Sidepath.SEED_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
