package com.example.preklad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

data class Item(val r: RectF, val t: String)

class OverlayView(c: Context) : View(c) {
    private var items = listOf<Item>()
    private var iw = 1
    private var ih = 1
    private val bg = Paint().apply { color = Color.argb(240, 20, 20, 20) }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    fun set(i: List<Item>, w: Int, h: Int) {
        items = i; iw = w; ih = h
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = maxOf(width / iw.toFloat(), height / ih.toFloat())
        val ox = (width - iw * s) / 2
        val oy = (height - ih * s) / 2
        for (item in items) {
            val r = RectF(
                item.r.left * s + ox, item.r.top * s + oy,
                item.r.right * s + ox, item.r.bottom * s + oy
            )
            c.drawRect(r, bg)
            tp.textSize = r.height() * 0.8f
            val tw = tp.measureText(item.t)
            if (tw > r.width() * 1.3f) tp.textSize *= r.width() * 1.3f / tw
            c.drawText(item.t, r.left, r.centerY() - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CZECH)
            .build()
    )
    private val cache = HashMap<String, String>()
    private val cz = Regex("[áčďéěíňóřšťúůýžÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ]")
    private var ready = false

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "Bez povolení kamery to nejde."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = OverlayView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 48, 24, 24)
            text = "Stahuji český překlad (jen poprvé, zapni internet)…"
        }
        root.addView(previewView)
        root.addView(overlay)
        root.addView(status)
        setContentView(root)

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ready = true
                status.visibility = View.GONE
            }
            .addOnFailureListener {
                status.text = "Model se nestáhl. Zapni internet a restartuj appku."
            }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { proxy -> process(proxy) } }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(proxy: ImageProxy) {
        val media = proxy.image
        if (!ready || media == null) {
            proxy.close(); return
        }
        val rot = proxy.imageInfo.rotationDegrees
        val w = if (rot % 180 == 0) proxy.width else proxy.height
        val h = if (rot % 180 == 0) proxy.height else proxy.width
        val img = InputImage.fromMediaImage(media, rot)

        recognizer.process(img)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.filter {
                    it.boundingBox != null && it.text.length >= 3 &&
                        it.text.any { ch -> ch.isLetter() } && !cz.containsMatchIn(it.text)
                }
                if (lines.isEmpty()) {
                    overlay.set(emptyList(), w, h)
                    proxy.close()
                    return@addOnSuccessListener
                }
                val result = ArrayList<Item>()
                var left = lines.size
                for (line in lines) {
                    val src = line.text.replace("\n", " ").trim()
                    val rect = RectF(line.boundingBox!!)
                    val finish = { tr: String? ->
                        if (tr != null) result.add(Item(rect, tr))
                        left--
                        if (left == 0) {
                            overlay.set(result, w, h)
                            proxy.close()
                        }
                    }
                    val cached = cache[src]
                    if (cached != null) {
                        finish(cached)
                    } else {
                        translator.translate(src)
                            .addOnSuccessListener { tr -> cache[src] = tr; finish(tr) }
                            .addOnFailureListener { finish(null) }
                    }
                }
            }
            .addOnFailureListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        translator.close()
        executor.shutdown()
    }
}
