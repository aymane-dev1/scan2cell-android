package com.scan2cell.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scan2cell.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bridge: LocalBridgeClient
    private val prefs by lazy { getSharedPreferences("scan2cell.local", MODE_PRIVATE) }
    private var pendingReceipt: ReceiptData? = null

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val value = result.data?.getStringExtra(ScannerActivity.EXTRA_SELECTED_TEXT)?.trim().orEmpty()
            if (value.isBlank()) return@registerForActivityResult
            binding.selectedText.setText(value)
            binding.selectedText.setSelection(value.length)
            sendSelectedValue()
        }

    private val receiptLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val intent = result.data ?: return@registerForActivityResult
            pendingReceipt = ReceiptData(
                treasuryNumber = intent.getStringExtra(ReceiptScannerActivity.EXTRA_TREASURY).orEmpty(),
                clientName = intent.getStringExtra(ReceiptScannerActivity.EXTRA_NAME).orEmpty(),
                contractNumber = intent.getStringExtra(ReceiptScannerActivity.EXTRA_CONTRACT).orEmpty(),
                amount = intent.getStringExtra(ReceiptScannerActivity.EXTRA_AMOUNT).orEmpty()
            )
            renderPendingReceipt()
            sendPendingReceipt()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openScanner() else showMessage("Camera permission is required to scan.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            startMainUi()
        } catch (error: Throwable) {
            showStartupFailure(error)
        }
    }

    private fun startMainUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bridge = LocalBridgeClient(applicationContext)

        binding.codeInput.doAfterTextChanged {
            binding.connectButton.isEnabled = !isBusy() && it?.length == 6
        }
        binding.connectButton.setOnClickListener { connectWithCode() }
        binding.advancedToggle.setOnClickListener {
            binding.ipInputLayout.visibility =
                if (binding.ipInputLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.scanButton.setOnClickListener { requestCameraAndOpenScanner() }
        binding.receiptButton.setOnClickListener { openReceiptScanner() }
        binding.sendButton.setOnClickListener { sendSelectedValue() }
        binding.receiptSendButton.setOnClickListener { sendPendingReceipt() }
        binding.receiptClearButton.setOnClickListener {
            pendingReceipt = null
            renderPendingReceipt()
        }
        binding.selectedText.doAfterTextChanged { updateButtons() }

        updateUiFromSavedPairing()
        renderPendingReceipt()

        // Connection verification is non-essential for launching the app.
        // Delay it until the first frame is already visible.
        binding.root.postDelayed({ verifySavedConnection() }, 350L)
    }

    private fun showStartupFailure(error: Throwable) {
        val scroll = android.widget.ScrollView(this)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 36, 32, 36)
        }
        val title = android.widget.TextView(this).apply {
            text = "Scan2Cell startup error"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val message = android.widget.TextView(this).apply {
            text = buildString {
                append("\nThe app caught the crash instead of closing.\n\n")
                append(error.javaClass.simpleName)
                append(": ")
                append(error.message ?: "No message")
                append("\n\n")
                append(error.stackTrace.take(8).joinToString("\n"))
            }
            textSize = 13f
            setTextIsSelectable(true)
        }
        val reset = android.widget.Button(this).apply {
            text = "Clear saved connection and restart"
            setOnClickListener {
                getSharedPreferences("scan2cell.local", MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
                recreate()
            }
        }
        box.addView(title)
        box.addView(message)
        box.addView(reset)
        scroll.addView(box)
        setContentView(scroll)
    }

    private fun requestCameraAndOpenScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openScanner() = scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    private fun openReceiptScanner() = receiptLauncher.launch(Intent(this, ReceiptScannerActivity::class.java))

    private fun connectWithCode() {
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        if (!code.matches(Regex("\\d{6}"))) {
            showMessage("Enter the complete six-digit code.")
            return
        }
        hideKeyboard()
        setBusy(true, "Finding your PC on the local network…")
        lifecycleScope.launch {
            try {
                val endpoint = withContext(Dispatchers.IO) {
                    bridge.discover(prefs.getString(KEY_BASE_URL, null), binding.ipInput.text?.toString())
                }
                val result = withContext(Dispatchers.IO) {
                    bridge.pair(endpoint, code, deviceId(), deviceName())
                }
                prefs.edit()
                    .putString(KEY_BASE_URL, endpoint.baseUrl)
                    .putString(KEY_TOKEN, result.token)
                    .putString(KEY_SERVER_ID, result.serverId)
                    .putString(KEY_SERVER_NAME, result.serverName)
                    .apply()
                binding.codeInput.setText("")
                setBusy(false)
                updateUi(true, true, result.serverName, result.excelConnected)
                showMessage(if (result.excelConnected) "Connected. Single scan and Receipt mode are ready." else "Phone paired. Open the Scan2Cell pane in Excel.")
            } catch (error: Exception) {
                setBusy(false)
                showMessage(error.message ?: "Could not connect to the PC.")
            }
        }
    }

    private fun sendSelectedValue() {
        val text = binding.selectedText.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) { showMessage("Scan or enter a value first."); return }
        val connection = savedConnection() ?: run { showMessage("Pair this phone with Excel first."); return }
        hideKeyboard()
        setBusy(true, "Sending to the selected Excel cell…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    sendSingleWithRediscovery(connection, text)
                }
                setBusy(false)
                binding.selectedText.setText("")
                showMessage("Sent to ${result.address}.")
                updateUiFromSavedPairing(reachable = true)
            } catch (error: Exception) {
                setBusy(false)
                showMessage(error.message ?: "Send failed.")
            }
        }
    }

    private fun sendPendingReceipt() {
        val receipt = pendingReceipt ?: return
        val connection = savedConnection() ?: run { showMessage("Pair this phone with Excel first."); return }
        hideKeyboard()
        setBusy(true, "Adding receipt to the Excel register…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    sendReceiptWithRediscovery(connection, receipt)
                }
                setBusy(false)
                showMessage("Receipt added to ${result.address}.")
                pendingReceipt = null
                renderPendingReceipt()
                updateUiFromSavedPairing(reachable = true)
            } catch (error: Exception) {
                setBusy(false)
                renderPendingReceipt()
                showMessage(error.message ?: "Receipt send failed. Your 4 fields are kept below so you can retry.")
            }
        }
    }

    private data class SavedConnection(val baseUrl: String, val token: String, val serverId: String?)

    private fun savedConnection(): SavedConnection? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
        val token = prefs.getString(KEY_TOKEN, null)
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return null
        return SavedConnection(baseUrl, token, prefs.getString(KEY_SERVER_ID, null))
    }

    private fun sendSingleWithRediscovery(connection: SavedConnection, text: String): LocalBridgeClient.SendResult {
        try {
            return bridge.send(connection.baseUrl, connection.token, deviceId(), text)
        } catch (first: Exception) {
            val endpoint = rediscoverMatching(connection.serverId, first)
            return bridge.send(endpoint.baseUrl, connection.token, deviceId(), text)
        }
    }

    private fun sendReceiptWithRediscovery(connection: SavedConnection, receipt: ReceiptData): LocalBridgeClient.SendResult {
        try {
            return bridge.sendReceipt(connection.baseUrl, connection.token, deviceId(), receipt)
        } catch (first: Exception) {
            val endpoint = rediscoverMatching(connection.serverId, first)
            return bridge.sendReceipt(endpoint.baseUrl, connection.token, deviceId(), receipt)
        }
    }

    private fun rediscoverMatching(expectedServerId: String?, first: Exception): LocalBridgeClient.ServerEndpoint {
        if (first.message?.contains("not paired", ignoreCase = true) == true) throw first
        val endpoint = bridge.discover(lastBaseUrl = null, manualIp = null)
        if (!expectedServerId.isNullOrBlank() && endpoint.serverId.isNotBlank() && endpoint.serverId != expectedServerId) {
            throw IllegalStateException("A different Scan2Cell PC was found. Pair again using its six-digit code.")
        }
        prefs.edit().putString(KEY_BASE_URL, endpoint.baseUrl).apply()
        return endpoint
    }

    private fun renderPendingReceipt() {
        val receipt = pendingReceipt
        binding.receiptPreviewCard.visibility = if (receipt == null) View.GONE else View.VISIBLE
        if (receipt == null) return
        binding.receiptTreasuryText.text = receipt.treasuryNumber.ifBlank { "— missing —" }
        binding.receiptNameText.text = receipt.clientName.ifBlank { "— missing —" }
        binding.receiptContractText.text = receipt.contractNumber.ifBlank { "— missing —" }
        binding.receiptAmountText.text = receipt.amount.ifBlank { "— missing —" }
        binding.receiptSendButton.isEnabled = !isBusy()
    }

    private fun verifySavedConnection() {
        val connection = savedConnection() ?: return
        lifecycleScope.launch {
            try {
                val endpoint = withContext(Dispatchers.IO) { bridge.probe(connection.baseUrl) }
                updateUi(true, true, endpoint.name, endpoint.excelConnected)
            } catch (_: Exception) {
                updateUiFromSavedPairing(reachable = false)
            }
        }
    }

    private fun updateUiFromSavedPairing(reachable: Boolean? = null) {
        val paired = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
        updateUi(paired, reachable ?: false, prefs.getString(KEY_SERVER_NAME, "Excel PC") ?: "Excel PC", null)
    }

    private fun updateUi(paired: Boolean, reachable: Boolean, serverName: String, excelOpen: Boolean?) {
        val statusText: String
        val statusBackground: Int
        val statusColor: Int
        when {
            !paired -> { statusText = "Not connected"; statusBackground = R.drawable.bg_status_offline; statusColor = R.color.s2c_error }
            reachable && excelOpen == true -> { statusText = "Ready • $serverName"; statusBackground = R.drawable.bg_status_online; statusColor = R.color.s2c_success }
            reachable -> { statusText = "Paired • open Excel pane"; statusBackground = R.drawable.bg_status_online; statusColor = R.color.s2c_success }
            else -> { statusText = "Paired • looking for PC"; statusBackground = R.drawable.bg_status_offline; statusColor = R.color.s2c_error }
        }
        binding.connectionStatus.text = statusText
        binding.connectionStatus.setBackgroundResource(statusBackground)
        binding.connectionStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        binding.scanButton.isEnabled = paired && !isBusy()
        binding.receiptButton.isEnabled = paired && !isBusy()
        updateButtons()
    }

    private fun updateButtons() {
        val paired = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
        binding.sendButton.isEnabled = paired && !isBusy() && !binding.selectedText.text.isNullOrBlank()
        binding.connectButton.isEnabled = !isBusy() && binding.codeInput.text?.length == 6
        binding.receiptSendButton.isEnabled = paired && !isBusy() && pendingReceipt != null
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        binding.root.tag = busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.codeInput.isEnabled = !busy
        binding.ipInput.isEnabled = !busy
        binding.advancedToggle.isEnabled = !busy
        val paired = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
        binding.scanButton.isEnabled = !busy && paired
        binding.receiptButton.isEnabled = !busy && paired
        if (message != null) binding.messageText.text = message
        updateButtons()
    }

    private fun isBusy(): Boolean = binding.root.tag == true
    private fun showMessage(message: String) {
        binding.messageText.text = message
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
    private fun hideKeyboard() {
        currentFocus?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    }
    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { character -> character.uppercase() }
        return "$manufacturer ${Build.MODEL}".trim().ifBlank { "Android phone" }
    }

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_SERVER_NAME = "serverName"
        private const val KEY_DEVICE_ID = "deviceId"
    }
}
