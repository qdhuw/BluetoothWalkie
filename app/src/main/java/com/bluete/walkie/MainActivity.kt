package com.bluete.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluete.walkie.databinding.ActivityMainBinding

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothService: BluetoothService? = null
    private var serviceBound = false

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceAdapter

    // ── permissions ──────────────────────────────────

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            initBluetooth()
        } else {
            Toast.makeText(this, "需要蓝牙和录音权限", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startListeningAndScan()
        }
    }

    // ── receivers ──────────────────────────────────

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothService.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothService.EXTRA_STATE, BluetoothService.STATE_NONE)
                val deviceName = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_NAME) ?: ""
                updateConnectionUI(state, deviceName)
            } else if (action == BluetoothService.ACTION_TOAST) {
                val msg = intent.getStringExtra(BluetoothService.EXTRA_MESSAGE)
                if (msg != null) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val deviceDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothDevice.ACTION_FOUND) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let { addDevice(it) }
            } else if (action == BluetoothAdapter.ACTION_DISCOVERY_STARTED) {
                binding.btnScan.text = "停止扫描"
                binding.scanProgress.visibility = View.VISIBLE
                binding.tvScanHint.text = "正在扫描附近蓝牙设备..."
            } else if (action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                binding.btnScan.text = "扫描设备"
                binding.scanProgress.visibility = View.GONE
                if (discoveredDevices.isEmpty()) {
                    binding.tvScanHint.text = "未发现设备，请确保对方手机蓝牙已开启"
                } else {
                    binding.tvScanHint.text = "点击设备发起连接"
                }
            }
        }
    }

    // ── service ──────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bluetoothService = (service as BluetoothService.LocalBinder).getService()
            serviceBound = true
            startListeningAndScan()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bluetoothService = null
            serviceBound = false
        }
    }

    // ── lifecycle ──────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceList()
        setupPttButton()
        setupScanButton()
        setupPairedDevicesButton()

        if (hasAllPermissions()) {
            initBluetooth()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, BluetoothService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val f1 = IntentFilter(BluetoothService.ACTION_STATE_CHANGED)
        val f2 = IntentFilter(BluetoothService.ACTION_TOAST)
        registerReceiver(bluetoothStateReceiver, f1)
        registerReceiver(bluetoothStateReceiver, f2)

        val f3 = IntentFilter(BluetoothDevice.ACTION_FOUND)
        val f4 = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        val f5 = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(deviceDiscoveryReceiver, f3)
        registerReceiver(deviceDiscoveryReceiver, f4)
        registerReceiver(deviceDiscoveryReceiver, f5)
    }

    override fun onStop() {
        super.onStop()
        bluetoothAdapter?.cancelDiscovery()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) { }
        try {
            unregisterReceiver(deviceDiscoveryReceiver)
        } catch (e: Exception) { }
    }

    // ── init ──────────────────────────────────

    private fun initBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            showDialog("错误", "设备不支持蓝牙")
            return
        }
        if (bluetoothAdapter!!.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun startListeningAndScan() {
        bluetoothService?.startListening()
        loadPairedDevices()
    }

    // ── device list ──────────────────────────────────

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter(discoveredDevices) { device ->
            connectToDevice(device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun loadPairedDevices() {
        val paired = bluetoothAdapter?.bondedDevices
        if (paired == null) return
        discoveredDevices.clear()
        discoveredDevices.addAll(paired)
        deviceAdapter.notifyDataSetChanged()
        if (discoveredDevices.isEmpty()) {
            binding.tvScanHint.text = "暂无已配对设备，请点击「扫描设备」"
        } else {
            binding.tvScanHint.text = "已配对设备（点击连接）"
        }
    }

    private fun addDevice(device: BluetoothDevice) {
        val exists = discoveredDevices.any { it.address == device.address }
        if (!exists) {
            discoveredDevices.add(device)
            deviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
        }
    }

    // ── scan buttons ──────────────────────────────────

    private fun setupScanButton() {
        binding.btnScan.setOnClickListener {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            } else {
                discoveredDevices.clear()
                loadPairedDevices()
                bluetoothAdapter?.startDiscovery()
            }
        }
    }

    private fun setupPairedDevicesButton() {
        binding.btnPaired.setOnClickListener {
            bluetoothAdapter?.cancelDiscovery()
            loadPairedDevices()
        }
    }

    // ── connect ──────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothAdapter?.cancelDiscovery()
        val name = device.name ?: device.address
        showDialogConfirm("连接到 $name", "确认连接到该设备进行蓝牙对讲？") {
            bluetoothService?.connect(device)
            binding.tvStatus.text = "正在连接 $name ..."
        }
    }

    // ── PTT button ──────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPttButton() {
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val svc = bluetoothService
                    if (svc != null && svc.state == BluetoothService.STATE_CONNECTED) {
                        svc.startTalking()
                        binding.btnPtt.isActivated = true
                        binding.btnPtt.text = "松开结束"
                        binding.tvVoiceIndicator.visibility = View.VISIBLE
                        vibrate(50)
                    } else {
                        Toast.makeText(this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bluetoothService?.stopTalking()
                    binding.btnPtt.isActivated = false
                    binding.btnPtt.text = "按住说话"
                    binding.tvVoiceIndicator.visibility = View.GONE
                    vibrate(30)
                    true
                }
                else -> false
            }
        }

        binding.btnDisconnect.setOnClickListener {
            bluetoothService?.disconnect()
            bluetoothService?.startListening()
        }
    }

    // ── UI update ──────────────────────────────────

    private fun updateConnectionUI(state: Int, deviceName: String) {
        when (state) {
            BluetoothService.STATE_NONE -> {
                binding.tvStatus.text = "未连接"
                binding.cardConnected.visibility = View.GONE
                binding.cardDevices.visibility = View.VISIBLE
                binding.btnPtt.isEnabled = false
                binding.btnDisconnect.visibility = View.GONE
            }
            BluetoothService.STATE_LISTEN -> {
                binding.tvStatus.text = "等待连接中..."
                binding.cardConnected.visibility = View.GONE
                binding.cardDevices.visibility = View.VISIBLE
                binding.btnPtt.isEnabled = false
                binding.btnDisconnect.visibility = View.GONE
            }
            BluetoothService.STATE_CONNECTING -> {
                binding.tvStatus.text = "连接中..."
                binding.btnPtt.isEnabled = false
            }
            BluetoothService.STATE_CONNECTED -> {
                binding.tvStatus.text = "已连接：$deviceName"
                binding.tvConnectedDevice.text = deviceName
                binding.cardConnected.visibility = View.VISIBLE
                binding.cardDevices.visibility = View.GONE
                binding.btnPtt.isEnabled = true
                binding.btnDisconnect.visibility = View.VISIBLE
                vibrate(100)
            }
        }
    }

    // ── utils ──────────────────────────────────

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showDialogConfirm(title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }
}
