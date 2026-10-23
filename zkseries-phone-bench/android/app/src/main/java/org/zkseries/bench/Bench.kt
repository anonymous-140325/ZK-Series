package org.zkseries.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the JVM harnesses on a handset.
 *
 * The protocol classes are the very same files the desktop compiles, consumed
 * in place through sourceSets in app/build.gradle.kts. Nothing here reimplements
 * any part of the protocol; this file only arranges for those classes to be able
 * to run under ART, captures what they print, and records the phone's thermal state.
 */
object Bench {

    /**
     * Timing policy. These reach the harnesses as system properties, which they read
     * when they start; the number of repetitions is the harness's own argument.
     */
    var burnInMs = 15_000L
    var discard = 5
    var seed = 100_001L

    private fun applyTimingPolicy() {
        System.setProperty("bench.burnInMs", burnInMs.toString())
        System.setProperty("bench.discard", discard.toString())
        System.setProperty("bench.seed", seed.toString())
    }

    // ---- thermal state ----------------------------------------------------------------

    /** A snapshot of the phone's thermal state; every value that cannot be read is "unavailable". */
    data class Thermal(val status: Int?, val batteryTenthsC: Int?, val curKhz: List<String>, val maxKhz: List<String>) {
        fun line(): String =
            "thermal_status=${status ?: "unavailable"} (0 = none, 1 = light, 2 = moderate, 3+ = throttling)" +
                ", battery_temp_c=${batteryTenthsC?.let { "%.1f".format(Locale.US, it / 10.0) } ?: "unavailable"}" +
                ", cpu_cur_khz=${curKhz.joinToString(";")}" +
                ", cpu_max_khz=${maxKhz.joinToString(";")}"
    }

