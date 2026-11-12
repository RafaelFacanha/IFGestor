package com.ifgestor.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkDiscoveryManager {

    private const val SERVICE_TYPE = "_ifgestor._tcp."
    private const val MDNS_GROUP_IP = "224.0.0.251"
    private const val MDNS_PORT = 5353

    data class PeerDevice(
        val id: String,
        val alias: String,
        val domain: String,
        val ip: String,
        val port: Int,
        val https: Boolean,
        val isSelf: Boolean = false,
        val lastSeen: Long = System.currentTimeMillis()
    ) {
        fun buildUrl(): String {
            val scheme = if (https) "https" else "http"
            return "$scheme://$ip:$port"
        }

        fun buildDomainUrl(): String {
            val scheme = if (https) "https" else "http"
            return "$scheme://$domain:$port"
        }
    }

    private val discoveredPeers = ConcurrentHashMap<String, PeerDevice>()
    private val isRunning = AtomicBoolean(false)
    private val p2pExecutor = Executors.newCachedThreadPool()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiWakeLock: WifiManager.WifiLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var mdnsResponderThread: Thread? = null
    private var mdnsSocket: MulticastSocket? = null

    @Volatile
    private var activeServiceName: String = "ifgestor"

    // =========================================================================
    // 1. OBTENCAO SEGURA DO IP LOCAL NA REDE WI-FI
    // =========================================================================

    fun getLocalIpAddress(context: Context? = null): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase(Locale.getDefault())
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (!ip.startsWith("169.254.")) return ip
                        }
                    }
                }
            }
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (context != null) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wm.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            } catch (_: Exception) {
            }
        }
        return "127.0.0.1"
    }

    fun isWifiConnected(context: Context): Boolean {
        val ip = getLocalIpAddress(context)
        return ip != "127.0.0.1" && ip != "0.0.0.0"
    }

    // =========================================================================
    // 2. INICIALIZACAO DO MOTOR mDNS (.local) E DESCOBERTA NSD MULTI-APARELHOS
    // =========================================================================

    @Synchronized
    fun startDiscoveryAndMdns(context: Context, port: Int, httpsEnabled: Boolean) {
        stopDiscoveryAndMdns()
        val appCtx = context.applicationContext
        isRunning.set(true)

        val baseAlias = SettingsManager.getDeviceAlias(appCtx)
        activeServiceName = baseAlias
        SettingsManager.setActiveMdnsDomain(appCtx, "$baseAlias.local")

        if (!SettingsManager.isMdnsEnabled(appCtx)) {
            return
        }

        acquireLocks(appCtx)
        registerNsdService(appCtx, baseAlias, port)
        startNsdPeerDiscovery(appCtx)
        startLocalMdnsResponder(appCtx)
    }

    @Synchronized
    fun stopDiscoveryAndMdns() {
        isRunning.set(false)
        discoveredPeers.clear()

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

        try {
            mdnsSocket?.close()
        } catch (_: Exception) {
        }
        mdnsSocket = null

        try {
            mdnsResponderThread?.interrupt()
        } catch (_: Exception) {
        }
        mdnsResponderThread = null

        releaseLocks()
    }

    private fun acquireLocks(context: Context) {
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("IFGestor_mDNS_Lock").apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }

            if (wifiWakeLock == null) {
                @Suppress("DEPRECATION")
                wifiWakeLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IFGestor_WiFi_Lock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiWakeLock?.isHeld == false) {
                wifiWakeLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiWakeLock?.isHeld == true) wifiWakeLock?.release()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 3. REGISTRO NSD COM RESOLUCAO AUTOMATICA DE CONFLITO (-2, -3)
    // =========================================================================

    private fun registerNsdService(context: Context, alias: String, port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = alias
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    // Caso outro celular ja use "ifgestor", o Android renomeia para "ifgestor (2)"
                    // Convertendo automaticamente para "ifgestor-2.local"
                    val normalized = info.serviceName
                        .lowercase(Locale.getDefault())
                        .replace("(", "-")
                        .replace(")", "")
                        .replace(Regex("[^a-z0-9\\-]"), "-")
                        .replace(Regex("-+"), "-")
                        .trim('-')
                        .ifEmpty { alias }

                    activeServiceName = normalized
                    val domain = "$normalized.local"
                    SettingsManager.setActiveMdnsDomain(context, domain)
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            registrationListener = listener
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun startNsdPeerDiscovery(context: Context) {
        try {
            val manager = nsdManager ?: (context.getSystemService(Context.NSD_SERVICE) as NsdManager)
            nsdManager = manager

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_ifgestor")) return
                    try {
                        manager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                val hostIp = resolvedInfo.host?.hostAddress ?: return
                                val peerPort = resolvedInfo.port
                                val myIp = getLocalIpAddress(context)

                                val cleanAlias = resolvedInfo.serviceName
                                    .lowercase(Locale.getDefault())
                                    .replace("(", "-")
                                    .replace(")", "")
                                    .replace(Regex("[^a-z0-9\\-]"), "-")
                                    .replace(Regex("-+"), "-")
                                    .trim('-')
                                    .ifEmpty { "ifgestor" }

                                val isSelfDevice = (hostIp == myIp && peerPort == SettingsManager.getServerPort(context))
                                val key = "$hostIp:$peerPort"

                                discoveredPeers[key] = PeerDevice(
                                    id = key,
                                    alias = cleanAlias,
                                    domain = "$cleanAlias.local",
                                    ip = hostIp,
                                    port = peerPort,
                                    https = SettingsManager.isHttpsEnabled(context),
                                    isSelf = isSelfDevice,
                                    lastSeen = System.currentTimeMillis()
                                )
                            }
                        })
                    } catch (_: Exception) {
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    val lostName = service.serviceName.lowercase(Locale.getDefault())
                    discoveredPeers.entries.removeIf { (_, peer) ->
                        !peer.isSelf && peer.alias.equals(lostName, ignoreCase = true)
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            discoveryListener = listener
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 4. RESPONDEDOR mDNS (UDP 5353) PARA RESOLUCAO DIRETA DE ifgestor.local NO PC
    // =========================================================================

    private fun startLocalMdnsResponder(context: Context) {
        mdnsResponderThread = Thread {
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(MDNS_GROUP_IP)
                socket = MulticastSocket(MDNS_PORT).apply {
                    reuseAddress = true
                    timeToLive = 255
                    joinGroup(group)
                }
                mdnsSocket = socket

                // Anuncia a presenca do dominio ao iniciar
                sendMdnsAnnouncement(socket, group, context)

                val buffer = ByteArray(1500)
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val domain = SettingsManager.getActiveMdnsDomain(context).lowercase(Locale.getDefault())
                    val queryName = parseDnsQuestionName(packet.data, packet.length)?.lowercase(Locale.getDefault())

                    if (queryName != null && (queryName == domain || queryName == "$activeServiceName.local")) {
                        val ipStr = getLocalIpAddress(context)
                        val ipBytes = InetAddress.getByName(ipStr).address
                        if (ipBytes.size == 4) {
                            val responseBytes = buildMdnsResponsePacket(domain, ipBytes)
                            val replyPacket = DatagramPacket(
                                responseBytes,
                                responseBytes.size,
                                group,
                                MDNS_PORT
                            )
                            socket.send(replyPacket)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }.apply {
            name = "IFGestor-mDNS-Responder"
            isDaemon = true
            start()
        }
    }

    private fun sendMdnsAnnouncement(socket: MulticastSocket, group: InetAddress, context: Context) {
        try {
            val domain = SettingsManager.getActiveMdnsDomain(context).lowercase(Locale.getDefault())
            val ipStr = getLocalIpAddress(context)
            val ipBytes = InetAddress.getByName(ipStr).address
            if (ipBytes.size == 4) {
                val pktBytes = buildMdnsResponsePacket(domain, ipBytes)
                socket.send(DatagramPacket(pktBytes, pktBytes.size, group, MDNS_PORT))
            }
        } catch (_: Exception) {
        }
    }

    private fun parseDnsQuestionName(data: ByteArray, length: Int): String? {
        if (length < 12) return null
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        // Ignora pacotes de resposta (bit QR = 1)
        if ((flags and 0x8000) != 0) return null

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdCount <= 0) return null

        var pos = 12
        val parts = mutableListOf<String>()
        while (pos < length) {
            val labelLen = data[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if ((labelLen and 0xC0) == 0xC0) break
            pos++
            if (pos + labelLen > length) return null
            parts.add(String(data, pos, labelLen, Charsets.UTF_8))
            pos += labelLen
        }
        return if (parts.isEmpty()) null else parts.joinToString(".")
    }

    private fun buildMdnsResponsePacket(domain: String, ipv4Bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        // Cabecalho DNS padrao (ID=0, Flags=0x8400 Resposta Autoritativa, ANCOUNT=1)
        dos.writeShort(0x0000)
        dos.writeShort(0x8400)
        dos.writeShort(0x0000)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)

        // Nome do Dominio (ex: ifgestor.local)
        for (part in domain.split('.')) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0x00)

        // Tipo A (0x0001), Classe IN + Cache Flush (0x8001), TTL 120s, Tamanho 4 bytes + IPv4
        dos.writeShort(0x0001)
        dos.writeShort(0x8001)
        dos.writeInt(120)
        dos.writeShort(4)
        dos.write(ipv4Bytes)
        dos.flush()
        return out.toByteArray()
    }

    // =========================================================================
    // 5. LISTAGEM DE DISPOSITIVOS ATIVOS NA REDE (SELETOR WEB & MOBILE - ITEM 7)
    // =========================================================================

    fun getDiscoveredPeers(context: Context): List<PeerDevice> {
        val myIp = getLocalIpAddress(context)
        val myPort = SettingsManager.getServerPort(context)
        val myDomain = SettingsManager.getActiveMdnsDomain(context)
        val myAlias = myDomain.removeSuffix(".local")
        val myHttps = SettingsManager.isHttpsEnabled(context)

        val selfPeer = PeerDevice(
            id = "$myIp:$myPort",
            alias = myAlias,
            domain = myDomain,
            ip = myIp,
            port = myPort,
            https = myHttps,
            isSelf = true
        )

        val list = mutableListOf(selfPeer)
        for (peer in discoveredPeers.values) {
            if (peer.ip != myIp || peer.port != myPort) {
                list.add(peer.copy(isSelf = false))
            }
        }
        return list.sortedWith(compareByDescending<PeerDevice> { it.isSelf }.thenBy { it.alias })
    }

    fun getDiscoveredPeersJson(context: Context): JSONArray {
        val arr = JSONArray()
        for (peer in getDiscoveredPeers(context)) {
            arr.put(
                JSONObject().apply {
                    put("id", peer.id)
                    put("alias", peer.alias)
                    put("domain", peer.domain)
                    put("ip", peer.ip)
                    put("port", peer.port)
                    put("https", peer.https)
                    put("isSelf", peer.isSelf)
                    put("url", peer.buildUrl())
                    put("domainUrl", peer.buildDomainUrl())
                }
            )
        }
        return arr
    }

    // =========================================================================
    // 6. TRANSFERENCIA DIRETA ENTRE CELULARES (P2P COM 2FA E BARRA DE PROGRESSO)
    // =========================================================================

    fun sendFilesPeerToPeerAsync(
        context: Context,
        targetIp: String,
        targetPort: Int,
        targetHttps: Boolean,
        targetSubfolder: String,
        files: List<File>,
        onComplete: ((Boolean, String) -> Unit)? = null
    ): String {
        val taskId = "p2p_send_${System.currentTimeMillis()}"
        val validFiles = files.filter { it.exists() && it.isFile && SettingsManager.isInsideSandbox(it) }

        if (validFiles.isEmpty()) {
            onComplete?.invoke(false, "Nenhum arquivo válido selecionado na Sandbox.")
            return taskId
        }

        val totalBytes = validFiles.sumOf { it.length().coerceAtLeast(1L) }

        SettingsManager.upsertTask(
            id = taskId,
            title = "📲 Enviando P2P para $targetIp",
            subtitle = "Aguardando aprovação 2FA no aparelho de destino...",
            type = "P2P_SEND",
            currentItems = 0,
            totalItems = validFiles.size,
            bytesProcessed = 0L,
            totalBytes = totalBytes,
            status = "RUNNING"
        )

        p2pExecutor.execute {
            var sentBytesSoFar = 0L
            var successCount = 0
            val senderAlias = SettingsManager.getActiveMdnsDomain(context).removeSuffix(".local")

            try {
                // Etapa 1: Solicita aprovacao 2FA no celular de destino antes de transmitir os arquivos
                val handshakeToken = requestPeer2faHandshake(
                    targetIp = targetIp,
                    targetPort = targetPort,
                    targetHttps = targetHttps,
                    senderAlias = senderAlias,
                    fileCount = validFiles.size,
                    targetSubfolder = targetSubfolder
                )

                if (handshakeToken.isNullOrBlank()) {
                    SettingsManager.upsertTask(
                        id = taskId,
                        title = "📲 P2P Recusado ($targetIp)",
                        subtitle = "O aparelho de destino não aprovou o recebimento 2FA.",
                        type = "P2P_SEND",
                        currentItems = 0,
                        totalItems = validFiles.size,
                        bytesProcessed = 0L,
                        totalBytes = totalBytes,
                        status = "FAILED"
                    )
                    SettingsManager.appendAuditLog(
                        context = context,
                        ip = targetIp,
                        category = "P2P",
                        action = "ENVIO_P2P_NEGADO",
                        details = "Destino $targetIp:$targetPort recusou ou expirou a confirmacao 2FA",
                        success = false
                    )
                    onComplete?.invoke(false, "Recebimento não autorizado no aparelho de destino.")
                    return@execute
                }

                // Etapa 2: Transmite cada arquivo em stream paginado atualizando a barra de progresso
                for ((index, file) in validFiles.withIndex()) {
                    SettingsManager.tryLockFile(file)
                    try {
                        val scheme = if (targetHttps) "https" else "http"
                        val encodedName = URLEncoder.encode(file.name, "UTF-8")
                        val encodedFolder = URLEncoder.encode(targetSubfolder, "UTF-8")
                        val encodedToken = URLEncoder.encode(handshakeToken, "UTF-8")
                        val endpoint = "$scheme://$targetIp:$targetPort/api/p2p/upload" +
                            "?name=$encodedName&folder=$encodedFolder&token=$encodedToken" +
                            "&index=${index + 1}&total=${validFiles.size}"

                        val conn = openPeerConnection(endpoint, targetHttps)
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/octet-stream")
                        conn.setRequestProperty("X-IFGestor-Sender", senderAlias)
                        conn.setFixedLengthStreamingMode(file.length())

                        BufferedInputStream(FileInputStream(file)).use { input ->
                            conn.outputStream.use { output ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                var lastUiUpdate = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                    sentBytesSoFar += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdate >= 120L) {
                                        lastUiUpdate = now
                                        SettingsManager.upsertTask(
                                            id = taskId,
                                            title = "📲 Enviando P2P (${index + 1}/${validFiles.size})",
                                            subtitle = "${file.name} → $targetIp",
                                            type = "P2P_SEND",
                                            currentItems = index + 1,
                                            totalItems = validFiles.size,
                                            bytesProcessed = sentBytesSoFar,
                                            totalBytes = totalBytes,
                                            status = "RUNNING"
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (conn.responseCode in 200..299) {
                            successCount++
                        }
                        conn.disconnect()
                    } finally {
                        SettingsManager.unlockFile(file)
                    }
                }

                val allOk = (successCount == validFiles.size)
                SettingsManager.upsertTask(
                    id = taskId,
                    title = if (allOk) "✅ P2P Concluído ($targetIp)" else "⚠️ P2P Parcial ($successCount/${validFiles.size})",
                    subtitle = "$successCount arquivo(s) enviado(s) com segurança",
                    type = "P2P_SEND",
                    currentItems = successCount,
                    totalItems = validFiles.size,
                    bytesProcessed = totalBytes,
                    totalBytes = totalBytes,
                    status = if (allOk) "COMPLETED" else "FAILED"
                )

                SettingsManager.appendAuditLog(
                    context = context,
                    ip = targetIp,
                    category = "P2P",
                    action = "ENVIO_P2P_CONCLUIDO",
                    details = "Enviados $successCount/${validFiles.size} arquivo(s) para $targetIp:$targetPort",
                    success = allOk
                )
                onComplete?.invoke(allOk, "Transferência P2P finalizada ($successCount/${validFiles.size}).")
            } catch (e: Exception) {
                SettingsManager.upsertTask(
                    id = taskId,
                    title = "❌ Falha no P2P ($targetIp)",
                    subtitle = e.message ?: "Erro de conexão na rede local",
                    type = "P2P_SEND",
                    currentItems = successCount,
                    totalItems = validFiles.size,
                    bytesProcessed = sentBytesSoFar,
                    totalBytes = totalBytes,
                    status = "FAILED"
                )
                SettingsManager.appendAuditLog(
                    context = context,
                    ip = targetIp,
                    category = "P2P",
                    action = "ERRO_P2P",
                    details = "Falha P2P para $targetIp: ${e.message}",
                    success = false
                )
                onComplete?.invoke(false, "Erro na transferência P2P: ${e.message}")
            }
        }

        return taskId
    }

    private fun requestPeer2faHandshake(
        targetIp: String,
        targetPort: Int,
        targetHttps: Boolean,
        senderAlias: String,
        fileCount: Int,
        targetSubfolder: String
    ): String? {
        return try {
            val scheme = if (targetHttps) "https" else "http"
            val url = "$scheme://$targetIp:$targetPort/api/p2p/handshake"
            val conn = openPeerConnection(url, targetHttps)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 65000 // Aguarda ate 60s pela aprovacao 2FA no celular de destino
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

            val body = "sender=${URLEncoder.encode(senderAlias, "UTF-8")}" +
                "&count=$fileCount" +
                "&folder=${URLEncoder.encode(targetSubfolder, "UTF-8")}"

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(responseText)
                if (json.optBoolean("approved", false)) {
                    json.optString("token", null)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openPeerConnection(urlStr: String, isHttps: Boolean): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        if (isHttps && conn is HttpsURLConnection) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return conn
    }
}
