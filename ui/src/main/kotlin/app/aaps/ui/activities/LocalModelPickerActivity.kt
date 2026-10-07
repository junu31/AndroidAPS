package app.aaps.ui.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import java.io.File
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Personal-fork: picks the local LLM model file (.task) and copies it into the app's own storage,
 * because the on-device runtime needs a plain file path. The previous model is replaced.
 */
class LocalModelPickerActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aapsLogger: AAPSLogger

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    @Volatile private var copying = false

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else copy(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        })
        title = getString(R.string.local_model_file)
        status.text = getString(R.string.local_model_pick)
        // leaving while copying would leave a half written file
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!copying) finish()
            }
        })
        if (savedInstanceState == null) pick.launch(arrayOf("*/*"))
    }

    private fun copy(uri: Uri) {
        val name = displayName(uri) ?: "model.task"
        // .task (MediaPipe, Gemma 3n) or .litertlm (LiteRT-LM, Gemma 4)
        if (!name.endsWith(".task", ignoreCase = true) && !name.endsWith(".litertlm", ignoreCase = true)) {
            ToastUtils.errorToast(this, getString(R.string.local_model_wrong_type))
            finish()
            return
        }
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
        val dir = File(getExternalFilesDir(null), "llm").apply { mkdirs() }
        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        copying = true
        status.text = getString(R.string.local_model_copying, name)
        thread(name = "LocalModelCopy") {
            val ok = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                val pct = (done * 100 / size).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { progress.progress = pct }
                                }
                            }
                        }
                    }
                }
                // only one model is kept
                dir.listFiles()?.filter { it != tmp && it.name != name }?.forEach { it.delete() }
                target.delete()
                tmp.renameTo(target)
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Local model copy failed", e)
                tmp.delete()
                false
            }
            runOnUiThread {
                copying = false
                if (ok) {
                    preferences.put(StringKey.AiLocalModelPath, target.absolutePath)
                    ToastUtils.okToast(this, getString(R.string.local_model_ready, name))
                } else ToastUtils.errorToast(this, getString(R.string.local_model_copy_failed))
                finish()
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

}
