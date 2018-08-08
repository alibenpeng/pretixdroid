package eu.pretix.pretixdroid.ui

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import buildUartPrinterString

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.joshdholtz.sentry.Sentry

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Timer
import java.util.TimerTask

import eu.pretix.libpretixsync.api.PretixApi
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.QueuedCheckIn
import eu.pretix.pretixdroid.AppConfig
import eu.pretix.pretixdroid.BluetoothLeService
import eu.pretix.pretixdroid.BuildConfig
//import eu.pretix.pretixdroid.MqttManager
import eu.pretix.pretixdroid.PretixDroid
import eu.pretix.pretixdroid.R
import eu.pretix.pretixdroid.async.SyncService
import me.dm7.barcodescanner.zxing.ZXingScannerView

class MainActivity : AppCompatActivity(), ZXingScannerView.ResultHandler, MediaPlayer.OnCompletionListener {
    private var qrView: CustomizedScannerView? = null
    private var lastScanTime: Long = 0
    private var lastScanCode: String? = null
    private var state = State.SCANNING
    private var timeoutHandler: Handler? = null
    private var blinkExecute: Runnable? = null
    private var blinkDark = true
    private var blinkHandler: Handler? = null
    private var mediaPlayer: MediaPlayer? = null
    private var config: AppConfig? = null
    private var timer: Timer? = null
    private var questionsDialog: Dialog? = null
    private var unpaidDialog: Dialog? = null
//    private var mqttManager: MqttManager? = null

    private val TAG = MainActivity::class.java.simpleName
    private var mDeviceName: String? = null
    private var mDeviceAddress: String? = null

