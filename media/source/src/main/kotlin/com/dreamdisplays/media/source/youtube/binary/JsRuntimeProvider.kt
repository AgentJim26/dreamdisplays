package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Provides a JavaScript runtime for yt-dlp (needed for YouTube signature
 * challenges, e.g. "made for kids" videos).
 *
 * Order: explicit override -> system deno/node/bun on PATH -> download
 * a pinned Deno build on demand (same pattern as FFmpeg/yt-dlp).
 * Returns extra yt-dlp CLI args, or empty when no runtime is available.
 */
object JsRuntimeProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val DENO_VERSION = "v2.5.4"
    private const val DENO_BASE = "https://github.com/denoland/deno/releases/download/"
    private val bundledDir: Path = Path.of("libs", "js-runtime")

    @Volatile
    private var cached: List<String>? = null

    fun args(): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val resolved = resolve()
            cached = resolved
            return resolved
        }
    }

    private fun resolve(): List<String> {
        // 1. Explicit override: -Ddreamdisplays.jsruntime=/path/to/deno or deno|node|bun
        val override = System.getProperty("dreamdisplays.jsruntime")?.ifEmpty { null }
            ?: System.getenv("DREAMDISPLAYS_JS_RUNTIME")?.ifEmpty { null }
        if (override != null) {
            val f = File(override)
            if (f.isFile && f.canExecute()) {
                val kind = guessKind(override)
                logger.info("Using JS runtime override: {} ({}).", override, kind)
                return listOf("--js-runtimes", "$kind:$override")
            }
            if (override in listOf("deno", "node", "bun", "qjs")) {
                findOnPath(override)?.let { return listOf("--js-runtimes", "$override:$it") }
                logger.warn("JS runtime override '{}' not found on PATH.", override)
            } else {
                logger.warn("JS runtime override '{}' is not executable.", override)
            }
        }

        // 2. Auto-detect what's already installed.
        for (kind in listOf("deno", "node", "bun")) {
            findOnPath(kind)?.let {
                logger.info("Using system JS runtime: {} ({}).", kind, it)
                return listOf("--js-runtimes", "$kind:$it")
            }
        }

        // 3. Download a pinned Deno build on demand.
        return runCatching {
            val deno = provisionDeno()
            logger.info("Using bundled Deno JS runtime: {}.", deno)
            listOf("--js-runtimes", "deno:$deno")
        }.onFailure { e ->
            logger.warn(
                "No JavaScript runtime found (tried deno/node/bun on PATH, bundled Deno download failed: {}). " +
                    "Some YouTube videos will fail; install Deno (https://deno.land) and restart.",
                e.message,
            )
        }.getOrDefault(emptyList())
    }

    private fun guessKind(path: String): String {
        val n = path.lowercase()
        return when {
            "bun" in n -> "bun"
            "node" in n -> "node"
            "qjs" in n -> "qjs"
            else -> "deno"
        }
    }

    private fun findOnPath(exe: String): String? {
        val names = if (OsInfo.isWindows && !exe.endsWith(".exe")) listOf("$exe.exe", exe) else listOf(exe)
        for (name in names) {
            runCatching {
                val p = ProcessBuilder(if (OsInfo.isWindows) listOf("where", name) else listOf("which", name))
                    .redirectErrorStream(true).start()
                val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) {
                    val first = out.lineSequence().first().trim()
                    if (first.isNotEmpty() && File(first).canExecute()) return first
                    // `where`/`which` succeeded: trust the exe name itself.
                    if (probeVersion(name)) return name
                } else if (probeVersion(name)) {
                    return name
                }
            }
        }
        // Last resort: direct probe (relies on PATH lookup by ProcessBuilder).
        return if (probeVersion(exe)) exe else null
    }

    private fun probeVersion(exe: String): Boolean {
        return runCatching {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
            Processes.drainAsync(p.inputStream)
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun provisionDeno(): String {
        val exeName = if (OsInfo.isWindows) "deno.exe" else "deno"
        val dest = bundledDir.resolve(exeName)
        if (dest.toFile().isFile && dest.toFile().canExecute()) return dest.toString()
        val asset = denoAsset()
        SourceLoader.downloadAndExtract(DENO_BASE + DENO_VERSION + "/" + asset, bundledDir, "Deno") { name ->
            File(name).name.takeIf { it == exeName || it == "deno" || it == "deno.exe" }
                ?.let { exeName }
        }
        val f = dest.toFile()
        if (!f.isFile || f.length() == 0L) throw java.io.IOException("Extracted Deno binary is missing or empty.")
        SourceLoader.makeExecutable(dest)
        return dest.toString()
    }

    private fun denoAsset(): String = when {
        OsInfo.isWindows -> "deno-x86_64-pc-windows-msvc.zip"
        OsInfo.isMac && OsInfo.isArm64 -> "deno-aarch64-apple-darwin.zip"
        OsInfo.isMac -> "deno-x86_64-apple-darwin.zip"
        OsInfo.isArm64 -> "deno-aarch64-unknown-linux-gnu.zip"
        else -> "deno-x86_64-unknown-linux-gnu.zip"
    }

    /** For diagnostics: which runtime (if any) would be used, without triggering a download. */
    fun describe(): String =
        cached?.joinToString(" ")
            ?: (System.getProperty("dreamdisplays.jsruntime") ?: System.getenv("DREAMDISPLAYS_JS_RUNTIME")
            ?: "auto (deno/node/bun on PATH, else bundled Deno $DENO_VERSION)")
}
