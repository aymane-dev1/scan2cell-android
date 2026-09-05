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
    private var psdGroupMode = false
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
                val primary = ReceiptParser.parse(recognized)

                // If the full-page pass already saw both paper IDs, keep it fast.
                if (primary.contractNumber.isNotBlank() && primary.tierReference.isNotBlank()) {
                    showReview(primary)
                } else {
                    // Small bottom IDs are the hardest part of the receipt. Run a
                    // dedicated, enlarged OCR pass instead of pretending a missing
                    // Tier is acceptable.
                    recognizeBottomReferences(bitmap, primary)
                }
            }
            .addOnFailureListener { error ->
                showLoading(false)
                binding.captureButton.isEnabled = true
                showMessage("Receipt recognition failed: ${error.message}")
            }
    }

    private fun recognizeBottomReferences(bitmap: Bitmap, primary: ReceiptData) {
        showLoading(true, "Reading Contract + Tier / Réf.…")

        // The two long identifiers are printed in the lower half of the receipt.
        // Excluding the very bottom footer also reduces telephone/fax false hits.
        val bottomBand = cropAndUpscale(
            source = bitmap,
            leftFraction = 0.0f,
            topFraction = 0.46f,
            rightFraction = 1.0f,
            bottomFraction = 0.92f,
            scale = 2.6f
        )

        recognizer.process(InputImage.fromBitmap(bottomBand, 0))
            .addOnSuccessListener { bottomText ->
                val pair = ReceiptParser.parseReferencePairOnly(bottomText)
                val merged = primary.copy(
                    contractNumber = pair.first.ifBlank { primary.contractNumber },
                    tierReference = pair.second.ifBlank { primary.tierReference }
                )
                recycleIfTemporary(bottomBand)

                if (merged.contractNumber.isNotBlank() && merged.tierReference.isNotBlank()) {
                    showReview(merged)
                } else {
                    recognizeLeftAndRightReferences(bitmap, merged)
                }
            }
            .addOnFailureListener {
                recycleIfTemporary(bottomBand)
                // The split pass is independent, so still try it if the whole
                // bottom-band OCR fails.
                recognizeLeftAndRightReferences(bitmap, primary)
            }
    }

    private fun recognizeLeftAndRightReferences(bitmap: Bitmap, primary: ReceiptData) {
        showLoading(true, "Reading the two bottom numbers separately…")

        val leftCrop = cropAndUpscale(
            source = bitmap,
            leftFraction = 0.0f,
            topFraction = 0.46f,
            rightFraction = 0.58f,
            bottomFraction = 0.92f,
            scale = 3.0f
        )

        recognizer.process(InputImage.fromBitmap(leftCrop, 0))
            .addOnSuccessListener { leftText ->
                val leftId = extractBestReference(
                    leftText.text,
                    exclude = setOf(primary.tierReference).filter { it.isNotBlank() }.toSet()
                )
                recycleIfTemporary(leftCrop)

                val contract = primary.contractNumber.ifBlank { leftId }

                val rightCrop = cropAndUpscale(
                    source = bitmap,
                    leftFraction = 0.42f,
                    topFraction = 0.46f,
                    rightFraction = 1.0f,
                    bottomFraction = 0.92f,
                    scale = 3.0f
                )

                recognizer.process(InputImage.fromBitmap(rightCrop, 0))
                    .addOnSuccessListener { rightText ->
                        val excluded = setOf(contract, leftId, primary.tierReference)
                            .filter { it.isNotBlank() }
                            .toSet()
                        val rightId = extractBestReference(rightText.text, excluded)
                        recycleIfTemporary(rightCrop)

                        val tier = primary.tierReference.ifBlank { rightId }
                        showReview(
                            primary.copy(
                                contractNumber = contract,
                                tierReference = tier
                            )
                        )
                    }
                    .addOnFailureListener {
                        recycleIfTemporary(rightCrop)
                        showReview(primary.copy(contractNumber = contract))
                    }
            }
            .addOnFailureListener {
                recycleIfTemporary(leftCrop)
                // If the left crop fails, the existing full-page Contract is still
                // useful. Try the right side directly for Tier.
                recognizeRightReferenceOnly(bitmap, primary)
            }
    }

    private fun recognizeRightReferenceOnly(bitmap: Bitmap, primary: ReceiptData) {
        val rightCrop = cropAndUpscale(
            source = bitmap,
            leftFraction = 0.42f,
            topFraction = 0.46f,
            rightFraction = 1.0f,
            bottomFraction = 0.92f,
            scale = 3.0f
        )

        recognizer.process(InputImage.fromBitmap(rightCrop, 0))
            .addOnSuccessListener { rightText ->
                val rightId = extractBestReference(
                    rightText.text,
                    exclude = setOf(primary.contractNumber).filter { it.isNotBlank() }.toSet()
                )
                recycleIfTemporary(rightCrop)
                showReview(primary.copy(tierReference = primary.tierReference.ifBlank { rightId }))
            }
            .addOnFailureListener {
                recycleIfTemporary(rightCrop)
                showReview(primary)
            }
    }

    private fun cropAndUpscale(
        source: Bitmap,
        leftFraction: Float,
        topFraction: Float,
        rightFraction: Float,
        bottomFraction: Float,
        scale: Float
    ): Bitmap {
        val left = (source.width * leftFraction).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * topFraction).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * rightFraction).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * bottomFraction).toInt().coerceIn(top + 1, source.height)

        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).toInt().coerceAtLeast(crop.width),
            (crop.height * scale).toInt().coerceAtLeast(crop.height),
            true
        )
        if (scaled !== crop && !crop.isRecycled) crop.recycle()
        return scaled
    }

    /**
     * Extract one long receipt identifier from an isolated crop. This is more
     * permissive than the full-page parser because the crop itself already tells
     * us whether we are looking at the left or right reference area.
     */
    private fun extractBestReference(text: String, exclude: Set<String> = emptySet()): String {
        val digitLike = setOf(
            '0','1','2','3','4','5','6','7','8','9',
            'O','Q','I','L','Z','S','G','T','B',
            'o','q','i','l','z','s','g','t','b'
        )

        fun normalize(value: String): String = value.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('G', '6')
            .replace('T', '7')
            .replace('B', '8')
            .filter { it.isDigit() }

        fun plausible(value: String): Boolean {
            if (value in exclude) return false
            if (value.length !in 9..14) return false
            if (!value.startsWith('0')) return false
            if (value.length == 10 && value.startsWith("05")) return false
            return true
        }

        val candidates = mutableSetOf<String>()

        text.lines().forEach { line ->
            // Exact OCR tokens.
            Regex("[0-9OoQqIiLlZzSsGgBbTt]{9,14}")
                .findAll(line)
                .map { normalize(it.value) }
                .filter(::plausible)
                .forEach { candidates += it }

            // IDs broken by spaces, dots or dashes.
            Regex("[0-9OoQqIiLlZzSsGgBbTt]{2,7}(?:[ ._-]+[0-9OoQqIiLlZzSsGgBbTt]{2,7}){1,4}")
                .findAll(line)
                .map { normalize(it.value) }
                .filter(::plausible)
                .forEach { candidates += it }

            // Last fallback for an isolated crop: collapse all digit-like glyphs
            // on one OCR line.
            val collapsed = normalize(line.filter { it in digitLike || it == ' ' || it == '-' || it == '.' })
            if (plausible(collapsed)) candidates += collapsed
        }

        return candidates
            .sortedWith(
                compareByDescending<String> { if (it.length == 11) 1 else 0 }
                    .thenByDescending { if (it.startsWith("000")) 1 else 0 }
                    .thenByDescending { it.length }
            )
            .firstOrNull()
            .orEmpty()
    }

    private fun recycleIfTemporary(bitmap: Bitmap) {
        if (bitmap !== capturedBitmap && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun showReview(data: ReceiptData) {
        showLoading(false)
        binding.previewView.visibility = View.INVISIBLE
        binding.focusFrame.visibility = View.GONE
        binding.cameraControls.visibility = View.GONE
        binding.reviewPanel.visibility = View.VISIBLE
        binding.flashButton.visibility = View.GONE
        psdGroupMode = isPsdGroupReceipt(data)

        binding.titleText.text = if (psdGroupMode) "Group receipt • PSD" else "Check the 5 fields"
        binding.subtitleText.text = if (psdGroupMode) {
            "One PSD code is used for both Contract and Tier. Name is optional."
        } else {
            "Check the values read from the paper before sending."
        }

        binding.treasuryInput.setText(data.treasuryNumber)
        binding.nameInput.setText(if (psdGroupMode) "" else data.clientName)
        binding.contractInput.setText(data.contractNumber)
        binding.tierInput.setText(data.tierReference)
        binding.contractInput.isEnabled = true
        binding.tierInput.isEnabled = !psdGroupMode
        binding.swapIdsButton.visibility = if (psdGroupMode) View.GONE else View.VISIBLE
        binding.amountInput.setText(data.amount)
        updateDetectedStatus(data)
    }

    private fun updateDetectedStatus(data: ReceiptData) {
        if (psdGroupMode) {
            val requiredCount = listOf(
                data.treasuryNumber,
                data.contractNumber,
                data.amount
            ).count { it.isNotBlank() }
            binding.detectedStatus.text = if (requiredCount == 3) {
                "PSD group • ready • code copied to Contract + Tier"
            } else {
                "$requiredCount / 3 required fields detected • review below"
            }
            return
        }

        val scannedCount = listOf(
            data.treasuryNumber,
            data.clientName,
            data.contractNumber,
            data.tierReference,
            data.amount
        ).count { it.isNotBlank() }

        binding.detectedStatus.text = when (scannedCount) {
            5 -> "5 / 5 detected • ready to compare"
            4 -> "4 / 5 detected • check the missing field"
            else -> "$scannedCount / 5 detected • review the fields below"
        }
    }

    private fun isPsdGroupReceipt(data: ReceiptData): Boolean {
        val contract = data.contractNumber.trim()
        val tier = data.tierReference.trim()
        return contract.isNotBlank() &&
            contract.equals(tier, ignoreCase = true) &&
            contract.any { it.isLetter() } &&
            contract.any { it.isDigit() }
    }

    private fun swapIds() {
        val contract = binding.contractInput.text?.toString().orEmpty()
        val tier = binding.tierInput.text?.toString().orEmpty()
        binding.contractInput.setText(tier)
        binding.tierInput.setText(contract)
        showMessage("Contract and Tier / Réf. were swapped.")
    }

    private fun returnReceipt() {
        val contract = binding.contractInput.text?.toString()?.trim().orEmpty()
        val data = ReceiptData(
            treasuryNumber = binding.treasuryInput.text?.toString()?.trim().orEmpty(),
            clientName = if (psdGroupMode) "" else binding.nameInput.text?.toString()?.trim().orEmpty(),
            contractNumber = contract,
            // Group/PSD receipts use the SAME paper code in both database fields.
            tierReference = if (psdGroupMode) contract else binding.tierInput.text?.toString()?.trim().orEmpty(),
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
            putExtra(EXTRA_TIER, data.tierReference)
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
        binding.subtitleText.text = "Fit the full receipt inside the frame • PID + PSD supported"
        binding.receiptImage.setImageDrawable(null)
        recycleCapturedBitmapExcept(null)
        capturedBitmap = null
        psdGroupMode = false
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
        const val EXTRA_TIER = "receipt_tier"
        const val EXTRA_AMOUNT = "receipt_amount"
    }
}
