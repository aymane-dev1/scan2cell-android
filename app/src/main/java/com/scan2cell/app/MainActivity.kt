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

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val value = result.data
                ?.getStringExtra(ScannerActivity.EXTRA_SELECTED_TEXT)
                ?.trim()
                .orEmpty()
            if (value.isBlank()) return@registerForActivityResult

            binding.selectedText.setText(value)
            binding.selectedText.setSelection(value.length)
            sendSelectedValue()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openScanner()
            } else {
                showMessage("Camera permission is required to scan.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bridge = LocalBridgeClient(applicationContext)

        binding.codeInput.doAfterTextChanged {
            binding.codeInputLayout.error = null
            binding.connectButton.isEnabled = !isBusy() && it?.length == 6
        }

        binding.connectButton.setOnClickListener { connectWithCode() }
        binding.advancedToggle.setOnClickListener {
            binding.ipInputLayout.visibility =
                if (binding.ipInputLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.scanButton.setOnClickListener { requestCameraAndOpenScanner() }
        binding.sendButton.setOnClickListener { sendSelectedValue() }
        binding.selectedText.doAfterTextChanged { updateButtons() }

        updateUiFromSavedPairing()
        verifySavedConnection()
    }

    private fun requestCameraAndOpenScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openScanner() {
        scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    }

    private fun connectWithCode() {
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        if (!code.matches(Regex("\\d{6}"))) {
            binding.codeInputLayout.error = "Enter the complete six-digit code."
            return
        }

        hideKeyboard()
        setBusy(true, "Finding your PC on the local network…")

        lifecycleScope.launch {
            try {
                val endpoint = withContext(Dispatchers.IO) {
                    bridge.discover(
                        lastBaseUrl = prefs.getString(KEY_BASE_URL, null),
                        manualIp = binding.ipInput.text?.toString()
                    )
                }

                val result = withContext(Dispatchers.IO) {
                    bridge.pair(
                        endpoint = endpoint,
                        code = code,
                        deviceId = deviceId(),
                        deviceName = deviceName()
                    )
                }

                prefs.edit()
                    .putString(KEY_BASE_URL, endpoint.baseUrl)
                    .putString(KEY_TOKEN, result.token)
                    .putString(KEY_SERVER_ID, result.serverId)
                    .putString(KEY_SERVER_NAME, result.serverName)
                    .apply()

                binding.codeInput.setText("")
                setBusy(false)
                updateUi(
                    paired = true,
                    reachable = true,
                    serverName = result.serverName,
                    excelOpen = result.excelConnected
                )
                showMessage(
                    if (result.excelConnected) {
                        "Connected. Select a cell in Excel and scan."
                    } else {
                        "Phone paired. Open the Scan2Cell pane in Excel before sending."
                    }
                )
            } catch (error: Exception) {
                setBusy(false)
                showMessage(error.message ?: "Could not connect to the PC.")
            }
        }
    }

    private fun sendSelectedValue() {
        val text = binding.selectedText.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            showMessage("Scan or enter a value first.")
            return
        }

        val token = prefs.getString(KEY_TOKEN, null)
        val savedBaseUrl = prefs.getString(KEY_BASE_URL, null)
        val savedServerId = prefs.getString(KEY_SERVER_ID, null)

        if (token.isNullOrBlank() || savedBaseUrl.isNullOrBlank()) {
            showMessage("Pair this phone with Excel first.")
            return
        }

        hideKeyboard()
        setBusy(true, "Sending to the selected Excel cell…")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    sendWithOneRediscovery(
                        originalBaseUrl = savedBaseUrl,
                        expectedServerId = savedServerId,
                        token = token,
                        text = text
                    )
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

    private fun sendWithOneRediscovery(
        originalBaseUrl: String,
        expectedServerId: String?,
        token: String,
        text: String
    ): LocalBridgeClient.SendResult {
        try {
            return bridge.send(
                baseUrl = originalBaseUrl,
                token = token,
                deviceId = deviceId(),
                text = text
            )
        } catch (first: Exception) {
            if (first.message?.contains("not paired", ignoreCase = true) == true) throw first

            val endpoint = bridge.discover(lastBaseUrl = null, manualIp = null)
            if (!expectedServerId.isNullOrBlank() &&
                endpoint.serverId.isNotBlank() &&
                endpoint.serverId != expectedServerId
            ) {
                throw IllegalStateException(
                    "A different Scan2Cell PC was found. Pair again using its six-digit code."
                )
            }

            prefs.edit().putString(KEY_BASE_URL, endpoint.baseUrl).apply()
            return bridge.send(
                baseUrl = endpoint.baseUrl,
                token = token,
                deviceId = deviceId(),
                text = text
            )
        }
    }

    private fun verifySavedConnection() {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return
        val token = prefs.getString(KEY_TOKEN, null) ?: return
        if (token.isBlank()) return

        lifecycleScope.launch {
            try {
                val endpoint = withContext(Dispatchers.IO) { bridge.probe(baseUrl) }
                updateUi(
                    paired = true,
                    reachable = true,
                    serverName = endpoint.name,
                    excelOpen = endpoint.excelConnected
                )
            } catch (_: Exception) {
                updateUiFromSavedPairing(reachable = false)
            }
        }
    }

    private fun updateUiFromSavedPairing(reachable: Boolean? = null) {
        val paired = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
        val serverName = prefs.getString(KEY_SERVER_NAME, "Excel PC") ?: "Excel PC"
        updateUi(
            paired = paired,
            reachable = reachable ?: false,
            serverName = serverName,
            excelOpen = null
        )
    }

    private fun updateUi(
        paired: Boolean,
        reachable: Boolean,
        serverName: String,
        excelOpen: Boolean?
    ) {
        val statusText: String
        val statusBackground: Int
        val statusColor: Int

        when {
            !paired -> {
                statusText = "Not connected"
                statusBackground = R.drawable.bg_status_offline
                statusColor = R.color.s2c_error
            }
            reachable && excelOpen == true -> {
                statusText = "Ready • $serverName"
                statusBackground = R.drawable.bg_status_online
                statusColor = R.color.s2c_success
            }
            reachable -> {
                statusText = "Paired • open Excel pane"
                statusBackground = R.drawable.bg_status_online
                statusColor = R.color.s2c_success
            }
            else -> {
                statusText = "Paired • looking for PC"
                statusBackground = R.drawable.bg_status_offline
                statusColor = R.color.s2c_error
            }
        }

        binding.connectionStatus.text = statusText
        binding.connectionStatus.setBackgroundResource(statusBackground)
        binding.connectionStatus.setTextColor(ContextCompat.getColor(this, statusColor))
        binding.scanButton.isEnabled = paired && !isBusy()
        updateButtons()
    }

    private fun updateButtons() {
        val paired = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
        binding.sendButton.isEnabled =
            paired && !isBusy() && !binding.selectedText.text.isNullOrBlank()
        binding.connectButton.isEnabled =
            !isBusy() && binding.codeInput.text?.length == 6
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        binding.root.tag = busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.codeInput.isEnabled = !busy
        binding.ipInput.isEnabled = !busy
        binding.advancedToggle.isEnabled = !busy
        binding.scanButton.isEnabled =
            !busy && !prefs.getString(KEY_TOKEN, null).isNullOrBlank()
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
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER
            .replaceFirstChar { character -> character.uppercase() }
        return "$manufacturer ${Build.MODEL}"
            .trim()
            .ifBlank { "Android phone" }
    }

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_SERVER_NAME = "serverName"
        private const val KEY_DEVICE_ID = "deviceId"
    }
}
