package com.davidpurkiss.videoeditor

import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FFmpegBridge(
    private val jsCallback: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "FFmpegBridge"
        private const val FFMPEG_BIN = "ffmpeg"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var ffmpegPath: String? = null

    fun initBinary(onReady: (Boolean) -> Unit) {
        executor.execute {
            try {
                // 1. Check previously working binary
                val candidates = listOf(
                    File("/data/local/tmp/$FFMPEG_BIN"),
                    File(context.codeCacheDir, FFMPEG_BIN)
                )
                for (bin in candidates) {
                    if (bin.exists() && bin.canExecute() && bin.length() > 1000) {
                        // Verify it actually works
                        try {
                            val p = Runtime.getRuntime().exec(arrayOf(bin.absolutePath, "-version"))
                            p.waitFor()
                            if (p.exitValue() == 0) {
                                ffmpegPath = bin.absolutePath
                                Log.d(TAG, "Cached ffmpeg works: $ffmpegPath")
                                onReady(true)
                                return@execute
                            }
                        } catch (e: Exception) {
                            bin.delete() // Remove broken binary
                        }
                    }
                }

                // 2. Extract bundled binary for device ABI
                // Try /data/local/tmp first (guaranteed executable), then codeCache
                val targets = listOf(
                    File("/data/local/tmp"),
                    context.codeCacheDir
                )

                for (targetDir in targets) {
                    val dest = File(targetDir, FFMPEG_BIN)
                    if (dest.exists() && dest.length() > 1000) continue // Already extracted

                    var extracted = false
                    for (abi in android.os.Build.SUPPORTED_ABIS) {
                        val assetName = "ffmpeg/$abi/$FFMPEG_BIN"
                        try {
                            context.assets.open(assetName).use { input ->
                                targetDir.mkdirs()
                                FileOutputStream(dest).use { out ->
                                    val buf = ByteArray(8192)
                                    var bytes: Int
                                    while (input.read(buf).also { bytes = it } >= 0) {
                                        out.write(buf, 0, bytes)
                                    }
                                }
                            }
                            if (dest.exists() && dest.length() > 1000) {
                                dest.setExecutable(true, false)
                                dest.setReadable(true, false)
                                Log.d(TAG, "Extracted to ${targetDir}: $abi (${dest.length()} bytes)")
                                extracted = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "No asset for $abi in $targetDir")
                        }
                    }

                    if (extracted && dest.exists()) {
                        // Test it
                        val works = try {
                            val p = Runtime.getRuntime().exec(arrayOf(dest.absolutePath, "-version"))
                            p.waitFor()
                            p.exitValue() == 0
                        } catch (e: Exception) { false }

                        if (works) {
                            ffmpegPath = dest.absolutePath
                            Log.d(TAG, "ffmpeg works from $targetDir")
                            onReady(true)
                            return@execute
                        }
                    }
                }

                // 3. Last resort: try via system linker (bypasses SELinux on some devices)
                for (bin in candidates) {
                    if (bin.exists() && bin.length() > 1000) {
                        try {
                            val p = Runtime.getRuntime().exec(
                                arrayOf("/system/bin/linker64", bin.absolutePath, "-version"))
                            p.waitFor()
                            if (p.exitValue() == 0) {
                                ffmpegPath = "/system/bin/linker64 ${bin.absolutePath}"
                                Log.d(TAG, "Using linker64 wrapper")
                                onReady(true)
                                return@execute
                            }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }

                Log.e(TAG, "No executable ffmpeg found. ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                onReady(false)
            } catch (e: Exception) {
                Log.e(TAG, "initBinary error", e)
                onReady(false)
            }
        }
    }

    private fun tokenize(cmd: String): Array<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in cmd) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result.toTypedArray()
    }

    @JavascriptInterface
    fun exec(command: String, callbackId: String) {
        val binPath = ffmpegPath
        if (binPath == null) {
            jsCallback("window._ffmpegCallback('$callbackId', {success:false, output:'', error:'ffmpeg no disponible'});")
            return
        }

        executor.execute {
            try {
                val args = tokenize(command.trim())
                val isLinker = binPath.startsWith("/system/bin/linker64")

                val cmdArgs: Array<String>
                val logCmd: String
                if (isLinker) {
                    val realBin = binPath.removePrefix("/system/bin/linker64 ").trim()
                    cmdArgs = arrayOf("/system/bin/linker64", realBin) + args
                    logCmd = "linker64 $realBin ${args.joinToString(" ")}"
                } else {
                    cmdArgs = arrayOf(binPath) + args
                    logCmd = "$binPath ${args.joinToString(" ")}"
                }

                Log.d(TAG, "exec: $logCmd")
                onLog(logCmd)

                val pb = ProcessBuilder(*cmdArgs)
                if (binPath.contains("termux")) {
                    pb.environment()["LD_LIBRARY_PATH"] = "/data/data/com.termux/files/usr/lib"
                    pb.environment()["HOME"] = "/data/data/com.termux/files/home"
                }
                pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
                pb.redirectErrorStream(true)

                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()

                val success = exitCode == 0
                val clean = output
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "")

                Log.d(TAG, "exec done: exit=$exitCode, len=${output.length}")
                val js = "window._ffmpegCallback('$callbackId', {success:$success, output:'$clean', error:''});"
                jsCallback(js)
            } catch (e: Exception) {
                Log.e(TAG, "exec exception", e)
                val msg = (e.message ?: "error").replace("\\", "\\\\").replace("'", "\\'")
                jsCallback("window._ffmpegCallback('$callbackId', {success:false, output:'', error:'$msg'});")
            }
        }
    }
}
