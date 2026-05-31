package com.davidpurkiss.videoeditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var ffmpegBridge: FFmpegBridge
    private lateinit var fileBridge: FileBridge

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                webView.reload()
            } else {
                Toast.makeText(this, "Se necesitan permisos", Toast.LENGTH_LONG).show()
            }
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            fileBridge.handleVideoResult(uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init FFmpeg bridge
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            val jsLog = fun(msg: String) {
                val safe = msg.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                runOnUiThread {
                    evaluateJavascript(
                        "var b=document.getElementById('logBox');if(b){b.textContent+=('$safe'+'\\n');b.scrollTop=b.scrollHeight;}",
                        null
                    )
                }
            }

            val jsCallback = fun(js: String) {
                runOnUiThread { evaluateJavascript(js, null) }
            }

            ffmpegBridge = FFmpegBridge(jsCallback, jsLog, applicationContext)
            fileBridge = FileBridge()
            val configBridge = ConfigBridge()

            addJavascriptInterface(ffmpegBridge, "FFmpegBridge")
            addJavascriptInterface(fileBridge, "FileBridge")
            addJavascriptInterface(configBridge, "ConfigBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    ffmpegBridge.initBinary { ok ->
                        runOnUiThread {
                            if (!ok) {
                                evaluateJavascript(
                                    "document.getElementById('errorCard').classList.add('show'); document.getElementById('errorCard').textContent = '⚠ FFmpeg no disponible. Instala Termux con ffmpeg o contacta soporte.';",
                                    null
                                )
                            }
                        }
                    }
                }
            }

            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/www/index.html")
        }

        setContentView(webView)
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    inner class FileBridge {
        private var pendingCallback: String? = null
        private var videoPath: String? = null
        private var videoName: String? = null
        private var videoSize: Long = 0

        @JavascriptInterface
        fun pickVideo(callbackId: String) {
            pendingCallback = callbackId
            videoPickerLauncher.launch(arrayOf("video/*"))
        }

        fun handleVideoResult(uri: Uri?) {
            val cbId = pendingCallback ?: return

            if (uri == null) {
                pendingCallback = null
                val js = "window._fileBridgeCallback('$cbId', {error:'Selecci\u00f3n cancelada'});"
                webView.post { webView.evaluateJavascript(js, null) }
                return
            }

            Thread {
                try {
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    val name = cursor?.use {
                        if (it.moveToFirst()) {
                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) it.getString(idx) else "video.mp4"
                        } else "video.mp4"
                    } ?: "video.mp4"

                    val workDir = File(filesDir, "jobs")
                    workDir.mkdirs()
                    val dest = File(workDir, "source_${System.currentTimeMillis()}.mp4")

                    val inputStream = contentResolver.openInputStream(uri)
                        ?: run {
                            webView.post {
                                webView.evaluateJavascript(
                                    "window._fileBridgeCallback('$cbId', {error:'No se pudo abrir el archivo'});",
                                    null
                                )
                            }
                            pendingCallback = null
                            return@Thread
                        }

                    val totalBytes = dest.length()
                    inputStream.use { input ->
                        FileOutputStream(dest).use { output ->
                            val buf = ByteArray(8192)
                            var bytes: Int
                            var copied: Long = 0
                            while (input.read(buf).also { bytes = it } >= 0) {
                                output.write(buf, 0, bytes)
                                copied += bytes
                                // Progress update every ~1MB
                                if (copied % 1048576 == 0L) {
                                    val pct = if (totalBytes > 0) (copied * 100 / totalBytes).toInt() else -1
                                    webView.post {
                                        webView.evaluateJavascript(
                                            "var s=document.getElementById('progressStatus');if(s)s.textContent='Copiando video... ${copied/1048576}MB';",
                                            null
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val path = dest.absolutePath
                    val size = dest.length()

                    // Proper JS escaping
                    val escapedPath = escapeJsString(path)
                    val escapedName = escapeJsString(name)

                    val js = "window._fileBridgeCallback('$cbId', {path:'$escapedPath', name:'$escapedName', size:$size});"
                    android.util.Log.d("VideoEditor", "handleVideoResult: path=$path, name=$name, size=$size")
                    webView.post { webView.evaluateJavascript(js, null) }

                } catch (e: Exception) {
                    android.util.Log.e("VideoEditor", "handleVideoResult error", e)
                    val em = escapeJsString(e.message ?: "error")
                    webView.post {
                        webView.evaluateJavascript(
                            "window._fileBridgeCallback('$cbId', {error:'$em'});",
                            null
                        )
                    }
                } finally {
                    pendingCallback = null
                }
            }.start()
        }

        private fun escapeJsString(s: String): String {
            return s.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
        }

        @JavascriptInterface
        fun getWorkDir(): String {
            val dir = File(filesDir, "jobs")
            dir.mkdirs()
            return dir.absolutePath
        }

        @JavascriptInterface
        fun saveToDownloads(sourcePath: String, fileName: String, callbackId: String) {
            Thread {
                try {
                    val src = File(sourcePath)
                    if (!src.exists()) {
                        webView.post {
                            webView.evaluateJavascript(
                                "window._fileBridgeCallback('$callbackId', {error:'Archivo no encontrado'});",
                                null
                            )
                        }
                        return@Thread
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                        }
                        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        u?.let {
                            contentResolver.openOutputStream(it)?.use { out ->
                                src.inputStream().use { inp -> inp.copyTo(out) }
                            }
                        }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        dir.mkdirs()
                        src.copyTo(File(dir, fileName), overwrite = true)
                    }

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Video guardado en Películas", Toast.LENGTH_LONG).show()
                    }
                    webView.post {
                        webView.evaluateJavascript(
                            "window._fileBridgeCallback('$callbackId', {ok:true});",
                            null
                        )
                    }
                } catch (e: Exception) {
                    val em = e.message?.replace("'", "\\'") ?: "error"
                    webView.post {
                        webView.evaluateJavascript(
                            "window._fileBridgeCallback('$callbackId', {error:'$em'});",
                            null
                        )
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun deleteFile(path: String) {
            File(path).delete()
        }

        @JavascriptInterface
        fun writeTextFile(path: String, content: String) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(content)
        }

        @JavascriptInterface
        fun uploadAudio(audioPath: String, serverUrl: String, language: String, apiKey: String, callbackId: String) {
            Thread {
                try {
                    val file = File(audioPath)
                    if (!file.exists()) {
                        webView.post {
                            webView.evaluateJavascript(
                                "window._fileBridgeCallback('$callbackId', {error:'Audio no encontrado'});",
                                null
                            )
                        }
                        return@Thread
                    }

                    val boundary = "----VE" + System.currentTimeMillis()
                    val url = java.net.URL(serverUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.setRequestProperty("X-API-Key", apiKey)
                    conn.connectTimeout = 300000
                    conn.readTimeout = 300000

                    val out = conn.outputStream
                    val crlf = "\r\n"
                    val d2 = "--"

                    out.write("$d2$boundary$crlf".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"$crlf".toByteArray())
                    out.write("Content-Type: audio/wav$crlf$crlf".toByteArray())
                    out.write(file.readBytes())
                    out.write(crlf.toByteArray())

                    if (language.isNotEmpty()) {
                        out.write("$d2$boundary$crlf".toByteArray())
                        out.write("Content-Disposition: form-data; name=\"language\"$crlf$crlf".toByteArray())
                        out.write("$language$crlf".toByteArray())
                    }

                    out.write("$d2$boundary$d2$crlf".toByteArray())
                    out.flush()
                    out.close()

                    val code = conn.responseCode
                    val body = if (code in 200..299)
                        conn.inputStream.bufferedReader().readText()
                    else
                        conn.errorStream?.bufferedReader()?.readText() ?: "error $code"
                    conn.disconnect()

                    val esc = escapeJsString(body)
                    webView.post {
                        webView.evaluateJavascript(
                            "window._fileBridgeCallback('$callbackId', {status:$code, body:'$esc'});",
                            null
                        )
                    }
                } catch (e: Exception) {
                    val em = e.message?.replace("'", "\\'") ?: "error"
                    webView.post {
                        webView.evaluateJavascript(
                            "window._fileBridgeCallback('$callbackId', {error:'$em'});",
                            null
                        )
                    }
                }
            }.start()
        }
    }

    inner class ConfigBridge {
        @JavascriptInterface
        fun getApiKey(): String = BuildConfig.MOBILE_API_KEY
    }
}
