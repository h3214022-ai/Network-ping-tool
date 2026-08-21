package com.hassanmohammadi.networkping

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hassanmohammadi.networkping.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanJob: Job? = null

    private val locationPermissionCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsIfNeeded()

        binding.btnRefreshInfo.setOnClickListener { showDeviceInfo() }
        binding.btnPing.setOnClickListener { runPing() }
        binding.btnScan.setOnClickListener { toggleScan() }

        showDeviceInfo()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), locationPermissionCode)
        }
    }

    // ============ DEVICE INFO ============

    private fun showDeviceInfo() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        val dhcp = wifiManager.dhcpInfo

        val ip = Formatter.formatIpAddress(info.ipAddress)
        val gateway = if (dhcp != null) Formatter.formatIpAddress(dhcp.gateway) else "-"
        val netmask = if (dhcp != null) Formatter.formatIpAddress(dhcp.netmask) else "-"
        val dns1 = if (dhcp != null) Formatter.formatIpAddress(dhcp.dns1) else "-"
        val ssid = info.ssid?.replace("\"", "") ?: "نامشخص"
        val mac = info.macAddress ?: "غیرقابل دسترسی (محدودیت اندروید)"

        val sb = StringBuilder()
        sb.append("SSID شبکه: $ssid\n")
        sb.append("IP دستگاه من: $ip\n")
        sb.append("Gateway (روتر): $gateway\n")
        sb.append("Subnet Mask: $netmask\n")
        sb.append("DNS: $dns1\n")
        sb.append("MAC: $mac")

        binding.tvDeviceInfo.text = sb.toString()
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) return null
        return Formatter.formatIpAddress(ipInt)
    }

    // ============ SINGLE PING (real ICMP via system ping binary) ============

    private fun runPing() {
        val target = binding.etPingTarget.text.toString().trim()
        if (target.isEmpty()) {
            binding.tvPingResult.text = "لطفاً یک IP یا دامنه وارد کنید"
            return
        }
        binding.tvPingResult.text = "در حال پینگ $target ...\n"
        binding.btnPing.isEnabled = false

        uiScope.launch {
            val result = withContext(Dispatchers.IO) { executeSystemPing(target) }
            binding.tvPingResult.text = result
            binding.btnPing.isEnabled = true
        }
    }

    private fun executeSystemPing(target: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/ping", "-c", "4", "-W", "2", target)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            if (output.isEmpty()) "پاسخی دریافت نشد — هاست در دسترس نیست" else output.toString()
        } catch (e: Exception) {
            "خطا در اجرای پینگ: ${e.message}\n\n" +
                "ممکن است دستگاه شما اجازه اجرای مستقیم ping را ندهد."
        }
    }

    // ============ WIFI NETWORK SCAN ============

    private fun toggleScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            binding.btnScan.text = "شروع اسکن شبکه"
            binding.progressScan.visibility = android.view.View.GONE
            binding.tvScanStatus.text = "اسکن متوقف شد"
            return
        }
        startNetworkScan()
    }

    private fun startNetworkScan() {
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            binding.tvScanStatus.text = "به وای‌فای متصل نیستید"
            return
        }

        val subnetPrefix = localIp.substringBeforeLast(".")
        binding.layoutScanResults.removeAllViews()
        binding.progressScan.visibility = android.view.View.VISIBLE
        binding.progressScan.progress = 0
        binding.btnScan.text = "توقف اسکن"
        binding.tvScanStatus.text = "در حال اسکن شبکه $subnetPrefix.0/24 ..."

        val foundCount = intArrayOf(0)
        val checkedCount = intArrayOf(0)

        scanJob = uiScope.launch {
            val results = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

            withContext(Dispatchers.IO) {
                val chunkSize = 16
                (1..254).chunked(chunkSize).forEach { chunk ->
                    val deferredList = chunk.map { i ->
                        async {
                            val ipToCheck = "$subnetPrefix.$i"
                            val reachable = try {
                                InetAddress.getByName(ipToCheck).isReachable(600)
                            } catch (e: Exception) {
                                false
                            }
                            synchronized(checkedCount) { checkedCount[0]++ }
                            if (reachable) {
                                val hostName = try {
                                    val addr = InetAddress.getByName(ipToCheck)
                                    val name = addr.canonicalHostName
                                    if (name == ipToCheck) "دستگاه ناشناس" else name
                                } catch (e: Exception) {
                                    "دستگاه ناشناس"
                                }
                                results.add(ipToCheck to hostName)
                                synchronized(foundCount) { foundCount[0]++ }
                            }
                            withContext(Dispatchers.Main) {
                                binding.progressScan.progress = checkedCount[0]
                                binding.tvScanStatus.text =
                                    "بررسی شد: ${checkedCount[0]}/254   |   یافت شد: ${foundCount[0]}"
                            }
                        }
                    }
                    deferredList.awaitAll()
                }
            }

            results.sortedBy { it.first.substringAfterLast(".").toIntOrNull() ?: 0 }
                .forEach { (ip, host) -> addScanResultRow(ip, host) }

            binding.progressScan.visibility = android.view.View.GONE
            binding.btnScan.text = "شروع اسکن شبکه"
            binding.tvScanStatus.text =
                if (results.isEmpty()) "هیچ دستگاهی یافت نشد"
                else "اسکن کامل شد — ${results.size} دستگاه یافت شد"
        }
    }

    private fun addScanResultRow(ip: String, hostName: String) {
        val row = TextView(this)
        val isMe = ip == getLocalIpAddress()
        row.text = if (isMe) "🟢 $ip  —  $hostName (این دستگاه شما)" else "🔵 $ip  —  $hostName"
        row.setTextColor(Color.parseColor(if (isMe) "#38EF7D" else "#EAF6FF"))
        row.textSize = 13f
        row.setPadding(8, 10, 8, 10)
        row.gravity = Gravity.START
        binding.layoutScanResults.addView(row)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
