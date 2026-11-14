package com.ifgestor.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MdnsDiscoveryManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val audit = AuditLogManager.getInstance(appContext)

    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mdnsResponderJob: Job? = null
    private var multicastSocket: MulticastSocket? = null

    private val discoveredPeers = ConcurrentHashMap<String, PeerDevice>()
    private val _peersFlow = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peersFlow: StateFlow<List<PeerDevice>> = _peersFlow.asStateFlow()

    private val _activeHostnameFlow = MutableStateFlow(settings.getActiveMdnsHostname())
    val activeHostnameFlow: StateFlow<String> = _activeHostnameFlow.asStateFlow()

    @Volatile
    private var isRunning = false

    data class PeerDevice(
        val id: String,
        val nickname: String,
        val hostname: String,
        val ipAddress: String,
        val port: Int,
        val isSelf: Boolean = false,
        val lastSeen: Long = System.currentTimeMillis()
    ) {
        val httpsUrl: String
            get() = "https://$hostname:$port"

        val fallbackIpUrl: String
            get() = "https://$ipAddress:$port"
    }

    /**
     * Inicia o anúncio mDNS/NSD, a descoberta de outros celulares IFGestor na rede
     * e o resolvedor UDP para https://ifgestor.local:8080 (ou ifgestor-2.local em caso de conflito).
     */
    @Synchronized
    fun start(port: Int = settings.getServerPort()) {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()
        updateSelfPeerEntry(port)
        registerNsdService(port)
        startNsdDiscovery()
        startMdnsUdpResponder()
    }

    /**
     * Encerra os serviços de anúncio, descoberta e libera o MulticastLock para economizar bateria.
     */
    @Synchronized
    fun stop() {
        isRunning = false

        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        }
        registrationListener = null

        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null

        mdnsResponderJob?.cancel()
        mdnsResponderJob = null

        try {
            multicastSocket?.close()
        } catch (_: Exception) {
        }
        multicastSocket = null

        releaseMulticastLock()
        discoveredPeers.clear()
        _peersFlow.value = emptyList()
    }

    /**
     * Atualiza o apelido do dispositivo e reinicia o anúncio na rede se estiver ativo.
     */
    fun refreshDeviceIdentity() {
        if (isRunning) {
            val port = settings.getServerPort()
            stop()
            start(port)
        } else {
            updateSelfPeerEntry(settings.getServerPort())
        }
    }

    /**
     * Obtém o endereço IPv4 local ativo na interface Wi-Fi / LAN.
     */
    fun getLocalIpv4Address(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase(Locale.ROOT)
                if (name.contains("wlan") || name.contains("ap") || name.contains("eth")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            }
            // Fallback em qualquer interface IPv4 não-loopback
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    /**
     * Retorna a URL principal HTTPS mDNS (ex: https://ifgestor.local:8080).
     */
    fun getPrimaryHttpsDomainUrl(): String {
        val host = _activeHostnameFlow.value.ifBlank { "ifgestor.local" }
        val port = settings.getServerPort()
        return "https://$host:$port"
    }

    /**
     * Retorna a URL HTTPS via IP direto (ex: https://192.168.1.15:8080).
     */
    fun getFallbackHttpsIpUrl(): String {
        val ip = getLocalIpv4Address()
        val port = settings.getServerPort()
        return "https://$ip:$port"
    }

    /**
     * Exporta todos os dispositivos ativos na rede (incluindo o próprio aparelho marcado com isSelf=true)
     * para alimentar o seletor no topo da interface Web (Item 7) e o modal de envio P2P (Item 8).
     */
    fun toJsonArray(): JSONArray {
        updateSelfPeerEntry(settings.getServerPort())
        val arr = JSONArray()
        _peersFlow.value.forEach { peer ->
            val obj = JSONObject().apply {
                put("id", peer.id)
                put("nickname", peer.nickname)
                put("hostname", peer.hostname)
                put("ipAddress", peer.ipAddress)
                put("port", peer.port)
                put("isSelf", peer.isSelf)
                put("httpsUrl", peer.httpsUrl)
                put("fallbackIpUrl", peer.fallbackIpUrl)
            }
            arr.put(obj)
        }
        return arr
    }

    // =========================================================================
    // NSD Service Registration & Conflict Resolution (ifgestor, ifgestor-2...)
    // =========================================================================

    private fun registerNsdService(port: Int) {
        val manager = nsdManager ?: return
        val nickname = settings.getDeviceNickname()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = BASE_SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("nickname", nickname)
            setAttribute("port", port.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                val resolvedDomain = normalizeServiceNameToDomain(registeredInfo.serviceName)
                settings.setActiveMdnsHostname(resolvedDomain)
                _activeHostnameFlow.value = resolvedDomain
                updateSelfPeerEntry(port)

                audit.logEvent(
                    action = "MDNS_DOMINIO_ATIVO",
                    actor = "Rede Local",
                    details = "Domínio registrado como https://$resolvedDomain:$port (Apelido: $nickname)",
                    success = true
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                settings.setActiveMdnsHostname("ifgestor.local")
                _activeHostnameFlow.value = "ifgestor.local"
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        registrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    /**
     * Converte nomes com sufixo de colisão do Android NSD (ex: "ifgestor (2)" ou "ifgestor-2")
     * para o padrão de domínio limpo "ifgestor-2.local" (Item 6).
     */
    private fun normalizeServiceNameToDomain(rawServiceName: String?): String {
        if (rawServiceName.isNullOrBlank()) return "ifgestor.local"
        val cleaned = rawServiceName.trim().lowercase(Locale.ROOT)

        // Detecta padrão "ifgestor (2)" ou "ifgestor 2" ou "ifgestor-2"
        val numberRegex = Regex("(\\d+)")
        val match = numberRegex.find(cleaned)
        return if (match != null) {
            val suffix = match.value.toIntOrNull() ?: 2
            if (suffix <= 1) "ifgestor.local" else "ifgestor-$suffix.local"
        } else {
            "ifgestor.local"
        }
    }

    // =========================================================================
    // NSD Peer Discovery (Descoberta de outros celulares IFGestor na mesma rede)
    // =========================================================================

    private fun startNsdDiscovery() {
        val manager = nsdManager ?: return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_ifgestor")) return
                try {
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                handleResolvedPeer(resolvedInfo)
                            }
                        }
                    )
                } catch (_: Exception) {
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val domain = normalizeServiceNameToDomain(service.serviceName)
                val toRemove = discoveredPeers.values.filter { !it.isSelf && it.hostname == domain }
                toRemove.forEach { discoveredPeers.remove(it.id) }
                publishPeersList()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResolvedPeer(resolvedInfo: NsdServiceInfo) {
        val hostAddress = resolvedInfo.host?.hostAddress ?: return
        val localIp = getLocalIpv4Address()
        val peerPort = resolvedInfo.port.takeIf { it > 0 } ?: 8080
        val peerDomain = normalizeServiceNameToDomain(resolvedInfo.serviceName)

        val rawNickBytes = resolvedInfo.attributes["nickname"]
        val peerNickname = if (rawNickBytes != null && rawNickBytes.isNotEmpty()) {
            String(rawNickBytes, Charsets.UTF_8)
        } else {
            peerDomain.removeSuffix(".local")
        }

        val isSelfDevice = (hostAddress == localIp && peerPort == settings.getServerPort())
        if (isSelfDevice) {
            updateSelfPeerEntry(peerPort)
            return
        }

        // Resolução extra de conflito: se outro IP já usa nosso hostname, incrementa o nosso para -2 ou -3
        if (peerDomain == _activeHostnameFlow.value && hostAddress != localIp) {
            resolveDomainCollisionWithPeer(peerDomain)
        }

        val id = "$hostAddress:$peerPort"
        discoveredPeers[id] = PeerDevice(
            id = id,
            nickname = peerNickname,
            hostname = peerDomain,
            ipAddress = hostAddress,
            port = peerPort,
            isSelf = false,
            lastSeen = System.currentTimeMillis()
        )
        publishPeersList()
    }

    private fun resolveDomainCollisionWithPeer(collidingDomain: String) {
        val usedDomains = discoveredPeers.values.map { it.hostname }.toSet() + collidingDomain
        var index = 2
        while ("ifgestor-$index.local" in usedDomains) {
            index++
        }
        val newDomain = "ifgestor-$index.local"
        settings.setActiveMdnsHostname(newDomain)
        _activeHostnameFlow.value = newDomain
        updateSelfPeerEntry(settings.getServerPort())
    }

    private fun updateSelfPeerEntry(port: Int) {
        val ip = getLocalIpv4Address()
        val domain = _activeHostnameFlow.value.ifBlank { "ifgestor.local" }
        val nickname = settings.getDeviceNickname()
        val selfId = "self"

        discoveredPeers[selfId] = PeerDevice(
            id = selfId,
            nickname = "$nickname (Este Aparelho)",
            hostname = domain,
            ipAddress = ip,
            port = port,
            isSelf = true,
            lastSeen = System.currentTimeMillis()
        )
        publishPeersList()
    }

    private fun publishPeersList() {
        val list = discoveredPeers.values.sortedWith(
            compareByDescending<PeerDevice> { it.isSelf }.thenBy { it.nickname.lowercase(Locale.ROOT) }
        )
        _peersFlow.value = list
    }

    // =========================================================================
    // Resolvedor UDP mDNS (RFC 6762 - 224.0.0.251:5353) para Resolução Direta no PC
    // =========================================================================

    private fun startMdnsUdpResponder() {
        mdnsResponderJob?.cancel()
        mdnsResponderJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val group = InetAddress.getByName(MDNS_MULTICAST_ADDRESS)
                    val socket = MulticastSocket(MDNS_PORT).apply {
                        reuseAddress = true
                        timeToLive = 255
                        joinGroup(group)
                    }
                    multicastSocket = socket

                    val buffer = ByteArray(1500)
                    while (isActive && isRunning && !socket.isClosed) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        handleIncomingMdnsPacket(socket, packet, group)
                    }
                } catch (_: Exception) {
                    delay(3000L)
                }
            }
        }
    }

    private fun handleIncomingMdnsPacket(
        socket: MulticastSocket,
        packet: DatagramPacket,
        multicastGroup: InetAddress
    ) {
        val data = packet.data
        val length = packet.length
        if (length < 12) return

        // Verifica flag QR == 0 (Query DNS)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdCount <= 0) return

        val parsedNameAndOffset = parseDnsQuestionName(data, 12, length) ?: return
        val queriedHost = parsedNameAndOffset.first.lowercase(Locale.ROOT)
        val nextOffset = parsedNameAndOffset.second
        if (nextOffset + 4 > length) return

        val qType = ((data[nextOffset].toInt() and 0xFF) shl 8) or (data[nextOffset + 1].toInt() and 0xFF)
        // Tipo A (1) ou ANY (255)
        if (qType != 1 && qType != 255) return

        val activeHost = _activeHostnameFlow.value.lowercase(Locale.ROOT)
        if (queriedHost == activeHost) {
            val localIp = getLocalIpv4Address()
            val ipParts = localIp.split(".").mapNotNull { it.toIntOrNull() }
            if (ipParts.size == 4) {
                val responseBytes = buildMdnsARecordResponse(activeHost, ipParts)
                val outPacket = DatagramPacket(
                    responseBytes,
                    responseBytes.size,
                    multicastGroup,
                    MDNS_PORT
                )
                socket.send(outPacket)
            }
        }
    }

    private fun parseDnsQuestionName(data: ByteArray, startOffset: Int, maxLen: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var offset = startOffset
        while (offset < maxLen) {
            val len = data[offset].toInt() and 0xFF
            if (len == 0) {
                offset += 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                return null
            }
            offset += 1
            if (offset + len > maxLen) return null
            val label = String(data, offset, len, Charsets.UTF_8)
            labels.add(label)
            offset += len
        }
        if (labels.isEmpty()) return null
        return Pair(labels.joinToString("."), offset)
    }

    private fun buildMdnsARecordResponse(hostname: String, ipv4Octets: List<Int>): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Cabeçalho DNS: ID=0, Flags=0x8400 (Response + Authoritative), QDCOUNT=0, ANCOUNT=1, NSCOUNT=0, ARCOUNT=0
        dos.writeShort(0x0000)
        dos.writeShort(0x8400)
        dos.writeShort(0x0000)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)

        // Nome do domínio em labels (ex: "ifgestor", "local")
        hostname.split(".").filter { it.isNotBlank() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0x00)

        // TYPE = A (1), CLASS = IN + Cache Flush (0x8001), TTL = 120 segundos, RDLENGTH = 4
        dos.writeShort(0x0001)
        dos.writeShort(0x8001)
        dos.writeInt(120)
        dos.writeShort(4)
        for (octet in ipv4Octets) {
            dos.writeByte(octet and 0xFF)
        }
        dos.flush()
        return baos.toByteArray()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("IFGestor_mDNS_Lock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val BASE_SERVICE_NAME = "ifgestor"
        private const val SERVICE_TYPE = "_ifgestor._tcp."
        private const val MDNS_MULTICAST_ADDRESS = "224.0.0.251"
        private const val MDNS_PORT = 5353

        @Volatile
        private var INSTANCE: MdnsDiscoveryManager? = null

        fun getInstance(context: Context): MdnsDiscoveryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MdnsDiscoveryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