    fun thermal(ctx: Context): Thermal {
        val status = try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } catch (t: Throwable) { null }
        val battery = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
        } catch (t: Throwable) { null }
        val cores = Runtime.getRuntime().availableProcessors()
        fun read(i: Int, name: String): String = try {
            File("/sys/devices/system/cpu/cpu$i/cpufreq/$name").readText().trim()
        } catch (t: Throwable) { "unavailable" }
        // scaling_max_freq is the current cap: below the hardware maximum when the phone throttles.
        return Thermal(status, battery, (0 until cores).map { read(it, "scaling_cur_freq") },
            (0 until cores).map { read(it, "scaling_max_freq") })
    }

    /**
     * Waits at least minSeconds, then until the thermal status is NONE and the battery is at most
     * 1.0 C above baselineTenthsC (if both can be read), or until maxSeconds have passed.
     */
    fun coolDown(ctx: Context, baselineTenthsC: Int?, minSeconds: Int, maxSeconds: Int, log: (String) -> Unit) {
        val start = System.currentTimeMillis()
        fun elapsed() = ((System.currentTimeMillis() - start) / 1000).toInt()
        Thread.sleep(minSeconds * 1000L)
        while (true) {
            val t = thermal(ctx)
            val statusOk = t.status == null || t.status == PowerManager.THERMAL_STATUS_NONE
            val batteryOk = baselineTenthsC == null || t.batteryTenthsC == null || t.batteryTenthsC <= baselineTenthsC + 10
            if (statusOk && batteryOk) { log("COOLDOWN done after ${elapsed()} s: ${t.line()}"); return }
            if (elapsed() >= maxSeconds) { log("COOLDOWN TIMEOUT after ${elapsed()} s, continuing: ${t.line()}"); return }
            Thread.sleep(15_000L)
        }
    }

    // ---- provenance ---------------------------------------------------------------------

    private fun provenance(ctx: Context): String {
        val rt = Runtime.getRuntime()
        val sb = StringBuilder()
        sb.appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("soc           : ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
        sb.appendLine("android       : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("abi           : ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("cores         : ${rt.availableProcessors()}")
        sb.appendLine("max heap      : ${rt.maxMemory() / (1024 * 1024)} MB")
        sb.appendLine("sustained perf: ${sustainedPerformance(ctx)}")
        sb.appendLine("timing policy : burnIn=${burnInMs}ms discard=$discard seed=$seed")
        sb.appendLine("build variant : ${if (isDebuggable(ctx)) "DEBUG  <-- DO NOT REPORT THESE NUMBERS" else "release"}")
        sb.appendLine("timestamp     : ${stamp("yyyy-MM-dd HH:mm:ss")}")
        return sb.toString()
    }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun sustainedPerformance(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isSustainedPerformanceModeSupported) "supported" else "NOT SUPPORTED (expect drift)"
    } catch (t: Throwable) {
        "unknown"
    }

    private fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // ---- running one harness ------------------------------------------------------------

    /**
     * Run one harness by class name, capturing everything it writes to stdout and stderr,
     * with the thermal state before and after. Returns the captured text; also writes it to
     * the app's external files dir, which `adb pull` can retrieve.
     */
    fun run(ctx: Context, harness: String, args: Array<String>, threads: Int, onLine: (String) -> Unit): String {
        System.setProperty("bppp.threads", threads.toString())   // read by the BP++ harnesses at start
        applyTimingPolicy()

        val captured = ByteArrayOutputStream()
        val header = provenance(ctx) +
            "thread cap    : $threads (BP++ harnesses only)\n" +
            "arguments     : ${args.joinToString(" ")}\n" +
            "PHONE_STATE before: ${thermal(ctx).line()}\n"

        val oldOut = System.out
        val oldErr = System.err
        val stream = PrintStream(TeeStream(captured, onLine), true)

        try {
            System.setOut(stream)
            System.setErr(stream)
            println(header)
            // A bare name is an experiments.* harness from the ZK-Series tree; a
            // qualified name (bppp.AuthBench) comes from bppp-java-zkseries.
            val cls = Class.forName(if ('.' in harness) harness else "experiments.$harness")
            val main = cls.getMethod("main", Array<String>::class.java)
            main.invoke(null, args)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            stream.println("\nFAILED: ${cause}")
            cause.stackTrace.take(25).forEach { stream.println("    at $it") }
        } finally {
            stream.println("PHONE_STATE after: ${thermal(ctx).line()}")
            System.setOut(oldOut)
            System.setErr(oldErr)
        }

        val text = captured.toString("UTF-8")
        writeFile(ctx, "$harness-t$threads-${stamp("yyyyMMdd-HHmmss")}.txt", text)
        return text
    }

    // ---- the final run ------------------------------------------------------------------

    data class Step(val label: String, val harness: String, val args: String, val threads: Int)

    /**
     * The measurement plan for the paper. Range proofs and the Bulletproofs baseline as one
     * step each; the authentication proof one series length T per step, so that the phone
     * cools down between steps. Every BP++ step runs with 1 and with 8 threads.
     */
    val finalPlan: List<Step> = buildList {
        add(Step("checks, 1 thread", "bppp.Check", "", 1))
        add(Step("checks, 8 threads", "bppp.Check", "", 8))
        add(Step("Bulletproofs baseline", "BulletproofsBench", "20 50,100,150,200,250,300", 1))
        for (t in listOf(1, 8)) add(Step("BP++ range, $t thread(s)", "bppp.AuthBench", "range 20 50,100,150,200,250,300", t))
        for (T in listOf(50, 97, 100, 150, 200, 250, 300))
            for (t in listOf(1, 8)) add(Step("BP++ authentication T=$T, $t thread(s)", "bppp.AuthBench", "auth 10 $T", t))
    }

    /**
     * The plan for Tables 5 and 6, with readings quantized to 16 bits. Table 5 (range proofs):
     * Bulletproofs' dedicated range proof, the unoptimized Bulletproofs++ (a straight
     * translation, bppp-java-unoptimized), and the optimized one with 1 and 8 threads. Table 6:
     * the case studies at their largest circuits, and the DTW/d_1 grid, one T per step so that
     * the phone cools down in between; all with 1 and 8 threads. One session for everything.
     */
    val tablesPlan: List<Step> = buildList {
        for (t in listOf(1, 8)) add(Step("checks, BP++ optimized, $t thread(s)", "bppp.Check", "", t))
        add(Step("checks, BP++ unoptimized", "bpppref.Check", "", 1))
        add(Step("checks, Bulletproofs dedicated range proof", "DedicatedRangeProofCheck", "", 1))
        add(Step("Bulletproofs dedicated range proof", "BulletproofsBench", "native 20 50,100,150,200,250,300", 1))
        add(Step("BP++ unoptimized range", "bpppref.AuthBench", "range 20 50,100,150,200,250,300", 1))
        for (t in listOf(1, 8)) add(Step("BP++ optimized range, $t thread(s)", "bppp.AuthBench", "range 20 50,100,150,200,250,300", t))
        for (t in listOf(1, 8)) add(Step("case studies, $t thread(s)", "bppp.AuthBench", "cases 10", t))
        for (T in listOf(50, 97, 100, 150, 200, 250, 300))
            for (t in listOf(1, 8)) add(Step("DTW d1 T=$T, $t thread(s)", "bppp.AuthBench", "dtw1 10 $T", t))
    }

    /**
     * Runs a plan with a cooldown before every step, and writes a session log (<TAG>-*.txt)
     * listing every step, its result file, and the thermal state. Stops at the first failure.
     */
    fun finalRun(ctx: Context, onLine: (String) -> Unit) = planRun(ctx, finalPlan, "FINAL RUN", "FINAL-RUN", onLine)

    fun tablesRun(ctx: Context, onLine: (String) -> Unit) = planRun(ctx, tablesPlan, "TABLES RUN", "TABLES-RUN", onLine)

    /**
     * The case-study rows of Table 6 for all three case studies (blow, shake, facial landmarks),
     * at their largest circuits, with 1 and 8 threads, in one short session.
     */
    val casesPlan: List<Step> = buildList {
        for (t in listOf(1, 8)) add(Step("checks, BP++ optimized, $t thread(s)", "bppp.Check", "", t))
        for (t in listOf(1, 8)) add(Step("case studies, $t thread(s)", "bppp.AuthBench", "cases 10", t))
    }

    fun casesRun(ctx: Context, onLine: (String) -> Unit) = planRun(ctx, casesPlan, "CASES RUN", "CASES-RUN", onLine)

    private fun planRun(ctx: Context, plan: List<Step>, title: String, tag: String, onLine: (String) -> Unit) {
        val log = StringBuilder()
        val name = "$tag-${stamp("yyyyMMdd-HHmmss")}.txt"
        fun note(s: String) { log.appendLine(s); onLine(s); writeFile(ctx, name, log.toString()) }
        val baseline = thermal(ctx)
        note(provenance(ctx).trimEnd())
        note("$title: ${plan.size} steps; cooldown before each: >= 120 s, then until thermal status 0")
        note("and battery <= start + 1.0 C, at most 900 s")
        note("PHONE_STATE start: ${baseline.line()}")
        for ((i, step) in plan.withIndex()) {
            note("STEP ${i + 1}/${plan.size} ${step.label}: cooling down ...")
            coolDown(ctx, baseline.batteryTenthsC, 120, 900) { note(it) }
            note("STEP ${i + 1}/${plan.size} ${step.label}: start ${stamp("HH:mm:ss")}")
            val args = step.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray()
            val text = run(ctx, step.harness, args, step.threads) { onLine(it) }
            val failed = "FAILED" in text
            note("STEP ${i + 1}/${plan.size} ${step.label}: ${if (failed) "FAILED" else "ok"} ${stamp("HH:mm:ss")}")
            if (failed) { note("$title STOPPED at step ${i + 1}"); return }
        }
        note("$title COMPLETE ${stamp("yyyy-MM-dd HH:mm:ss")}")
    }

    private fun writeFile(ctx: Context, name: String, text: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, name).writeText(text)
        } catch (t: Throwable) {
            // Losing the file is not worth losing the run over; the text is already on screen.
        }
    }

    /** Mirrors bytes into a buffer and, line by line, to the UI. */
    private class TeeStream(
        private val sink: OutputStream,
        private val onLine: (String) -> Unit
    ) : OutputStream() {
        private val line = StringBuilder()

        override fun write(b: Int) {
            sink.write(b)
            when (b) {
                '\n'.code -> {
                    onLine(line.toString())
                    line.setLength(0)
                }
                '\r'.code -> {
                    // swallow
                }
                else -> line.append(Char(b))
            }
        }
    }
}
