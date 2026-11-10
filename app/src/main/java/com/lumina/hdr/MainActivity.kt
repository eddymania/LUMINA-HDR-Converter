package com.lumina.hdr

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

data class HdrSettings(
    val strength: Float = 1.0f,
    val gamma: Float = 1.0f,
    val peakNits: Float = 400f
)

class MainActivity : Activity() {
    private var videoUri: Uri? = null
    private var chosenAudioUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var videoName: TextView
    private lateinit var audioName: TextView
    private lateinit var exportButton: Button
    private lateinit var pickAudio: Button
    private lateinit var audioSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var strength: SeekBar
    private lateinit var gamma: SeekBar
    private lateinit var peak: SeekBar
    private val worker = Executors.newSingleThreadExecutor()
    private var exporting = false

    private val ink = Color.rgb(237, 243, 250)
    private val muted = Color.rgb(164, 183, 204)
    private val panel = Color.rgb(25, 37, 56)
    private val accent = Color.rgb(61, 218, 205)

    private fun text(value: String, size: Float = 15f, color: Int = ink): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 8, 0, 8)
        }

    private fun card(body: LinearLayout, title: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            setBackgroundColor(panel)
        }
        container.addView(text(title, 18f))
        val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
        body.addView(container, lp)
        return container
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        initial: Int,
        max: Int,
        display: (Int) -> String
    ): SeekBar {
        val value = text("", 14f, muted)
        parent.addView(value)
        return SeekBar(this).apply {
            this.max = max
            progress = initial
            value.text = "$label: ${display(initial)}"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = "$label: ${display(p)}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            parent.addView(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(Color.rgb(11, 19, 32))
        }
        body.addView(text("LUMINA", 30f, accent))
        body.addView(text("HDR CONVERTER  ·  v1.2", 17f))
        body.addView(text("SDR → HDR10 • HEVC Main10 • Offline", 13f, muted))

        val videoCard = card(body, "01  VIDEO SOURCE")
        val videoButton = Button(this).apply {
            text = "Pilih video SDR"
            setOnClickListener { openDocument("video/*", 10) }
        }
        videoCard.addView(videoButton)
        videoName = text("Tiada video dipilih", 13f, muted)
        videoCard.addView(videoName)

        val audioCard = card(body, "02  AUDIO")
        audioSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Kekalkan audio asal (AAC)", "Gunakan audio AAC pilihan", "Mute — tanpa audio")
            )
        }
        audioCard.addView(audioSpinner)
        pickAudio = Button(this).apply {
            text = "Pilih audio AAC"
            visibility = View.GONE
            setOnClickListener { openDocument("audio/*", 12) }
        }
        audioCard.addView(pickAudio)
        audioName = text("", 13f, muted)
        audioCard.addView(audioName)
        audioSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                pickAudio.visibility = if (position == 1) View.VISIBLE else View.GONE
                updateButton()
            }
        }

        val hdrCard = card(body, "03  HDR SETTINGS")
        strength = slider(hdrCard, "HDR Strength", 100, 150) { "$it%" }
        gamma = slider(hdrCard, "Gamma", 0, 40) { "%.2f".format(1f + it / 100f) }
        peak = slider(hdrCard, "Peak highlights", 100, 500) { "${300 + it} nits" }
        hdrCard.addView(text("Peak 300–800 nits (eksperimen). Metadata mastering masih nilai rujukan.", 12f, muted))

        val outputCard = card(body, "04  EXPORT")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        outputCard.addView(progress)
        status = text("Sedia untuk memproses.", 13f, muted)
        outputCard.addView(status)
        exportButton = Button(this).apply {
            text = "Eksport HDR10 1080p"
            isEnabled = false
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "video/mp4"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "LUMINA_HDR10_v1.2.mp4")
                }, 11)
            }
        }
        outputCard.addView(exportButton)
        body.addView(text("HDR10 metadata perlu disahkan menggunakan MediaInfo. Tetapan tidak memulihkan butiran highlight SDR yang hilang.", 12f, muted))
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private fun openDocument(mime: String, requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = mime
            addCategory(Intent.CATEGORY_OPENABLE)
        }, requestCode)
    }

    private fun updateButton() {
        if (::exportButton.isInitialized && ::audioSpinner.isInitialized) {
            exportButton.isEnabled = !exporting && videoUri != null &&
                (audioSpinner.selectedItemPosition != 1 || chosenAudioUri != null)
        }
    }

    @Deprecated("Legacy callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val selected = data?.data ?: return
        when (requestCode) {
            10 -> {
                videoUri = selected
                videoName.text = "Video dipilih: ${selected.lastPathSegment ?: "video"}"
                updateButton()
            }
            12 -> {
                chosenAudioUri = selected
                audioName.text = "Audio dipilih: ${selected.lastPathSegment ?: "AAC"}"
                updateButton()
            }
            11 -> {
                val input = videoUri ?: return
                val audio = when (audioSpinner.selectedItemPosition) {
                    0 -> input
                    1 -> chosenAudioUri ?: return
                    else -> null
                }
                val settings = HdrSettings(
                    strength = strength.progress / 100f,
                    gamma = 1f + gamma.progress / 100f,
                    peakNits = (300 + peak.progress).toFloat()
                )
                exporting = true
                updateButton()
                progress.progress = 0
                status.text = "Memulakan pemprosesan..."
                worker.execute {
                    try {
                        contentResolver.openFileDescriptor(selected, "w")!!.use { fd ->
                            HdrTranscoder(this).convert(input, fd.fileDescriptor, audio, settings) { message, percent ->
                                runOnUiThread {
                                    status.text = message
                                    progress.progress = percent.coerceIn(0, 100)
                                }
                            }
                        }
                        runOnUiThread {
                            progress.progress = 100
                            status.text = "Eksport selesai. Semak metadata dan audio dalam MediaInfo."
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Gagal: ${e.message}" }
                    } finally {
                        runOnUiThread { exporting = false; updateButton() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
