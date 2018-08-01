package eu.pretix.pretixdroid.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.view.View

import java.util.ArrayList
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanFilter
import android.preference.PreferenceManager
import android.widget.*
import eu.pretix.pretixdroid.BluetoothLeService
import eu.pretix.pretixdroid.R


class BleScanActivity : AppCompatActivity() {

    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothLeScanner: BluetoothLeScanner? = null

    private var mScanning: Boolean = false

    lateinit internal var btnScan: Button
    lateinit internal var listViewLE: ListView

    lateinit internal var adapterLeScanResult: ListAdapter
    private lateinit var displayListBluetoothDevice : ArrayList<Map<String, String>>

    private var mHandler: Handler? = null

    var scanResultOnItemClickListener: AdapterView.OnItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
        val device = parent.getItemAtPosition(position) as HashMap<String, String>
        val name = device.get("name")
        val address = device.get("address")

        val msg = (address + "\n")

        AlertDialog.Builder(this@BleScanActivity)
                .setTitle(name)
                .setMessage(msg)
                .setNegativeButton("cancel") { dialog, which -> }
                .setPositiveButton("Connect") { dialog, which ->
                    val intent = Intent(this@BleScanActivity,
                            MainActivity::class.java)
                    intent.putExtra(MainActivity.EXTRAS_DEVICE_ADDRESS,
                            address)
                    intent.putExtra(MainActivity.EXTRAS_DEVICE_NAME,
                            name)

                    if (mScanning) {
                        mBluetoothLeScanner!!.stopScan(scanCallback)
                        mScanning = false
                        btnScan.isEnabled = true
                    }
                    startActivity(intent)
                }
                .show()
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            addBluetoothDevice(result.device)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            for (result in results) {
                addBluetoothDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Toast.makeText(this@BleScanActivity,
                    "onScanFailed: " + errorCode.toString(),
                    Toast.LENGTH_LONG).show()
        }

        private fun addBluetoothDevice(device: BluetoothDevice) {
            val nameAndMac = HashMap<String, String>()
            nameAndMac.put("name", device.name)
            nameAndMac.put("address", device.address)

            if (!displayListBluetoothDevice.contains(nameAndMac)) {

                displayListBluetoothDevice.add(nameAndMac)

                listViewLE.invalidateViews()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blescan)


        // Check if BLE is supported on the device.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this,
                    "BLUETOOTH_LE not supported in this device!",
                    Toast.LENGTH_SHORT).show()
            finish()
        }

        getBluetoothAdapterAndLeScanner()

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this,
                    "bluetoothManager.getAdapter()==null",
                    Toast.LENGTH_SHORT).show()
            finish()
            return
        }


        // Quick permission check
        var permissionCheck = this.checkSelfPermission("Manifest.permission.ACCESS_FINE_LOCATION")
        permissionCheck += this.checkSelfPermission("Manifest.permission.ACCESS_COARSE_LOCATION")
        if (permissionCheck != 0) {

            this.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1001) //Any number
        }

        btnScan = findViewById<View>(R.id.scan) as Button
        btnScan.setOnClickListener { scanLeDevice(true) }
        listViewLE = findViewById<View>(R.id.lelist) as ListView

        displayListBluetoothDevice = ArrayList()
        adapterLeScanResult = SimpleAdapter(
                this, displayListBluetoothDevice,
                android.R.layout.simple_list_item_2,
                arrayOf("name", "address"),
                intArrayOf(android.R.id.text1, android.R.id.text2))

        listViewLE.adapter = adapterLeScanResult
        listViewLE.onItemClickListener = scanResultOnItemClickListener

        mHandler = Handler()

//        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        scanLeDevice(true)

    }

    override fun onResume() {
        super.onResume()

        if (!mBluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, RQS_ENABLE_BLUETOOTH)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {

        if (requestCode == RQS_ENABLE_BLUETOOTH && resultCode == Activity.RESULT_CANCELED) {
            finish()
            return
        }

        getBluetoothAdapterAndLeScanner()

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this,
                    "bluetoothManager.getAdapter()==null",
                    Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun getBluetoothAdapterAndLeScanner() {
        // Get BluetoothAdapter and BluetoothLeScanner.
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter
        mBluetoothLeScanner = mBluetoothAdapter!!.bluetoothLeScanner

        mScanning = false
    }

    /*
    to call startScan (ScanCallback callback),
    Requires BLUETOOTH_ADMIN permission.
    Must hold ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission to get results.
     */
    private fun scanLeDevice(enable: Boolean) {
        if (enable) {
            displayListBluetoothDevice.clear()
            listViewLE.invalidateViews()

            // Stops scanning after a pre-defined scan period.
            mHandler!!.postDelayed({
                mBluetoothLeScanner!!.stopScan(scanCallback)
                listViewLE.invalidateViews()

                Toast.makeText(this@BleScanActivity,
                        "Scan timeout",
                        Toast.LENGTH_LONG).show()

                mScanning = false
                btnScan.isEnabled = true
            }, SCAN_PERIOD)

            //scan specified devices only with ScanFilter
            val scanFilter = ScanFilter.Builder()
                    .setServiceUuid(BluetoothLeService.ParcelUuid_Silpion_UART)
                    .build()
            val scanFilters = ArrayList<ScanFilter>()
            scanFilters.add(scanFilter)

            val scanSettings = ScanSettings.Builder().build()

            mBluetoothLeScanner!!.startScan(scanFilters, scanSettings, scanCallback)


            mScanning = true
            btnScan.isEnabled = false
        } else {
            mBluetoothLeScanner!!.stopScan(scanCallback)
            mScanning = false
            btnScan.isEnabled = true
        }
    }

    companion object {

        private val RQS_ENABLE_BLUETOOTH = 1
        private val SCAN_PERIOD: Long = 10000
    }
}

