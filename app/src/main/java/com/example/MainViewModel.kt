package com.example

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DriveMode(val label: String, val description: String) {
    CLICK_TOGGLE(
        "Click to Start / Stop",
        "Tap direction to start moving, tap again or tap STOP to stop."
    ),
    PRESS_HOLD(
        "Press and Hold Mode",
        "Hold button to drive, release button to stop instantly."
    )
}

class MainViewModel(context: Context) : ViewModel() {

    val bluetoothManager = ESP32BluetoothManager(context)

    // Drive Mode configuration
    private val _driveMode = MutableStateFlow(DriveMode.CLICK_TOGGLE)
    val driveMode: StateFlow<DriveMode> = _driveMode.asStateFlow()

    // Current active movement command ("F", "B", "L", "R", "S" or null)
    private val _activeMovement = MutableStateFlow("S")
    val activeMovement: StateFlow<String> = _activeMovement.asStateFlow()

    // Current speed value (100 to 255)
    private val _currentSpeed = MutableStateFlow(200)
    val currentSpeed: StateFlow<Int> = _currentSpeed.asStateFlow()

    // Error messages
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Dialog state for device picker
    private val _isDevicePickerOpen = MutableStateFlow(false)
    val isDevicePickerOpen: StateFlow<Boolean> = _isDevicePickerOpen.asStateFlow()

    // List of paired devices
    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    // Button Configuration Persistence using SharedPreferences
    private val sharedPrefs = context.getSharedPreferences("rc_controller_prefs", Context.MODE_PRIVATE)

    private val _cmdForward = MutableStateFlow(sharedPrefs.getString("cmd_forward", "F") ?: "F")
    val cmdForward: StateFlow<String> = _cmdForward.asStateFlow()

    private val _cmdBackward = MutableStateFlow(sharedPrefs.getString("cmd_backward", "B") ?: "B")
    val cmdBackward: StateFlow<String> = _cmdBackward.asStateFlow()

    private val _cmdLeft = MutableStateFlow(sharedPrefs.getString("cmd_left", "L") ?: "L")
    val cmdLeft: StateFlow<String> = _cmdLeft.asStateFlow()

    private val _cmdRight = MutableStateFlow(sharedPrefs.getString("cmd_right", "R") ?: "R")
    val cmdRight: StateFlow<String> = _cmdRight.asStateFlow()

    private val _cmdStop = MutableStateFlow(sharedPrefs.getString("cmd_stop", "S") ?: "S")
    val cmdStop: StateFlow<String> = _cmdStop.asStateFlow()

    // Dialog state for command configuration settings
    private val _isConfigDialogOpen = MutableStateFlow(false)
    val isConfigDialogOpen: StateFlow<Boolean> = _isConfigDialogOpen.asStateFlow()

    init {
        // Automatically check paired devices if permissions are already granted
        refreshPairedDevices()
    }

    fun setDriveMode(mode: DriveMode) {
        _driveMode.value = mode
        // Safe measure: stop the car when switching modes
        triggerStop()
    }

    fun updateSpeed(newSpeed: Int) {
        val clampedSpeed = newSpeed.coerceIn(100, 255)
        if (_currentSpeed.value != clampedSpeed) {
            _currentSpeed.value = clampedSpeed
            // Send new speed over Bluetooth
            bluetoothManager.sendCommand(clampedSpeed.toString())
        }
    }

    fun refreshPairedDevices() {
        if (bluetoothManager.hasRequiredPermissions()) {
            _pairedDevices.value = bluetoothManager.getPairedDevices()
        }
    }

    fun openDevicePicker() {
        refreshPairedDevices()
        _isDevicePickerOpen.value = true
    }

    fun closeDevicePicker() {
        _isDevicePickerOpen.value = false
    }

    fun connectToDevice(deviceAddress: String) {
        _isDevicePickerOpen.value = false
        _errorMessage.value = null
        
        bluetoothManager.connect(deviceAddress, viewModelScope) { success, error ->
            if (!success) {
                _errorMessage.value = error
            }
        }
    }

    fun disconnectDevice() {
        bluetoothManager.disconnect()
        _activeMovement.value = "S"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun openConfigDialog() {
        _isConfigDialogOpen.value = true
    }

    fun closeConfigDialog() {
        _isConfigDialogOpen.value = false
    }

    fun updateButtonConfigurations(forward: String, backward: String, left: String, right: String, stop: String) {
        sharedPrefs.edit().apply {
            putString("cmd_forward", forward)
            putString("cmd_backward", backward)
            putString("cmd_left", left)
            putString("cmd_right", right)
            putString("cmd_stop", stop)
            apply()
        }
        _cmdForward.value = forward
        _cmdBackward.value = backward
        _cmdLeft.value = left
        _cmdRight.value = right
        _cmdStop.value = stop
    }

    /**
     * Helper to resolve physical command string from logical direction
     */
    fun resolveCommand(logicalDir: String): String {
        return when (logicalDir) {
            "F" -> _cmdForward.value
            "B" -> _cmdBackward.value
            "L" -> _cmdLeft.value
            "R" -> _cmdRight.value
            "S" -> _cmdStop.value
            else -> logicalDir
        }
    }

    /**
     * Handles movement input triggers based on current Drive Mode
     */
    fun handleMovementAction(command: String, isPressDown: Boolean) {
        val resolvedCmd = resolveCommand(command)
        if (_driveMode.value == DriveMode.PRESS_HOLD) {
            if (isPressDown) {
                // Press down: Send movement command
                _activeMovement.value = command
                bluetoothManager.sendCommand(resolvedCmd)
            } else {
                // Press up (Release): Send stop command
                triggerStop()
            }
        } else {
            // CLICK_TOGGLE Mode (Tap to toggle)
            if (isPressDown) { // Only handle on click event
                if (_activeMovement.value == command) {
                    // Tap again to stop
                    triggerStop()
                } else {
                    // Tap to start moving in this direction
                    _activeMovement.value = command
                    bluetoothManager.sendCommand(resolvedCmd)
                }
            }
        }
    }

    /**
     * Instantly stops the RC car and updates local state.
     */
    fun triggerStop() {
        _activeMovement.value = "S"
        bluetoothManager.sendCommand(resolveCommand("S"))
    }
}