    private val mServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            mBluetoothLeService = (service as BluetoothLeService.LocalBinder).service
            Log.i(TAG, "BLE Service connected")
            if (!mBluetoothLeService!!.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth")
                finish()
            }
            // Automatically connects to the device upon successful start-up initialization.
            connectGatt()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.i(TAG, "BLE Service disconnected")
            mBluetoothLeService = null
        }
    }

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private val mGattUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothLeService.ACTION_GATT_CONNECTED -> {
                    Log.i(TAG, "ACTION_GATT_CONNECTED")
                    invalidateOptionsMenu()
                    config!!.bleConnected = true
                }

                BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                    Log.i(TAG, "ACTION_GATT_DISCONNECTED")
                    invalidateOptionsMenu()
                    config!!.bleConnected = false

                    connectGatt()
                }

                BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED -> {
                    Log.i(TAG, "ACTION_GATT_SERVICES_DISCOVERED")
                    // Initialize onClickListener for the Send-Button
/*                    if (mBluetoothLeService != null) {
                        blePrinterReady = true
                    }*/
                }

                BluetoothLeService.ACTION_DATA_AVAILABLE -> {
                    Log.i(TAG, "ACTION_GATT_DATA_AVAILABLE")
                }

                BluetoothLeService.ACTION_DATA_SENT -> {
                    Log.i(TAG, "ACTION_GATT_DATA_SENT")
                }

            }
        }
    }


    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Intent receiver for LECOM-manufactured hardware scanners
            val barcode = intent.getByteArrayExtra("barocode") // sic!
            val barocodelen = intent.getIntExtra("length", 0)
            val barcodeStr = String(barcode, 0, barocodelen)
            handleScan(barcodeStr)
        }

    }

    enum class State {
        SCANNING, LOADING, RESULT
    }

    private fun connectGatt() {
        if (config!!.blePrintingEnabled) {
            if (BluetoothAdapter.checkBluetoothAddress(mDeviceAddress)) {
                val result = mBluetoothLeService!!.connect(mDeviceAddress)
                Log.d(TAG, "GATT Connect request result=$result")
                if (!result) {
                    Toast.makeText(this,
                            "Connect attempt failed!",
                            Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Log.d(TAG, "GATT Connect request: \"${mDeviceAddress}\" is not a valid Bluetooth Address!")
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        if (BuildConfig.SENTRY_DSN != null) {
            Sentry.init(this, BuildConfig.SENTRY_DSN)
        }

        checkProvider = (application as PretixDroid).newCheckProvider
        config = AppConfig(this)

//        mqttManager = MqttManager.getInstance(config)
//        mqttManager!!.start()


        val intent = intent
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME)
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS)

        if (mDeviceAddress == null || mDeviceAddress == "" || mDeviceAddress == "DEVICE_ADDRESS") {
            Log.w(TAG, "Device address empty - setting from preferences!")
            mDeviceAddress = config!!.blePrinterAddress
        }

        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "BLE bindService(): mServiceConnection")


        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter())
        if (mBluetoothLeService != null) {
            connectGatt()
        } else {
            Log.d(TAG, "GATT Connect request: mBluetoothLeService == null")
        }

        setContentView(R.layout.activity_main)

        qrView = findViewById<View>(R.id.qrdecoderview) as CustomizedScannerView
        qrView!!.setResultHandler(this)
        qrView!!.setAutoFocus(config!!.autofocus)
        qrView!!.flash = config!!.flashlight

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Sentry.addBreadcrumb("main.startup", "Permission request started")
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA),
                    PERMISSIONS_REQUEST_CAMERA)
        }

        val formats = ArrayList<BarcodeFormat>()
        formats.add(BarcodeFormat.QR_CODE)

        qrView!!.setFormats(formats)

        volumeControlStream = AudioManager.STREAM_MUSIC
        mediaPlayer = buildMediaPlayer(this)

        timeoutHandler = Handler()
        blinkHandler = Handler()

        findViewById<View>(R.id.rlSyncStatus).setOnClickListener { showSyncStatusDetails() }

        resetView()

        supportActionBar!!.setDisplayShowHomeEnabled(true)
        supportActionBar!!.setIcon(R.drawable.ic_logo)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CAMERA -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Sentry.addBreadcrumb("main.startup", "Permission granted")
                    if (config!!.camera) {
                        qrView!!.startCamera()
                    }
                } else {
                    Sentry.addBreadcrumb("main.startup", "Permission request denied")
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private inner class SyncTriggerTask : TimerTask() {
        override fun run() {
            triggerSync()
        }
    }

    private inner class UpdateSyncStatusTask : TimerTask() {
        override fun run() {
            runOnUiThread { updateSyncStatus() }
        }
    }

    public override fun onResume() {
        super.onResume()
        if (config!!.camera) {
            qrView!!.setResultHandler(this)
            qrView!!.startCamera()
            qrView!!.setAutoFocus(config!!.autofocus)
            resetView()
        } else {
            val filter = IntentFilter()
            // Broadcast sent by Lecom scanners
            filter.addAction("scan.rcv.message")
            registerReceiver(scanReceiver, filter)
        }

//        mqttManager!!.reconnect()

        if (config!!.blePrintingEnabled) {
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter())
            if (mBluetoothLeService != null) {
                connectGatt()
                //            val result = mBluetoothLeService!!.connect(mDeviceAddress)
                //            Log.d(TAG, "GATT Connect request result=$result")
            } else {
                Log.d(TAG, "GATT Connect request: mBluetoothLeService == null")
            }
        }

        timer = Timer()
        timer!!.schedule(SyncTriggerTask(), 1000, 10000)
        timer!!.schedule(UpdateSyncStatusTask(), 500, 500)
        updateSyncStatus()
    }

    public override fun onPause() {
        super.onPause()
        if (config!!.camera) {
            qrView!!.stopCamera()
        } else {
            unregisterReceiver(scanReceiver)
        }
        timer!!.cancel()

        unregisterReceiver(mGattUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(mServiceConnection)
        mBluetoothLeService = null
    }

    override fun handleResult(rawResult: Result) {
        if (config!!.camera) {
            qrView!!.resumeCameraPreview(this)
        }
        val s = rawResult.text
        if (s == lastScanCode && System.currentTimeMillis() - lastScanTime < 5000) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        lastScanCode = s

        handleScan(s)
    }

    fun handleScan(s: String) {
        if (questionsDialog != null && questionsDialog!!.isShowing) {
            // Do not scan while asking questions
            return
        }
        if (unpaidDialog != null && unpaidDialog!!.isShowing) {
            // Do not scan while asking questions
            return
        }

        if (config!!.soundEnabled) mediaPlayer!!.start()
        resetView()

        if (config!!.isConfigured) {
            handleTicketScanned(s, ArrayList(), false)
        } else {
            handleConfigScanned(s)
        }
    }

    private fun handleConfigScanned(s: String) {
        Sentry.addBreadcrumb("main.scanned", "Config scanned")

        try {
            val jsonObject = JSONObject(s)
            if (jsonObject.getInt("version") > PretixApi.SUPPORTED_API_VERSION) {
                displayScanResult(TicketCheckProvider.CheckResult(
                        TicketCheckProvider.CheckResult.Type.ERROR,
                        getString(R.string.err_qr_version)), null, false)
            } else {
                if (jsonObject.getInt("version") < 3) {
                    config!!.asyncModeEnabled = false
                }
                config!!.setEventConfig(jsonObject.getString("url"), jsonObject.getString("key"),
                        jsonObject.getInt("version"), jsonObject.optBoolean("show_info", true),
                        jsonObject.optBoolean("allow_search", true))

                config!!.setMqttConfig(jsonObject.getString("mqtt_url"), jsonObject.getString("mqtt_user"),
                        jsonObject.getString("mqtt_pass"), jsonObject.getString("mqtt_client_prefix"),
                        jsonObject.getString("mqtt_pub"), jsonObject.getString("mqtt_status"))
//                mqttManager!!.start()

                checkProvider = (application as PretixDroid).newCheckProvider
                displayScanResult(TicketCheckProvider.CheckResult(
                        TicketCheckProvider.CheckResult.Type.VALID,
                        getString(R.string.config_done)), null, false)

                triggerSync()
            }
        } catch (e: JSONException) {
            displayScanResult(TicketCheckProvider.CheckResult(
                    TicketCheckProvider.CheckResult.Type.ERROR,
                    getString(R.string.err_qr_invalid)), null, false)
        }

    }

    private fun triggerSync() {
        val i = Intent(this, SyncService::class.java)
        startService(i)
    }

    private fun handleTicketScanned(s: String, answers: List<TicketCheckProvider.Answer>, ignore_unpaid: Boolean) {
        Sentry.addBreadcrumb("main.scanned", "Ticket scanned")

        state = State.LOADING
        findViewById<View>(R.id.tvScanResult).visibility = View.GONE
        findViewById<View>(R.id.pbScan).visibility = View.VISIBLE
        CheckTask().execute(s, answers, ignore_unpaid)
    }

    private fun updateSyncStatus() {
        if (config!!.asyncModeEnabled) {
            findViewById<View>(R.id.rlSyncStatus).visibility = View.VISIBLE

            if (config!!.lastFailedSync > config!!.lastSync || System.currentTimeMillis() - config!!.lastDownload > 5 * 60 * 1000) {
                findViewById<View>(R.id.rlSyncStatus).setBackgroundColor(ContextCompat.getColor(this, R.color.scan_result_err))
            } else {
                findViewById<View>(R.id.rlSyncStatus).setBackgroundColor(ContextCompat.getColor(this, R.color.scan_result_ok))
            }
            val text: String
            val diff = System.currentTimeMillis() - config!!.lastDownload
            if (config!!.lastDownload == 0L) {
                text = getString(R.string.sync_status_never)
            } else if (diff > 24 * 3600 * 1000) {
                val days = (diff / (24 * 3600 * 1000)).toInt()
                text = resources.getQuantityString(R.plurals.time_days, days, days)
            } else if (diff > 3600 * 1000) {
                val hours = (diff / (3600 * 1000)).toInt()
                text = resources.getQuantityString(R.plurals.time_hours, hours, hours)
            } else if (diff > 60 * 1000) {
                val mins = (diff / (60 * 1000)).toInt()
                text = resources.getQuantityString(R.plurals.time_minutes, mins, mins)
            } else {
                text = getString(R.string.sync_status_now)
            }

            (findViewById<View>(R.id.tvSyncStatus) as TextView).text = text
        } else {
            findViewById<View>(R.id.rlSyncStatus).visibility = View.GONE
        }
    }

    fun showSyncStatusDetails() {
        val lastSync = Calendar.getInstance()
        lastSync.timeInMillis = config!!.lastSync
        val lastSyncFailed = Calendar.getInstance()
        lastSyncFailed.timeInMillis = config!!.lastFailedSync
        val cnt = (application as PretixDroid).data.count(QueuedCheckIn::class.java).get().value().toLong()

        val formatter = SimpleDateFormat(getString(R.string.sync_status_date_format))
        AlertDialog.Builder(this)
                .setTitle(R.string.sync_status)
                .setMessage(
                        getString(R.string.sync_status_last) + "\n" +
                                formatter.format(lastSync.time) + "\n\n" +
                                getString(R.string.sync_status_local) + cnt +
                                if (config!!.lastFailedSync > 0)
                                    "\n\n" +
                                            getString(R.string.sync_status_last_failed) + "\n" +
                                            formatter.format(lastSyncFailed.time) +
                                            "\n" + config!!.lastFailedSyncMsg
                                else
                                    ""

                )
                .setPositiveButton(R.string.dismiss) { dialog, which -> }
                .show()

    }

    private fun resetView() {
        val tvScanResult = findViewById<View>(R.id.tvScanResult) as TextView
        timeoutHandler!!.removeCallbacksAndMessages(null)
        blinkHandler!!.removeCallbacksAndMessages(null)
        tvScanResult.visibility = View.VISIBLE
        findViewById<View>(R.id.rlWarning).visibility = View.GONE
        findViewById<View>(R.id.tvTicketName).visibility = View.INVISIBLE
        findViewById<View>(R.id.tvAttendeeName).visibility = View.INVISIBLE
        findViewById<View>(R.id.tvOrderCode).visibility = View.INVISIBLE
        findViewById<View>(R.id.tvPrintBadge).visibility = View.INVISIBLE
        (findViewById<View>(R.id.tvTicketName) as TextView).text = ""
        (findViewById<View>(R.id.tvScanResult) as TextView).text = ""
        (findViewById<View>(R.id.tvAttendeeName) as TextView).text = ""
        (findViewById<View>(R.id.tvOrderCode) as TextView).text = ""
        findViewById<View>(R.id.rlScanStatus).setBackgroundColor(
                ContextCompat.getColor(this, R.color.scan_result_unknown))

        if (config!!.isConfigured) {
            tvScanResult.setText(R.string.hint_scan)
        } else {
            tvScanResult.setText(R.string.hint_config)
        }

        if (!config!!.camera) {
            qrView!!.visibility = View.GONE
        } else {
            qrView!!.visibility = View.VISIBLE
        }
    }

    inner class CheckTask : AsyncTask<Any, Int, TicketCheckProvider.CheckResult>() {
        internal lateinit var answers: List<TicketCheckProvider.Answer>
        internal var ignore_unpaid: Boolean = false

        override fun doInBackground(vararg params: Any): TicketCheckProvider.CheckResult {
            val secret = params[0] as String
            answers = params[1] as List<TicketCheckProvider.Answer>
            ignore_unpaid = params[2] as Boolean
            return if (secret.matches("[0-9A-Za-z-]+".toRegex())) {
                checkProvider!!.check(secret, answers, ignore_unpaid)
            } else {
                TicketCheckProvider.CheckResult(TicketCheckProvider.CheckResult.Type.INVALID, getString(R.string.scan_result_invalid))
            }
        }

        override fun onPostExecute(checkResult: TicketCheckProvider.CheckResult) {
            displayScanResult(checkResult, answers, ignore_unpaid)
            triggerSync()
        }
    }

    private fun printBadge(checkResult: TicketCheckProvider.CheckResult) {
        if (checkResult.attendee_name != null && checkResult.attendee_name != "null") {
//            val sat = if (checkResult.isRequireAttention) "Special Attention Ticket" else " " // FIXME: printer still requires whitespace

            if (!config!!.blePrintingEnabled) {
                Log.d(TAG, "BLE Printing disabled")
                Toast.makeText(this,
                        R.string.printing_disabled,
                        Toast.LENGTH_SHORT).show()
            } else if (!config!!.bleConnected || mBluetoothLeService == null) {
                Log.d(TAG, "Printer disconnected")
                Toast.makeText(this,
                        R.string.printer_disconnected,
                        Toast.LENGTH_SHORT).show()
            } else if (mBluetoothLeService!!.uartTxCharacteristic == null) {
                Log.d(TAG, "Printer connection error")
                Toast.makeText(this,
                        R.string.printer_connection_error,
                        Toast.LENGTH_SHORT).show()
            } else {
                // All clear, print away!
                mBluetoothLeService!!.writeUartData(
                        mBluetoothLeService!!.uartTxCharacteristic as BluetoothGattCharacteristic,
                        buildUartPrinterString(checkResult.attendee_name, checkResult.isRequireAttention, checkResult.orderCode))
//                mqttManager?.publish(checkResult.getAttendee_name() + ";" + sat + ";" + checkResult.getOrderCode());
            }
        } else {
            Log.d("Badge", "Nothing to print")
        }
    }

    private fun displayScanResult(checkResult: TicketCheckProvider.CheckResult, answers: List<TicketCheckProvider.Answer>?, ignore_unpaid: Boolean) {
        if (checkResult.type == TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED) {
            questionsDialog = QuestionDialogHelper.showDialog(this, checkResult, lastScanCode, { secret, answers, ignore_unpaid -> handleTicketScanned(secret, answers, ignore_unpaid) }, ignore_unpaid)
        }
        if (checkResult.type == TicketCheckProvider.CheckResult.Type.UNPAID && checkResult.isCheckinAllowed) {
            unpaidDialog = UnpaidOrderDialogHelper.showDialog(this, checkResult, lastScanCode, answers) { secret, answers, ignore_unpaid -> handleTicketScanned(secret, answers, ignore_unpaid) }
        }

        val tvScanResult = findViewById<View>(R.id.tvScanResult) as TextView
        val tvTicketName = findViewById<View>(R.id.tvTicketName) as TextView
        val tvAttendeeName = findViewById<View>(R.id.tvAttendeeName) as TextView
        val tvOrderCode = findViewById<View>(R.id.tvOrderCode) as TextView

        val tvPrintBadge = findViewById<View>(R.id.tvPrintBadge) as Button
        tvPrintBadge.setOnClickListener { printBadge(checkResult) }

        state = State.RESULT
        findViewById<View>(R.id.pbScan).visibility = View.INVISIBLE
        tvScanResult.visibility = View.VISIBLE

        if (checkResult.ticket != null) {
            tvTicketName.visibility = View.VISIBLE
            if (checkResult.variation != null && checkResult.variation != "null") {
                tvTicketName.text = checkResult.ticket + " – " + checkResult.variation
            } else {
                tvTicketName.text = checkResult.ticket
            }
        }

        if (checkResult.attendee_name != null && checkResult.attendee_name != "null") {
            tvAttendeeName.visibility = View.VISIBLE
            tvAttendeeName.text = checkResult.attendee_name
            tvPrintBadge.visibility = View.VISIBLE
        }

        if (checkResult.orderCode != null && checkResult.orderCode != "null") {
            tvOrderCode.visibility = View.VISIBLE
            tvOrderCode.text = checkResult.orderCode
        }

        var col = R.color.scan_result_unknown
        var default_string = R.string.err_unknown

        when (checkResult.type) {
            TicketCheckProvider.CheckResult.Type.ERROR -> {
                col = R.color.scan_result_err
                default_string = R.string.err_unknown
            }
            TicketCheckProvider.CheckResult.Type.INVALID -> {
                col = R.color.scan_result_err
                default_string = R.string.scan_result_invalid
            }
            TicketCheckProvider.CheckResult.Type.UNPAID -> {
                col = R.color.scan_result_err
                default_string = R.string.scan_result_unpaid
            }
            TicketCheckProvider.CheckResult.Type.PRODUCT -> {
                col = R.color.scan_result_err
                default_string = R.string.scan_result_product
            }
            TicketCheckProvider.CheckResult.Type.ANSWERS_REQUIRED -> {
                col = R.color.scan_result_warn
                default_string = R.string.scan_result_questions
            }
            TicketCheckProvider.CheckResult.Type.USED -> {
                col = R.color.scan_result_warn
                default_string = R.string.scan_result_used
            }
            TicketCheckProvider.CheckResult.Type.VALID -> {
                col = R.color.scan_result_ok
                default_string = R.string.scan_result_valid
                printBadge(checkResult)
            }
        }

        if (checkResult.message != null) {
            tvScanResult.text = checkResult.message
        } else {
            tvScanResult.text = getString(default_string)
        }
        findViewById<View>(R.id.rlScanStatus).setBackgroundColor(ContextCompat.getColor(this, col))

        findViewById<View>(R.id.rlWarning).visibility = if (checkResult.isRequireAttention) View.VISIBLE else View.GONE

        if (checkResult.isRequireAttention) {
            blinkExecute = Runnable {
                try {
                    if (blinkDark) {
                        findViewById<View>(R.id.rlWarning).setBackgroundColor(resources.getColor(R.color.scan_result_attention_alternate))
                        (findViewById<View>(R.id.tvWarning) as TextView).setTextColor(resources.getColor(R.color.pretix_brand_dark))
                        (findViewById<View>(R.id.ivWarning) as ImageView).setImageResource(R.drawable.ic_warning_dark_24dp)
                        blinkDark = false
                    } else {
                        findViewById<View>(R.id.rlWarning).setBackgroundColor(resources.getColor(R.color.scan_result_attention))
                        (findViewById<View>(R.id.tvWarning) as TextView).setTextColor(resources.getColor(R.color.white))
                        (findViewById<View>(R.id.ivWarning) as ImageView).setImageResource(R.drawable.ic_warning_white_24dp)
                        blinkDark = true
                    }
                } finally {
                    blinkHandler!!.postDelayed(blinkExecute, 200)
                }
            }
            blinkExecute!!.run()
        }

        timeoutHandler!!.postDelayed({ resetView() }, 10000)
    }

    override fun onCompletion(mp: MediaPlayer) {
        // When the beep has finished playing, rewind to queue up another one.
        mp.seekTo(0)
    }

    private fun buildMediaPlayer(activity: Context): MediaPlayer? {
        mediaPlayer = MediaPlayer()
        mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mediaPlayer!!.setOnCompletionListener(this)
        // mediaPlayer.setOnErrorListener(this);
        try {
            val file = activity.resources
                    .openRawResourceFd(R.raw.beep)
            try {
                mediaPlayer!!.setDataSource(file.fileDescriptor,
                        file.startOffset, file.length)
            } finally {
                file.close()
            }
            mediaPlayer!!.setVolume(0.10f, 0.10f)
            mediaPlayer!!.prepare()
            return mediaPlayer
        } catch (ioe: IOException) {
            mediaPlayer!!.release()
            return null
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)

        val checkable = menu.findItem(R.id.action_flashlight)
        checkable.isChecked = config!!.flashlight
/*

        if (config!!.bleConnected) {
            menu.findItem(R.id.menu_connect).isVisible = false
            menu.findItem(R.id.menu_disconnect).isVisible = true
//            menu.findItem(R.id.menu_print_test_ticket).isVisible = true
        } else {
            menu.findItem(R.id.menu_connect).isVisible = true
            menu.findItem(R.id.menu_disconnect).isVisible = false
//            menu.findItem(R.id.menu_print_test_ticket).isVisible = false
        }
*/

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_flashlight -> {
                config!!.flashlight = !item.isChecked
                if (config!!.camera) {
                    qrView!!.flash = !item.isChecked
                }
                item.isChecked = !item.isChecked
                return true
            }
/*            R.id.menu_print_test_ticket -> {
                mBluetoothLeService!!.writeUartData(
                        mBluetoothLeService!!.uartTxCharacteristic as BluetoothGattCharacteristic,
                        buildUartPrinterString("Klaus-Bärbel Günther von Irgendwas-Doppelname genannt Jemand Anders", "SPECIÄL ÄTTÜNTIÖN", "Örder Cöde"))
                return true
            }*/
/*            R.id.menu_connect -> {

//                mBluetoothLeService!!.connect(mDeviceAddress)
                connectGatt()
                return true
            }
            R.id.menu_disconnect -> {

                manuallyDisconnected = true
                mBluetoothLeService!!.disconnect()
                return true
            }*/
/*            R.id.action_blescan -> {
                val intent_blescan = Intent(this, BleScanActivity::class.java)
                startActivity(intent_blescan)
                return true
            }*/
            R.id.action_preferences -> {
                val intent_settings = Intent(this, SettingsActivity::class.java)
                startActivity(intent_settings)
                return true
            }
            R.id.action_search -> {
                if (config!!.isConfigured) {
                    val intent = Intent(this, SearchActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.not_configured, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            R.id.action_eventinfo -> {
                if (config!!.isConfigured) {
                    val intent = Intent(this, EventinfoActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.not_configured, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    companion object {

        var mBluetoothLeService: BluetoothLeService? = null
        var checkProvider: TicketCheckProvider? = null

        val PERMISSIONS_REQUEST_CAMERA = 10001
        val EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS"
        val EXTRAS_DEVICE_NAME = "DEVICE_NAME"

        private fun makeGattUpdateIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_SENT)
            return intentFilter
        }
    }
}
