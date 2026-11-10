package com.lumina.hdr

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.media.*
import android.net.Uri
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color
import java.util.concurrent.Executors

class MainActivity : Activity() {
 private var source: Uri? = null
 private lateinit var status: TextView
 private lateinit var runButton: Button
 private val worker = Executors.newSingleThreadExecutor()
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(36,48,36,24); setBackgroundColor(Color.rgb(12,19,31)) }
  val heading = TextView(this).apply { text="LUMINA HDR Converter"; textSize=25f; setTextColor(Color.WHITE) }
  status=TextView(this).apply { text="Pilih video SDR untuk memulakan ujian HDR10."; textSize=15f; setTextColor(Color.LTGRAY); setPadding(0,25,0,25) }
  val pick=Button(this).apply { text="Pilih Video"; setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="video/*"; addCategory(Intent.CATEGORY_OPENABLE) },10) } }
  runButton=Button(this).apply { text="Tukar ke HDR10 1080p"; isEnabled=false; setOnClickListener { val uri=source ?: return@setOnClickListener; val output=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type="video/mp4"; putExtra(Intent.EXTRA_TITLE,"LUMINA_HDR10.mp4"); addCategory(Intent.CATEGORY_OPENABLE) }; startActivityForResult(output,11) } }
  layout.addView(heading);layout.addView(status);layout.addView(pick);layout.addView(runButton)
  val note=TextView(this).apply { text="Eksperimen SDR→HDR. Periksa HDR metadata selepas eksport. Video SDR tidak mengandungi butiran highlight yang telah hilang."; setTextColor(Color.LTGRAY); setPadding(0,25,0,0) }; layout.addView(note)
  setContentView(layout)
 }
 @Deprecated("Legacy result callback") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=RESULT_OK) return
  val uri=data?.data ?: return
  if(requestCode==10) { source=uri; runButton.isEnabled=true; status.text="Video dipilih. Sedia untuk ujian." }
  if(requestCode==11) { val input=source ?: return; runButton.isEnabled=false; status.text="Memproses video...";
   worker.execute { try { contentResolver.openFileDescriptor(uri,"w")!!.use { fd -> HdrTranscoder(this).convert(input,fd.fileDescriptor) { message -> runOnUiThread { status.text=message } } }; runOnUiThread { status.text="Fail dieksport. Sahkan metadata menggunakan ffprobe sebelum menganggapnya HDR10." } }
   catch(e:Exception) { runOnUiThread { status.text="Gagal: ${e.message}" } } finally { runOnUiThread { runButton.isEnabled=true } } }
  }
 }
 override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
