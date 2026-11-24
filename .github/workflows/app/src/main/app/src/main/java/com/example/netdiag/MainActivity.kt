package com.example.netdiag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tvTerminal: TextView
    private lateinit var tvAdvice: TextView
    private lateinit var btnScan: Button
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    data class WifiMetrics(
        val ssid: String,
        val rssi: Int,
        val linkSpeed: Int,
        val freq: Int,
        val gatewayIp: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTerminal = findViewById(R.id.tvTerminal)
        tvAdvice = findViewById(R.id.tvAdvice)
        btnScan = findViewById(R.id.btnScan)

        checkPermissions()

        btnScan.setOnClickListener {
            startFullDiagnostics()
        }
    }

    private fun checkPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, perms, 100)
        }
    }

    private fun startFullDiagnostics() {
        btnScan.isEnabled = false
        tvTerminal.text = ""
        tvAdvice.text = "Выполняется анализ цепочки..."

        scope.launch {
            val wifi = readWifiHardware()
            val band = if (wifi.freq > 4900) "5 ГГц" else "2.4 ГГц"

            log("$ iw dev wlan0 link")
            log("  SSID: ${wifi.ssid} ($band, ${wifi.freq} MHz)")
            log("  signal: ${wifi.rssi} dBm")
            log("  link bitrate: ${wifi.linkSpeed} MBit/s\n")

            log("$ ip route")
            log("  default via ${wifi.gatewayIp}\n")

            log("$ ping -c 3 ${wifi.gatewayIp}")
            val routerPing = withContext(Dispatchers.IO) { pingAvg(wifi.gatewayIp) }
            log("  avg rtt: ${if (routerPing >= 0) "$routerPing мс" else "таймаут"}\n")

            log("$ ping -c 3 1.1.1.1")
            val internetPing = withContext(Dispatchers.IO) { pingAvg("1.1.1.1") }
            log("  avg rtt: ${if (internetPing >= 0) "$internetPing мс" else "таймаут"}\n")

            log("$ curl speed.cloudflare.com (25MB)")
            val downloadMbps = withContext(Dispatchers.IO) { measureDownload() }
            log("  download: ${String.format("%.1f", downloadMbps)} MBit/s\n")

            tvAdvice.text = buildRecommendations(wifi, routerPing, internetPing, downloadMbps)
            btnScan.isEnabled = true
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifiHardware(): WifiMetrics {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info: WifiInfo = wifiManager.connectionInfo

        val ssid = info.ssid?.replace("\"", "")?.takeIf { it != "<unknown ssid>" } ?: "Скрыто (включите GPS)"
        val rssi = info.rssi
        val linkSpeed = info.linkSpeed
        val freq = info.frequency
        val gateway = Formatter.formatIpAddress(wifiManager.dhcpInfo.gateway)

        return WifiMetrics(ssid, rssi, linkSpeed, freq, gateway)
    }

    private fun pingAvg(host: String): Double {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 3 $host")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var avg = -1.0
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("rtt min/avg/max")) {
                    val parts = line!!.substringAfter("= ").split("/")
                    if (parts.size >= 2) avg = parts[1].toDoubleOrNull() ?: -1.0
                }
            }
            process.waitFor()
            avg
        } catch (e: Exception) {
            -1.0
        }
    }

    private fun measureDownload(): Double {
        return try {
            val url = URL("https://speed.cloudflare.com/__down?bytes=25000000")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 12000

            val start = System.currentTimeMillis()
            val input = conn.inputStream
            val buffer = ByteArray(8192)
            var read: Int
            var total = 0L
            while (input.read(buffer).also { read = it } != -1) {
                total += read
            }
            input.close()

            val sec = (System.currentTimeMillis() - start) / 1000.0
            if (sec > 0) (total * 8.0) / (sec * 1_000_000.0) else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun buildRecommendations(
        wifi: WifiMetrics,
        routerPing: Double,
        internetPing: Double,
        download: Double
    ): String {
        val tips = mutableListOf<String>()

        when {
            wifi.rssi < -75 -> tips.add("КРИТИЧЕСКИ СЛАБЫЙ СИГНАЛ (${wifi.rssi} dBm): телефон на грани обрыва. Подойдите ближе к роутеру, уберите препятствия (зеркала, бетонные стены) или установите Wi-Fi репитер / Mesh-узел.")
            wifi.rssi < -67 -> tips.add("СРЕДНИЙ СИГНАЛ (${wifi.rssi} dBm): возможны просадки скорости и задержки. Попробуйте поднять роутер выше от пола или поправить положение антенн.")
            else -> tips.add("ОТЛИЧНЫЙ СИГНАЛ (${wifi.rssi} dBm): уровень приема в норме.")
        }

        if (wifi.freq < 3000) {
            tips.add("ДИАПАЗОН 2.4 ГГц: частота сильно зашумлена соседскими сетями и Bluetooth. Если роутер умеет 5 ГГц — переключитесь на него, либо в настройках роутера выберите свободный канал (1, 6 или 11).")
        }

        if (routerPing > 15.0) {
            tips.add("ВЫСОКИЙ ПИНГ ДО РОУТЕРА (${routerPing} мс): проблема в Wi-Fi канале между телефоном и роутером (помехи или перегрузка роутера — попробуйте перезагрузить роутер).")
        } else if (routerPing in 0.0..15.0 && internetPing > 80.0) {
            tips.add("ЗАДЕРЖКА ДАЛЬШЕ РОУТЕРА: до роутера отклик хороший (${routerPing} мс), но до 1.1.1.1 пинг ${internetPing} мс. Проверьте, не активен ли VPN/прокси или фоновая загрузка.")
        }

        if (wifi.linkSpeed > 0 && download > 0 && download < wifi.linkSpeed * 0.35) {
            tips.add("ПОТЕРЯ СКОРОСТИ: канал заявлен на ${wifi.linkSpeed} Мбит/с, но скачивание всего ${String.format("%.1f", download)} Мбит/с. Причина — помехи в эфире или лимит скорости у провайдера.")
        }

        return tips.joinToString("\n\n")
    }

    private fun log(text: String) {
        tvTerminal.append(text + "\n")
    }
}
