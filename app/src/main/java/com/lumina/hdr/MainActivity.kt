
package com.lumina.hdr

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var videoUri: Uri? = null
    private var customAudioUri: Uri? = null

    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private lateinit var audioButton: Button
    private lateinit var audioMode: Spinner
    private lateinit var progressBar: ProgressBar

    private val worker = Executors.newSingleThreadExecutor()

    private val modes = arrayOf(
        "Kekalkan audio asal",
        "Pilih audio lain",
        "Tiada audio (Mute)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 24)
            setBackgroundColor(Color.rgb(12, 19, 31))
        }

        fun addLabel(value: String): TextView {
            return TextView(this).apply {
                text = value
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, 18, 0, 12)
                layout.addView(this)
            }
        }

        addLabel("LUMINA HDR Converter v1.1").apply {
            textSize = 24f
        }

        val videoButton = Button(this).apply {
            text = "Pilih Video SDR"
            setOnClickListener {
                openDocument("video/*", 10)
            }
        }
        layout.addView(videoButton)

        addLabel("Pilihan Audio")

        audioMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes
            )
        }
        layout.addView(audioMode)

        audioButton = Button(this).apply {
            text = "Pilih Fail Audio AAC"
            setOnClickListener {
                openDocument("audio/*", 12)
            }
        }
        layout.addView(audioButton)

        audioMode.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    audioButton.visibility =
                        if (position == 1)
                            android.view.View.VISIBLE
                        else
                            android.view.View.GONE
                    updateExportButton()
                }
            }

        progressBar = ProgressBar(
            this, null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }
        layout.addView(progressBar)

        status = addLabel("Pilih video untuk bermula.")

        exportButton = Button(this).apply {
            text = "Eksport HDR10 1080p"
            isEnabled = false
            setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_CREATE_DOCUMENT
                ).apply {
                    type = "video/mp4"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(
                        Intent.EXTRA_TITLE,
                        "LUMINA_HDR10_v1.1.mp4"
                    )
                }
                startActivityForResult(intent, 11)
            }
        }
        layout.addView(exportButton)

        setContentView(ScrollView(this).apply {
            addView(layout)
        })
    }

    private fun openDocument(type: String, code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            this.type = type
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, code)
    }

    private fun updateExportButton() {
        exportButton.isEnabled =
            videoUri != null &&
            (audioMode.selectedItemPosition != 1 ||
             customAudioUri != null)
    }

    @Deprecated("Legacy callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            10 -> {
                videoUri = uri
                status.text = "Video SDR dipilih."
                updateExportButton()
            }

            12 -> {
                customAudioUri = uri
                status.text = "Fail audio dipilih."
                updateExportButton()
            }

            11 -> {
                val input = videoUri ?: return
                val mode = audioMode.selectedItemPosition

                val audio = when (mode) {
                    0 -> input
                    1 -> customAudioUri ?: return
                    else -> null
                }

                exportButton.isEnabled = false
                progressBar.progress = 0
                status.text = "Memulakan eksport..."

                worker.execute {
                    try {
                        contentResolver.openFileDescriptor(
                            uri, "w"
                        )!!.use { fd ->

                            HdrTranscoder(this).convert(
                                input,
                                fd.fileDescriptor,
                                audio
                            ) { message, percent ->
                                runOnUiThread {
                                    status.text = message
                                    progressBar.progress = percent
                                }
                            }
                        }

                        runOnUiThread {
                            status.text =
                                "Eksport selesai. Semak HDR10 dan audio."
                            progressBar.progress = 100
                        }

                    } catch (e: Exception) {
                        runOnUiThread {
                            status.text =
                                "Gagal: ${e.message}"
                        }
                    } finally {
                        runOnUiThread {
                            updateExportButton()
                        }
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
