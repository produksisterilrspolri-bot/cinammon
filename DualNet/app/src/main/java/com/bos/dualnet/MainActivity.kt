package com.bos.dualnet

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    @Volatile private var cell: Network? = null
    @Volatile private var wifi: Network? = null
    @Volatile private var eth: Network? = null
    private val lan get() = wifi ?: eth

    private lateinit var status: TextView
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("p", 0) }

    private val privateIp = Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.).*""")

    private fun lanHosts() =
        prefs.getString("lan", "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // Host LAN -> WiFi/Ethernet. Sisanya -> data seluler (jika tidak ada, jatuh ke LAN).
    private fun route(host: String): Network? {
        val isLan = privateIp.matches(host) || host.endsWith(".local") ||
            lanHosts().any { host == it || host.endsWith(".$it") }
        return if (isLan) lan else cell ?: lan
    }

    private fun track(cm: ConnectivityManager, transport: Int, internet: Boolean, set: (Network?) -> Unit) {
        val b = NetworkRequest.Builder().addTransportType(transport)
        if (internet) b.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        cm.requestNetwork(b.build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { set(n); refresh() }
            override fun onLost(n: Network) { set(null); refresh() }
        })
    }

    private fun refresh() = runOnUiThread {
        status.text = "Data: ${if (cell != null) "ON" else "-"}   WiFi/LAN: ${if (lan != null) "ON" else "-"}"
    }

    private fun load(s: String) {
        val t = s.trim()
        if (t.isNotEmpty()) web.loadUrl(if ("://" in t) t else "http://$t")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = EditText(this).apply {
            hint = "alamat web / IP"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val go = Button(this).apply { text = "Go" }
        val lanBtn = Button(this).apply { text = "LAN" }
        status = TextView(this).apply { setPadding(16, 4, 16, 4) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }

        val row = LinearLayout(this).apply {
            addView(url, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(go)
            addView(lanBtn)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(status)
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        val cm = getSystemService(ConnectivityManager::class.java)
        track(cm, NetworkCapabilities.TRANSPORT_CELLULAR, true) { cell = it }
        track(cm, NetworkCapabilities.TRANSPORT_WIFI, false) { wifi = it }
        track(cm, NetworkCapabilities.TRANSPORT_ETHERNET, false) { eth = it }
        refresh()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val port = Proxy(::route).start()
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(), { it.run() }, {}
            )
        } else {
            Toast.makeText(this, "WebView tidak mendukung proxy. Update Android System WebView.", Toast.LENGTH_LONG).show()
        }

        go.setOnClickListener { load(url.text.toString()) }
        lanBtn.setOnClickListener {
            val et = EditText(this).apply {
                setText(prefs.getString("lan", ""))
                hint = "contoh: intranet.rs.local, simrs.lokal"
            }
            AlertDialog.Builder(this)
                .setTitle("Host yang lewat WiFi/LAN (pisah koma)")
                .setMessage("IP 10.x, 192.168.x, 172.16-31.x dan *.local otomatis lewat LAN.")
                .setView(et)
                .setPositiveButton("Simpan") { _, _ -> prefs.edit().putString("lan", et.text.toString()).apply() }
                .show()
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
