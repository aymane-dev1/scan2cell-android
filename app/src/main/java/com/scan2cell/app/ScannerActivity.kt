package com.scan2cell.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.toBitmap
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scan2cell.app.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class ScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private var capturedBitmap: Bitmap? = null

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                showMessage("Camera permission is required.")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.backButton.setOnClickListener {
            if (binding.resultContainer.visibility == View.VISIBLE) showCameraMode() else finish()
        }
        binding.flashButton.setOnClickListener { toggleTorch() }
        binding.captureButton.setOnClickListener { captureInMemory() }
        binding.retakeButton.setOnClickListener { showCameraMode() }
        binding.useButton.setOnClickListener { returnSelectedValue() }

        binding.wordOverlay.setOnItemSelected { item ->
            binding.resultText.setText(item.text)
            binding.resultText.setSelection(binding.resultText.text?.length ?: 0)
            binding.scannerSubtitle.text =
                if (item.kind == WordOverlayView.Kind.BARCODE) "Barcode selected" else "Word selected"
            binding.useButton.isEnabled = item.text.isNotBlank()
        }

        binding.resultText.addTextChangedListener(SimpleTextWatcher {
            binding.useButton.isEnabled = binding.resultText.text?.toString()?.trim()?.isNotEmpty() == true
        })

        requestCamera()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(92)
                    .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (error: Exception) {
                showMessage("Camera could not start: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureInMemory() {
        val capture = imageCapture ?: return
        capture.targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0

        showLoading(true, "Reading words and codes…")
        binding.captureButton.isEnabled = false

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = image.toBitmap()
                        val upright = rotateBitmap(bitmap, rotation)
                        image.close()

                        runOnUiThread {
                            capturedBitmap?.takeIf { it !== upright && !it.isRecycled }?.recycle()
                            capturedBitmap = upright
                            binding.capturedImage.setImageBitmap(upright)
                            analyzeBitmap(upright)
                        }
                    } catch (error: Exception) {
                        image.close()
                        runOnUiThread {
                            showLoading(false)
                            binding.captureButton.isEnabled = true
                            showMessage("Could not read the captured image: ${error.message}")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        showLoading(false)
                        binding.captureButton.isEnabled = true
                        showMessage("Capture failed: ${exception.message}")
                    }
                }
            }
        )
    }

    private fun analyzeBitmap(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val textTask = textRecognizer.process(input)
        val barcodeTask = barcodeScanner.process(input)

        Tasks.whenAllComplete(textTask, barcodeTask)
            .addOnSuccessListener {
                val items = mutableListOf<WordOverlayView.DetectedItem>()

                if (textTask.isSuccessful) {
                    val result = textTask.result
                    result.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            line.elements.forEach { element ->
                                val value = element.text.trim()
                                val bounds = element.boundingBox
                                if (value.isNotBlank() && bounds != null &&
                                    bounds.width() >= 4 && bounds.height() >= 4
                                ) {
                                    items += WordOverlayView.DetectedItem(
                                        text = value,
                                        bounds = bounds,
                                        kind = WordOverlayView.Kind.WORD
                                    )
                                }
                            }
                        }
                    }
                }

                if (barcodeTask.isSuccessful) {
                    barcodeTask.result.forEach { barcode ->
                        val value = barcode.rawValue?.trim().orEmpty()
                        val bounds = barcode.boundingBox
                        if (value.isNotBlank() && bounds != null) {
                            items += WordOverlayView.DetectedItem(
                                text = value,
                                bounds = bounds,
                                kind = WordOverlayView.Kind.BARCODE
                            )
                        }
                    }
                }

                val unique = items
                    .distinctBy { "${it.kind}|${it.text}|${it.bounds.flattenToString()}" }
                    .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

                showResultMode()
                binding.wordOverlay.setItems(unique, bitmap.width, bitmap.height)
                binding.resultText.setText("")
                binding.useButton.isEnabled = false

                if (unique.isEmpty()) {
                    binding.scannerSubtitle.text = "No readable words found — retake closer"
                    showMessage("No text or barcode was detected.")
                } else {
                    val barcodes = unique.count { it.kind == WordOverlayView.Kind.BARCODE }
                    binding.scannerSubtitle.text =
                        "${unique.size} selectable item${if (unique.size == 1) "" else "s"}" +
                        if (barcodes > 0) " • $barcodes barcode${if (barcodes == 1) "" else "s"}" else ""
                }
            }
            .addOnFailureListener { error ->
                showLoading(false)
                binding.captureButton.isEnabled = true
                showMessage("Recognition failed: ${error.message}")
            }
    }

    private fun showResultMode() {
        showLoading(false)
        binding.previewView.visibility = View.INVISIBLE
        binding.resultContainer.visibility = View.VISIBLE
        binding.cameraControls.visibility = View.GONE
        binding.resultControls.visibility = View.VISIBLE
        binding.flashButton.visibility = View.GONE
        binding.scannerTitle.text = "Choose the exact value"
        binding.scannerSubtitle.text = "Tap one highlighted word or code"
    }

    private fun showCameraMode() {
        binding.resultText.setText("")
        binding.wordOverlay.clearItems()
        binding.capturedImage.setImageDrawable(null)
        capturedBitmap?.takeIf { !it.isRecycled }?.recycle()
        capturedBitmap = null

        binding.previewView.visibility = View.VISIBLE
        binding.resultContainer.visibility = View.GONE
        binding.cameraControls.visibility = View.VISIBLE
        binding.resultControls.visibility = View.GONE
        binding.flashButton.visibility = View.VISIBLE
        binding.captureButton.isEnabled = true
        binding.scannerTitle.text = "Smart scanner"
        binding.scannerSubtitle.text = "Hold steady and capture"
    }

    private fun returnSelectedValue() {
        val value = binding.resultText.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            showMessage("Tap a highlighted word or code first.")
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_SELECTED_TEXT, value)
        )
        finish()
    }

    private fun toggleTorch() {
        torchEnabled = !torchEnabled
        camera?.cameraControl?.enableTorch(torchEnabled)
        binding.flashButton.alpha = if (torchEnabled) 1f else 0.62f
    }

    private fun showLoading(visible: Boolean, text: String? = null) {
        binding.loadingPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (text != null) binding.loadingText.text = text
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
        if (rotated !== source && !source.isRecycled) source.recycle()
        return rotated
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textRecognizer.close()
        barcodeScanner.close()
        capturedBitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    companion object {
        const val EXTRA_SELECTED_TEXT = "selected_text"
    }
}

private class SimpleTextWatcher(
    private val action: () -> Unit
) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
