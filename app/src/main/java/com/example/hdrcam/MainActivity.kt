package com.example.hdrcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var hdrBtn: Button
    private lateinit var resBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var mbpsBtn: Button
    private lateinit var wbBtn: Button
    private lateinit var flipBtn: Button
    private lateinit var recBtn: Button
    private lateinit var evBar: SeekBar
    private lateinit var evLabel: TextView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var useFront = false

    private val hdrCandidates = listOf(
        "SDR" to DynamicRange.SDR,
        "HLG10" to DynamicRange.HLG_10_BIT,
        "HDR10" to DynamicRange.HDR10_10_BIT,
        "HDR10+" to DynamicRange.HDR10_PLUS_10_BIT,
        "Dolby Vision" to DynamicRange.DOLBY_VISION_10_BIT
    )
    private val hdrOptions = mutableListOf<Pair<String, DynamicRange>>()
    private var hdrIdx = 0

    private val resOptions = listOf("4K" to Quality.UHD, "1080p" to Quality.FHD)
    private var resIdx = 0
    private val fpsOptions = listOf(30, 60)
    private var fpsIdx = 1
    private val mbpsOptions = listOf(50, 80, 100)
    private var mbpsIdx = 2
    private val wbOptions = listOf(
        "WB auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
        "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "Tungsten" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        "Fluorescent" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    )
    private var wbIdx = 0

    private var evMin = 0
    private var evStep = 0f

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (r[Manifest.permission.CAMERA] == true) start() else toast("Camera permission is required")
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val rot = when {
                    o in 45..134 -> Surface.ROTATION_270
                    o in 135..224 -> Surface.ROTATION_180
                    o in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                videoCapture?.targetRotation = rot
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            permLauncher.launch(need)
        }
    }

    override fun onResume() { super.onResume(); orientationListener.enable() }
    override fun onPause() { super.onPause(); orientationListener.disable() }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun mk(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun buildUi() {
        previewView = PreviewView(this)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        zoomRow = row()
        hdrBtn = mk("HDR") { if (hdrOptions.isNotEmpty()) { hdrIdx = (hdrIdx + 1) % hdrOptions.size; bind() } }
        resBtn = mk("4K") { resIdx = (resIdx + 1) % resOptions.size; bind() }
        fpsBtn = mk("60") { fpsIdx = (fpsIdx + 1) % fpsOptions.size; bind() }
        mbpsBtn = mk("100") { mbpsIdx = (mbpsIdx + 1) % mbpsOptions.size; bind() }
        wbBtn = mk("WB auto") { wbIdx = (wbIdx + 1) % wbOptions.size; bind() }
        flipBtn = mk("Flip") { useFront = !useFront; refreshHdr(); bind() }
        recBtn = Button(this).apply {
            text = "Record"
            setOnClickListener { toggleRecord() }
        }

        evLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "EV 0.0"
            textSize = 12f
            setPadding(dp(8), 0, dp(8), 0)
        }
        evBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val idx = p + evMin
                    camera?.cameraControl?.setExposureCompensationIndex(idx)
                    evLabel.text = "EV %.1f".format(idx * evStep)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val settings = row().apply { addView(hdrBtn); addView(resBtn); addView(fpsBtn); addView(mbpsBtn) }
        val extra = row().apply { addView(wbBtn); addView(flipBtn) }
        val evRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(evLabel); addView(evBar)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(16))
            addView(zoomRow); addView(settings); addView(extra); addView(evRow); addView(recBtn)
        }

        val root = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(40); leftMargin = dp(8)
            })
            addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
    }

    private fun setControlsEnabled(en: Boolean) {
        listOf(hdrBtn, resBtn, fpsBtn, mbpsBtn, wbBtn, flipBtn).forEach { it.isEnabled = en }
    }

    private fun updateLabels() {
        val hdr = hdrOptions.getOrNull(hdrIdx)?.first ?: "SDR"
        hdrBtn.text = hdr
        resBtn.text = resOptions[resIdx].first
        fpsBtn.text = "${fpsOptions[fpsIdx]} fps"
        mbpsBtn.text = "${mbpsOptions[mbpsIdx]} Mbps"
        wbBtn.text = wbOptions[wbIdx].first
        status.text = "$hdr  ${resOptions[resIdx].first}  ${fpsOptions[fpsIdx]}fps  ${mbpsOptions[mbpsIdx]}Mbps  ${if (useFront) "front" else "rear"}"
    }

    // ---------- Camera ----------

    private fun selector() =
        if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun start() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            refreshHdr()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshHdr() {
        val p = provider ?: return
        val info = selector().filter(p.availableCameraInfos).firstOrNull() ?: return
        val sup = Recorder.getVideoCapabilities(info).supportedDynamicRanges
        hdrOptions.clear()
        hdrOptions.addAll(hdrCandidates.filter { it.second in sup })
        if (hdrOptions.isEmpty()) hdrOptions.add("SDR" to DynamicRange.SDR)
        hdrIdx = hdrOptions.indexOfFirst { it.first == "HLG10" }.coerceAtLeast(0)
    }

    private fun bind(retry: Boolean = true) {
        val p = provider ?: return
        if (hdrOptions.isEmpty()) refreshHdr()
        val dr = hdrOptions[hdrIdx].second
        val fps = fpsOptions[fpsIdx]
        val awb = wbOptions[wbIdx].second
        try {
            val previewB = Preview.Builder().setDynamicRange(dr)
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        resOptions[resIdx].second,
                        FallbackStrategy.lowerQualityOrHigherThan(resOptions[resIdx].second)
                    )
                )
                .setTargetVideoEncodingBitRate(mbpsOptions[mbpsIdx] * 1_000_000)
                .build()
            val videoB = VideoCapture.Builder(recorder)
                .setDynamicRange(dr)
                .setTargetFrameRate(Range(fps, fps))

            // Continuous auto white balance + face detection (face-aware auto exposure/colour)
            for (ext in listOf(Camera2Interop.Extender(previewB), Camera2Interop.Extender(videoB))) {
                ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
                ext.setCaptureRequestOption(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                )
            }

            val preview = previewB.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val vc = videoB.build()
            vc.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

            p.unbindAll()
            val cam = p.bindToLifecycle(this, selector(), preview, vc)
            camera = cam
            videoCapture = vc
            setupZoom(cam)
            setupEv(cam)
            updateLabels()
        } catch (e: Exception) {
            if (retry && fpsIdx != 0) {
                fpsIdx = 0
                toast("That combination isn't supported, trying 30 fps")
                bind(false)
            } else {
                toast("Camera setup failed: ${e.message}")
            }
        }
    }

    private fun setupZoom(c: Camera) {
        zoomRow.removeAllViews()
        val z = c.cameraInfo.zoomState.value ?: return
        val list = (listOf(z.minZoomRatio) +
            listOf(0.6f, 1f, 2f, 3f, 5f, 10f).filter { it > z.minZoomRatio + 0.05f && it <= z.maxZoomRatio })
            .distinctBy { (it * 10).toInt() }
        for (r in list) {
            zoomRow.addView(mk("%.1fx".format(r)) { c.cameraControl.setZoomRatio(r) })
        }
        c.cameraControl.setZoomRatio(1f.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    private fun setupEv(c: Camera) {
        val es = c.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            val r = es.exposureCompensationRange
            evMin = r.lower
            evStep = es.exposureCompensationStep.toFloat()
            evBar.max = r.upper - r.lower
            evBar.progress = -r.lower
            evBar.isEnabled = true
            evLabel.text = "EV 0.0"
        } else {
            evBar.isEnabled = false
            evLabel.text = "EV n/a"
        }
    }

    // ---------- Recording ----------

    @SuppressLint("MissingPermission")
    private fun toggleRecord() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture ?: return
        val name = "HDR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/HdrCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv)
            .build()
        var pending = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    recBtn.text = "Stop"
                    setControlsEnabled(false)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    recBtn.text = "Record"
                    setControlsEnabled(true)
                    if (ev.hasError()) toast("Recording error ${ev.error}") else toast("Saved to Movies/HdrCam")
                }
                else -> {}
            }
        }
    }
}
