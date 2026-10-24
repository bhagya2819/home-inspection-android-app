package com.griha.inspection.feature.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.griha.inspection.BuildConfig

/**
 * The admin map: Leaflet + OpenStreetMap in a WebView (user decision: no Google Maps SDK). The page
 * and Leaflet are bundled in assets; only map tiles come from tile.openstreetmap.org. [script] is a
 * JavaScript call (e.g. `showTeam([...], true)`) run whenever it changes, once the page is ready.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMap(script: String?, modifier: Modifier = Modifier, onPerson: (String) -> Unit = {}) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val onPersonNow by rememberUpdatedState(onPerson)
    val web = remember {
        WebView(context).apply {
            // Fill the Compose box: with the default WRAP_CONTENT the page's height is its content's,
            // so the map (height: 100%) collapses to 0 px and nothing shows.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // OSM's tile policy asks apps to identify themselves.
            settings.userAgentString = settings.userAgentString + " Griha-Android/${BuildConfig.VERSION_NAME}"
            webViewClient = object : WebViewClient() {
                // Links (the OSM copyright notice) never navigate the map away.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            val main = Handler(Looper.getMainLooper())
            addJavascriptInterface(object {
                @JavascriptInterface fun onReady() { main.post { ready = true } }
                @JavascriptInterface fun onPerson(id: String) { main.post { onPersonNow(id) } }
            }, "Android")
            // The base URL is only the Referer the tile server sees; nothing is loaded from it.
            loadDataWithBaseURL("https://grihahomeinspection.in/app-map/", page(context), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }
    LaunchedEffect(ready, script) { if (ready && script != null) web.evaluateJavascript(script, null) }
    AndroidView(factory = { web }, modifier = modifier)
}

private var cachedPage: String? = null

private fun page(context: Context): String = cachedPage ?: run {
    fun asset(name: String) = context.assets.open("map/$name").bufferedReader().use { it.readText() }
    asset("index.html")
        .replace("/*LEAFLET_CSS*/", asset("leaflet.css"))
        .replace("/*LEAFLET_JS*/", asset("leaflet.js"))
        .also { cachedPage = it }
}

/** A JavaScript string literal for embedding data in a [LeafletMap] script. */
fun jsString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '<' -> append("\\u003c")
        else -> if (c < ' ' || c == ' ' || c == ' ') append(String.format("\\u%04x", c.code)) else append(c)
    }
    append('"')
}
