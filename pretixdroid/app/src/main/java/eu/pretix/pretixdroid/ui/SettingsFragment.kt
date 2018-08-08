package eu.pretix.pretixdroid.ui

import android.bluetooth.BluetoothGattCharacteristic
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.support.annotation.RawRes
import android.support.annotation.StringRes
import android.support.v7.app.AlertDialog
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import buildUartPrinterString

import com.joshdholtz.sentry.Sentry

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

import eu.pretix.libpretixsync.db.QueuedCheckIn

import eu.pretix.pretixdroid.AppConfig
import eu.pretix.pretixdroid.PretixDroid
import eu.pretix.pretixdroid.R
import eu.pretix.pretixdroid.ui.MainActivity.Companion.checkProvider
import eu.pretix.pretixdroid.ui.MainActivity.Companion.mBluetoothLeService

class SettingsFragment : PreferenceFragment() {

    private fun resetApp() {
        AlertDialog.Builder(activity)
                .setMessage(R.string.pref_reset_warning)
                .setNegativeButton(getString(R.string.cancel)) { dialog, whichButton ->
                    // do nothing
                }
                .setPositiveButton(getString(R.string.ok)) { dialog, whichButton ->
                    val config = AppConfig(activity)
                    config.resetEventConfig()
                    config.resetMqttConfig()
                    Toast.makeText(activity, R.string.reset_success, Toast.LENGTH_SHORT).show()
                }.create().show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences)

        val reset = findPreference("action_reset") as Preference
        reset.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val cnt = (activity.application as PretixDroid).data.count(QueuedCheckIn::class.java).get().value().toLong()
            if (cnt > 0) {
                AlertDialog.Builder(activity)
                        .setMessage(R.string.pref_reset_sync_warning)
                        .setNegativeButton(getString(R.string.cancel)) { dialog, whichButton ->
                            // do nothing
                        }
                        .setPositiveButton(getString(R.string.ok)) { dialog, whichButton -> resetApp() }.create().show()

            } else {
                resetApp()
            }
            true
        }

        val about = findPreference("action_about")
        about.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            asset_dialog(R.raw.about, R.string.about)
            true
        }

        val async = findPreference("async") as CheckBoxPreference
        async.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val config = AppConfig(activity)
            if (newValue is Boolean && newValue != config.asyncModeEnabled) {
                if (newValue) {
                    if (config.apiVersion < 3) {
                        AlertDialog.Builder(activity)
                                .setMessage(R.string.pref_async_not_supported)
                                .setPositiveButton(getString(R.string.dismiss)) { dialog, whichButton -> }.create().show()
                        return@OnPreferenceChangeListener false
                    }

                    AlertDialog.Builder(activity)
                            .setTitle(R.string.pref_async)
                            .setMessage(R.string.pref_async_warning)
                            .setNegativeButton(getString(R.string.cancel)) { dialog, whichButton -> }
                            .setPositiveButton(getString(R.string.ok)) { dialog, whichButton ->
                                config.asyncModeEnabled = true
                                async.isChecked = true
                            }.create().show()

                    return@OnPreferenceChangeListener false
                }
            }
            true
        }

        val ble_printing = findPreference("ble_printing") as CheckBoxPreference
        ble_printing.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val config = AppConfig(activity)
            if (newValue is Boolean && newValue != config.blePrintingEnabled) {
                if (mBluetoothLeService != null) {
                    if (newValue) {
                        mBluetoothLeService!!.connect(config.blePrinterAddress)
                        config.blePrintingEnabled = true
                        ble_printing.isChecked = true
                    } else {
                        mBluetoothLeService!!.disconnect()
                        config.blePrintingEnabled = false
                        ble_printing.isChecked = false
                    }
                }
            }
            true
        }

        val ble_scan = findPreference("action_blescan") as Preference
        ble_scan.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val config = AppConfig(activity)
            config.blePrinterAddress = ""
            mBluetoothLeService!!.disconnect()
            val intent_blescan = Intent("eu.pretix.pretixdroid.ui.BleScanActivity")
            startActivity(intent_blescan)
            return@OnPreferenceClickListener true
        }

        val print_test_badge = findPreference("action_print_test_badge")
        if (checkProvider != null && mBluetoothLeService != null) {
            val testData = checkProvider!!.testTicket
            print_test_badge.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mBluetoothLeService!!.writeUartData(
                        mBluetoothLeService!!.uartTxCharacteristic as BluetoothGattCharacteristic,
//                        buildUartPrinterString(testData[0], false, testData[1]))
//                buildUartPrinterString("Klaus-Bärbel Günther von Irgendwas-Doppelname genannt Jemand Anders", false, "1234567890"))
                buildUartPrinterString("Häns Würst", false, "1234567890"))
                return@OnPreferenceClickListener true
            }
        }

    }

    private fun asset_dialog(@RawRes htmlRes: Int, @StringRes title: Int) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null, false)
        val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(R.string.dismiss, null)
                .create()

        val textView = view.findViewById<View>(R.id.aboutText) as TextView

        var text = ""

        val builder = StringBuilder()
        val fis: InputStream
        try {
            fis = resources.openRawResource(htmlRes)
            val reader = BufferedReader(InputStreamReader(fis, "utf-8"))
            var line: String
            while (reader.readLine() != null) {
                line = reader.readLine()
                builder.append(line)
            }

            text = builder.toString()
            fis.close()
        } catch (e: IOException) {
            Sentry.captureException(e)
            e.printStackTrace()
        }

        textView.text = Html.fromHtml(text)
        textView.movementMethod = LinkMovementMethod.getInstance()

        dialog.show()
    }
}