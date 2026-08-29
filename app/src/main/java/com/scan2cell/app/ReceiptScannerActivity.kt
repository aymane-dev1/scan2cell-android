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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scan2cell.app.databinding.ActivityReceiptScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReceiptScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiptScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private var capturedBitmap: Bitmap? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                showMessage("Camera permission is required.")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.backButton.setOnClickListener {
            if (binding.reviewPanel.visibility == View.VISIBLE) showCameraMode() else finish()
        }
        binding.flashButton.setOnClickListener { toggleTorch() }
        binding.captureButton.setOnClickListener { captureReceipt() }
        binding.retakeButton.setOnClickListener { showCameraMode() }
        binding.swapIdsButton.setOnClickListener { swapIds() }
        binding.sendReceiptButton.setOnClickListener { returnReceipt() }
        requestCamera()
    }

    private fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (error: Exception) {
                showMessage("Camera could not start: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureReceipt() {
        val capture = imageCapture ?: return
        capture.targetRotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        showLoading(true, "Reading the receipt…")
        binding.captureButton.isEnabled = false

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.toBitmap()
                    val upright = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                    runOnUiThread {
                        recycleCapturedBitmapExcept(upright)
                        capturedBitmap = upright
                        binding.receiptImage.setImageBitmap(upright)
                        recognizeReceipt(upright)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        showLoading(false)
                        binding.captureButton.isEnabled = true
                        showMessage("Could not read the photo: ${error.message}")
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    showLoading(false)
                    binding.captureButton.isEnabled = true
                    showMessage("Capture failed: ${exception.message}")
                }
            }
        })
    }

    private fun recognizeReceipt(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { recognized ->
                val lines = recognized.textBlocks.flatMap { block -> block.lines.map { it.text } }
                val data = ReceiptParser.parse(lines.ifEmpty { recognized.text.lines() })
                showReview(data)
            }
            .addOnFailureListener { error ->
                showLoading(false)
                binding.captureButton.isEnabled = true
                showMessage("Receipt recognition failed: ${error.message}")
            }
    }

    private fun showReview(data: ReceiptData) {
        showLoading(false)
        binding.previewView.visibility = View.INVISIBLE
        binding.focusFrame.visibility = View.GONE
        binding.cameraControls.visibility = View.GONE
        binding.reviewPanel.visibility = View.VISIBLE
        binding.flashButton.visibility = View.GONE
        binding.titleText.text = "Check the 4 fields"
        binding.subtitleText.text = "Correct anything before sending to Excel"

        binding.treasuryInput.setText(data.treasuryNumber)
        binding.nameInput.setText(data.clientName)
        binding.contractInput.setText(data.contractNumber)
        binding.amountInput.setText(data.amount)
        updateDetectedStatus(data.detectedCount)
    }

    private fun updateDetectedStatus(count: Int) {
        binding.detectedStatus.text = when (count) {
            4 -> "4 / 4 fields detected"
            3 -> "3 / 4 detected • check the missing field"
            else -> "$count / 4 detected • please review carefully"
        }
    }

    private fun swapIds() {
        val treasury = binding.treasuryInput.text?.toString().orEmpty()
        val contract = binding.contractInput.text?.toString().orEmpty()
        binding.treasuryInput.setText(contract)
        binding.contractInput.setText(treasury)
        showMessage("The two reference numbers were swapped.")
    }

    private fun returnReceipt() {
        val data = ReceiptData(
            treasuryNumber = binding.treasuryInput.text?.toString()?.trim().orEmpty(),
            clientName = binding.nameInput.text?.toString()?.trim().orEmpty(),
            contractNumber = binding.contractInput.text?.toString()?.trim().orEmpty(),
            amount = binding.amountInput.text?.toString()?.trim().orEmpty()
        )
        if (data.detectedCount == 0) {
            showMessage("Enter at least one receipt field before sending.")
            return
        }
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_TREASURY, data.treasuryNumber)
            putExtra(EXTRA_NAME, data.clientName)
            putExtra(EXTRA_CONTRACT, data.contractNumber)
            putExtra(EXTRA_AMOUNT, data.amount)
        })
        finish()
    }

    private fun showCameraMode() {
        binding.reviewPanel.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.focusFrame.visibility = View.VISIBLE
        binding.cameraControls.visibility = View.VISIBLE
        binding.flashButton.visibility = View.VISIBLE
        binding.captureButton.isEnabled = true
        binding.titleText.text = "Receipt scanner"
        binding.subtitleText.text = "Fit the full receipt inside the frame"
        binding.receiptImage.setImageDrawable(null)
        recycleCapturedBitmapExcept(null)
        capturedBitmap = null
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
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source && !source.isRecycled) source.recycle()
        return rotated
    }

    private fun recycleCapturedBitmapExcept(keep: Bitmap?) {
        val old = capturedBitmap
        if (old != null && old !== keep && !old.isRecycled) old.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
        recycleCapturedBitmapExcept(null)
    }

    companion object {
        const val EXTRA_TREASURY = "receipt_treasury"
        const val EXTRA_NAME = "receipt_name"
        const val EXTRA_CONTRACT = "receipt_contract"
        const val EXTRA_AMOUNT = "receipt_amount"
    }
}
