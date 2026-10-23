package org.zkseries.bench

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * One button per harness. Tap to run with the arguments shown; long-press to
 * load those arguments into the edit box and read what the harness does.
 *
 * The run happens on a single background thread, never on the main thread, and
 * the screen is held on for the duration: Android demotes background processes
 * to the little cores, and a run that gets descheduled measures the scheduler
 * rather than the protocol.
 */
class BenchActivity : Activity() {

    private data class Harness(val name: String, val args: String, val about: String, val threads: Int = 1)

    private val harnesses = listOf(
        Harness(
            "BulletproofsBench", "20 50,100,150,200,250,300",
            "Bulletproofs rows of Table 6: N values each in [0, 8000], one proof per " +
                "value (one timed, x N) and all N aggregated. Args: <reps> <N values>."
        ),
        Harness(
            "bppp.AuthBench", "auth 3 50,97,100",
            "Smoke version of the authentication harness, 8 threads. Run it first to " +
                "confirm the protocol runs on ART at all.", 8
        ),
        Harness(
            "bppp.AuthBench", "range 20 50,100,150,200,250,300",
            "Bulletproofs++ range rows of Table 6, one thread: N values each in [0, 8000] " +
                "in one proof. Args: range <reps> <N values>.", 1
        ),
        Harness(
            "bppp.AuthBench", "range 20 50,100,150,200,250,300",
            "The same Bulletproofs++ range rows with 8 threads. Proofs are identical; " +
                "only the time differs.", 8
        ),
        Harness(
            "bppp.AuthBench", "auth 10 50,97,100,150,200,250,300",
            "Authentication proof rows of Table 6, one thread, m = 3: sum and DTW with " +
                "d1, d2, dinf, and the time to find the coupling. Each row first checks that " +
                "its proof verifies and that theta = delta is refused. Args: auth <reps> <T values>.", 1
        ),
        Harness(
            "bppp.AuthBench", "auth 10 50,97,100,150,200,250,300",
            "The same authentication rows with 8 threads.", 8
        )
    )

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var argsField: EditText
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(Button(this).apply {
            text = "CASES RUN  (case studies of Table 6, ${Bench.casesPlan.size} steps, about 30 minutes)"
            setOnClickListener { launchPlan { ctx, out -> Bench.casesRun(ctx, out) } }
        })

        root.addView(Button(this).apply {
            text = "TABLES RUN  (Tables 5 and 6, ${Bench.tablesPlan.size} steps, about 3-4 hours)"
            setOnClickListener { launchPlan { ctx, out -> Bench.tablesRun(ctx, out) } }
        })

        root.addView(Button(this).apply {
            text = "FINAL RUN  (all paper measurements, ${Bench.finalPlan.size} steps, several hours)"
            setOnClickListener { launchPlan { ctx, out -> Bench.finalRun(ctx, out) } }
        })

        argsField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "arguments (individual runs below)"
        }
        root.addView(argsField)

        harnesses.forEach { h ->
            root.addView(Button(this).apply {
                text = "${h.name}  ${h.args}  [${h.threads} thread${if (h.threads > 1) "s" else ""}]"
                setOnClickListener { launch(h.name, argsField.text.toString().ifBlank { h.args }, h.threads) }
                setOnLongClickListener {
                    argsField.setText(h.args)
                    Toast.makeText(this@BenchActivity, h.about, Toast.LENGTH_LONG).show()
                    true
                }
            })
        }

        output = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        setContentView(root)
    }

    private fun launch(harness: String, argString: String, threads: Int) {
        if (running) {
            Toast.makeText(this, "a run is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        output.text = ""
        append("running $harness $argString\n")

        val args = argString.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray()

        worker.execute {
            try {
                Bench.run(applicationContext, harness, args, threads) { line -> runOnUiThread { append(line) } }
            } finally {
                running = false
                runOnUiThread { append("\n-- done; written to ${getExternalFilesDir(null)} --") }
            }
        }
    }

    private fun launchPlan(plan: (android.content.Context, (String) -> Unit) -> Unit) {
        if (running) {
            Toast.makeText(this, "a run is already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        output.text = ""
        worker.execute {
            try {
                // Keep only the last lines on screen: the full text goes to the files.
                plan(applicationContext) { line -> runOnUiThread { append(line) } }
            } finally {
                running = false
                runOnUiThread { append("\n-- run ended; files in ${getExternalFilesDir(null)} --") }
            }
        }
    }

    private fun append(line: String) {
        if (output.length() > 200_000) output.text = output.text.subSequence(100_000, output.length())
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
