package com.bluete.walkie

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BluetoothService : Service() {

    companion object {
        private const val TAG = "BluetoothService"
        private const val CHANNEL_ID = "bluetooth_walkie_channel"
        private const val NOTIFICATION_ID = 1
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "BluetoothWalkie"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2

        const val ACTION_STATE_CHANGED = "com.bluete.walkie.STATE_CHANGED"
        const val ACTION_TOAST = "com.bluete.walkie.TOAST"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val STATE_NONE = 0
        const val STATE_LISTEN = 1
        const val STATE_CONNECTING = 2
        const val STATE_CONNECTED = 3
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待连接中..."))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "蓝牙对讲服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持蓝牙对讲连接"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙对讲")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private var bluetoothAdapter: BluetoothAdapter? = null

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private var audioRecordThread: AudioRecordThread? = null
    private var audioPlayThread: AudioPlayThread? = null

    var state: Int = STATE_NONE
        private set

    var connectedDeviceName: String = ""
        private set

    var isTalking: Boolean = false
        private set

    private val recordBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    }

    private val playBufferSize: Int by lazy {
        AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
    }

    // ── lifecycle ──────────────────────────────────

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // ── public API ──────────────────────────────────

    fun startListening() {
        cancelConnect()
        cancelConnected()
        acceptThread?.cancel()
        acceptThread = AcceptThread()
        acceptThread!!.start()
        state = STATE_LISTEN
        updateNotification("等待连接中...")
    }

    fun connect(device: BluetoothDevice) {
        if (state == STATE_CONNECTING) cancelConnect()
        cancelConnected()
        connectThread = ConnectThread(device)
        connectThread!!.start()
        state = STATE_CONNECTING
    }

    fun disconnect() {
        stopAll()
        state = STATE_NONE
    }

    fun startTalking() {
        if (state != STATE_CONNECTED) return
        isTalking = true
        audioRecordThread?.startRecording()
    }

    fun stopTalking() {
        isTalking = false
        audioRecordThread?.stopRecording()
    }

    // ── internal ──────────────────────────────────

    private fun onConnected(socket: BluetoothSocket, device: BluetoothDevice) {
        cancelConnect()
        cancelConnected()
        acceptThread?.cancel()
        acceptThread = null

        connectedDeviceName = device.name ?: "未知设备"
        state = STATE_CONNECTED
        updateNotification("已连接：$connectedDeviceName")

        audioPlayThread = AudioPlayThread()
        audioPlayThread!!.start()

        connectedThread = ConnectedThread(socket)
        connectedThread!!.start()

        broadcastToast("已连接：$connectedDeviceName")
    }

    private fun onConnectionFailed() {
        broadcastToast("连接失败，请重试")
        state = STATE_LISTEN
        updateNotification("等待连接中...")
        startListening()
    }

    private fun onConnectionLost() {
        broadcastToast("连接已断开")
        stopAll()
        state = STATE_LISTEN
        updateNotification("等待连接中...")
        startListening()
    }

    private fun cancelConnect() {
        connectThread?.cancel()
        connectThread = null
    }

    private fun cancelConnected() {
        connectedThread?.cancel()
        connectedThread = null
        audioRecordThread?.stopRecording()
        audioRecordThread = null
        audioPlayThread?.stopPlaying()
        audioPlayThread = null
    }

    private fun stopAll() {
        cancelConnect()
        cancelConnected()
        acceptThread?.cancel()
        acceptThread = null
    }

    private fun broadcastState(newState: Int) {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_STATE, newState)
        intent.putExtra(EXTRA_DEVICE_NAME, connectedDeviceName)
        sendBroadcast(intent)
    }

    private fun broadcastToast(msg: String) {
        val intent = Intent(ACTION_TOAST)
        intent.putExtra(EXTRA_MESSAGE, msg)
        sendBroadcast(intent)
    }

    // ── AcceptThread ──────────────────────────────────

    private inner class AcceptThread : Thread("AcceptThread") {
        private val serverSocket: BluetoothServerSocket? by lazy {
            try {
                bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "ServerSocket creation failed", e)
                null
            }
        }
        private val running = AtomicBoolean(true)

        override fun run() {
            Log.i(TAG, "AcceptThread: start")
            while (running.get()) {
                val socket: BluetoothSocket? = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.e(TAG, "accept failed", e)
                    null
                }
                if (socket != null) {
                    when (this@BluetoothService.state) {
                        STATE_LISTEN, STATE_CONNECTING -> {
                            onConnected(socket, socket.remoteDevice)
                        }
                        else -> {
                            try { socket.close() } catch (e: IOException) { }
                        }
                    }
                }
            }
            Log.i(TAG, "AcceptThread: ended")
        }

        fun cancel() {
            running.set(false)
            try { serverSocket?.close() } catch (e: IOException) { }
        }
    }

    // ── ConnectThread ──────────────────────────────────

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread("ConnectThread") {
        private val socket: BluetoothSocket? by lazy {
            try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "socket creation failed", e)
                null
            }
        }

        override fun run() {
            Log.i(TAG, "ConnectThread: connecting")
            bluetoothAdapter?.cancelDiscovery()
            try {
                socket?.connect()
            } catch (e: IOException) {
                Log.e(TAG, "connect failed", e)
                try { socket?.close() } catch (ex: IOException) { }
                onConnectionFailed()
                return
            }
            connectThread = null
            socket?.let { onConnected(it, device) }
        }

        fun cancel() {
            try { socket?.close() } catch (e: IOException) { }
        }
    }

    // ── ConnectedThread ──────────────────────────────────

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread("ConnectedThread") {
        private val inputStream: InputStream? by lazy {
            try { socket.inputStream } catch (e: IOException) { null }
        }
        private val outputStream: OutputStream? by lazy {
            try { socket.outputStream } catch (e: IOException) { null }
        }
        private val running = AtomicBoolean(true)

        override fun run() {
            Log.i(TAG, "ConnectedThread: start")
            audioRecordThread = AudioRecordThread(outputStream)
            audioRecordThread!!.start()

            val buffer = ByteArray(playBufferSize)
            while (running.get()) {
                val bytesRead: Int = try {
                    inputStream?.read(buffer) ?: -1
                } catch (e: IOException) {
                    Log.e(TAG, "read failed", e)
                    -1
                }
                if (bytesRead <= 0) {
                    onConnectionLost()
                    break
                }
                audioPlayThread?.enqueue(buffer.copyOf(bytesRead))
            }
            Log.i(TAG, "ConnectedThread: end")
        }

        fun write(data: ByteArray) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "write failed", e)
                onConnectionLost()
            }
        }

        fun cancel() {
            running.set(false)
            try { socket.close() } catch (e: IOException) { }
        }
    }

    // ── AudioRecordThread ──────────────────────────────────

    private inner class AudioRecordThread(private val outputStream: OutputStream?) : Thread("AudioRecordThread") {
        private var audioRecord: AudioRecord? = null
        private val recording = AtomicBoolean(false)
        private val running = AtomicBoolean(true)

        @SuppressLint("MissingPermission")
        override fun run() {
            Log.i(TAG, "AudioRecordThread: start")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, recordBufferSize
            )
            val buffer = ByteArray(recordBufferSize)
            while (running.get()) {
                if (!recording.get()) {
                    sleep(20)
                    continue
                }
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    try {
                        outputStream?.write(buffer, 0, read)
                        outputStream?.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "write failed", e)
                        break
                    }
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            Log.i(TAG, "AudioRecordThread: end")
        }

        fun startRecording() {
            recording.set(true)
            audioRecord?.startRecording()
        }

        fun stopRecording() {
            recording.set(false)
            audioRecord?.stop()
        }

        fun cancel() {
            running.set(false)
            interrupt()
        }
    }

    // ── AudioPlayThread ──────────────────────────────────

    private inner class AudioPlayThread : Thread("AudioPlayThread") {
        private var audioTrack: AudioTrack? = null
        private val queue = ConcurrentLinkedQueue<ByteArray>()
        private val running = AtomicBoolean(true)

        override fun run() {
            Log.i(TAG, "AudioPlayThread: start")
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            while (running.get()) {
                val data = queue.poll()
                if (data != null) {
                    audioTrack?.write(data, 0, data.size)
                } else {
                    sleep(5)
                }
            }
            audioTrack?.stop()
            audioTrack?.release()
            Log.i(TAG, "AudioPlayThread: end")
        }

        fun enqueue(data: ByteArray) {
            if (queue.size > 20) queue.poll()
            queue.offer(data)
        }

        fun stopPlaying() {
            running.set(false)
            interrupt()
        }
    }
}
