package com.toolkit.privacy

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * AppForge Offline, running inside the app next to the HTML Runner.
 * The page (assets/appforge/index.html) is served to the WebView from a private https origin, so its saved
 * apps and settings stay separate from every HTML Runner project. Nothing is downloaded: it all runs offline.
 */
private const val AF_HOST = "appforge.local"
private const val AF_URL = "https://$AF_HOST/index.html"

/** Small shim: lets the page save its blob downloads, share, print and open links through the app. */
private const val SHIM = """(function(){if(window.__afShim)return;window.__afShim=1;
var M={},cu=URL.createObjectURL.bind(URL);
URL.createObjectURL=function(b){var u=cu(b);M[u]=b;return u};
var ck=HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click=function(){var a=this;
if(a.download&&M[a.href]){var b=M[a.href],r=new FileReader();
r.onload=function(){try{AFNative.save(a.download,b.type||'application/octet-stream',String(r.result).split(',')[1]||'')}catch(e){}};
r.readAsDataURL(b);return}
return ck.apply(this,arguments)};
window.print=function(){try{AFNative.print()}catch(e){}};
try{Object.defineProperty(navigator,'share',{configurable:true,value:function(d){try{AFNative.share(((d&&d.title)?d.title+'\n':'')+((d&&d.text)||'')+((d&&d.url)?'\n'+d.url:''))}catch(e){}return Promise.resolve()}})}catch(e){}
window.open=function(u){try{AFNative.open(String(u||''))}catch(e){}return null};
})();"""

private class AfBridge(private val web: () -> WebView?, private val ctx: android.content.Context) {
    private val ui = Handler(Looper.getMainLooper())
    private fun toast(s: String) = ui.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }

    @JavascriptInterface fun save(name: String, mime: String, b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val fn = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "appforge.txt" }
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fn)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AppForge")
                }
                val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                ctx.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AppForge").apply { mkdirs() }
                File(dir, fn).writeBytes(bytes)
            }
            toast("Saved to Downloads/AppForge/$fn")
        } catch (e: Exception) { toast("Could not save: ${e.message}") }
    }

    @JavascriptInterface fun share(text: String) {
        ui.post {
            try {
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface fun print() {
        ui.post {
            try {
                val w = web() ?: return@post
                val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
                pm.print("AppForge", w.createPrintDocumentAdapter("AppForge"), PrintAttributes.Builder().build())
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface fun open(url: String) {
        ui.post {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppForgeScreen() {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var fileCb by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pending by remember { mutableStateOf<PermissionRequest?>(null) }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    fun grantFor(req: PermissionRequest) {
        val ok = req.resources.filter {
            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ||
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }.toTypedArray()
        if (ok.isEmpty()) req.deny() else req.grant(ok)
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { grantFor(it) }
        pending = null
    }

    BackHandler(enabled = canBack) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.textZoom = 100
                addJavascriptInterface(AfBridge({ web }, c.applicationContext), "AFNative")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url
                        if (u.host == AF_HOST) {
                            return try {
                                WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), c.assets.open("appforge/index.html"))
                            } catch (e: Exception) { null }
                        }
                        return null
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == AF_HOST) return false
                        try { c.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                        return true
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { view.evaluateJavascript(SHIM, null) }
                    override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(SHIM, null) }
                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canBack = view.canGoBack() }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                        fileCb?.onReceiveValue(null)
                        fileCb = cb
                        return try { chooser.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; cb.onReceiveValue(null); false }
                    }
                    override fun onPermissionRequest(req: PermissionRequest) {
                        val need = mutableListOf<String>()
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in req.resources) need += Manifest.permission.RECORD_AUDIO
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in req.resources) need += Manifest.permission.CAMERA
                        val missing = need.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                        if (missing.isEmpty()) grantFor(req) else { pending = req; perms.launch(missing.toTypedArray()) }
                    }
                }
                loadUrl(AF_URL)
                web = this
            }
        }
    )
}
