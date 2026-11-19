package com.sdsync.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdsync.app.data.ConfigStore
import com.sdsync.app.data.DbHelper
import com.sdsync.app.data.FolderConfig
import com.sdsync.app.service.TransferService
import com.sdsync.app.util.StorageUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_SD_TREE = 200
        private const val REQ_PICK_FOLDER = 300
    }

    private lateinit var config: ConfigStore
    private lateinit var db: DbHelper
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var tvRecovered: TextView
    private lateinit var tvFilesMoved: TextView
    private lateinit var tvSdStatus: TextView
    private lateinit var rvFolders: RecyclerView
    private lateinit var rvHistory: RecyclerView
    private lateinit var chkBoot: CheckBox
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var transferAdapter: TransferAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = ConfigStore(this)
        db = DbHelper(this)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        tvRecovered = findViewById(R.id.tvRecovered)
        tvFilesMoved = findViewById(R.id.tvFilesMoved)
        tvSdStatus = findViewById(R.id.tvSdStatus)
        rvFolders = findViewById(R.id.rvFolders)
        rvHistory = findViewById(R.id.rvHistory)
        chkBoot = findViewById(R.id.chkBoot)

        folderAdapter = FolderAdapter(mutableListOf()) { fc -> removeFolder(fc) }
        rvFolders.layoutManager = LinearLayoutManager(this)
        rvFolders.adapter = folderAdapter

        transferAdapter = TransferAdapter(mutableListOf())
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = transferAdapter

        btnToggle.setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btnAddFolder).setOnClickListener { addFolder() }
        findViewById<Button>(R.id.btnSelectSd).setOnClickListener { pickSd() }
        chkBoot.setOnCheckedChangeListener { _, checked -> config.bootAutostart = checked }

        requestPermissionsIfNeeded()
        showAutostartHintIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMS)
        } else {
            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // dialog unavailable; ignore
                }
            }
        }
    }

    /**
     * One-time hint for Oppo/ColorOS: background apps are killed unless the user
     * enables auto-start in the system settings. We can't set that programmatically,
     * so we deep-link to the app's app-info page where the option lives.
     */
    private fun showAutostartHintIfNeeded() {
        val brand = Build.MANUFACTURER.lowercase()
        if (!(brand.contains("oppo") || brand.contains("oneplus"))) return
        if (config.autostartHintShown) return
        config.autostartHintShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.autostart_title)
            .setMessage(R.string.autostart_body)
            .setPositiveButton(R.string.autostart_open) { _, _ ->
                try {
                    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(appInfo)
                } catch (e: Exception) {
                    // settings page unavailable; ignore
                }
            }
            .setNegativeButton(R.string.autostart_dismiss, null)
            .show()
    }

    private fun toggleService() {
        val intent = Intent(this, TransferService::class.java).apply {
            action = if (TransferService.isRunning) TransferService.ACTION_PAUSE else TransferService.ACTION_START
        }
        startService(intent)
        refresh()
    }

    private fun pickSd() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQ_SD_TREE)
    }

    private fun addFolder() {
        val intent = Intent(this, FolderPickerActivity::class.java)
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    private fun removeFolder(fc: FolderConfig) {
        val list = config.getSourceFolders().toMutableList()
        list.removeAll { it.path == fc.path }
        config.saveSourceFolders(list)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SD_TREE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val uri = data.data!!
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    config.sdTreeUri = uri.toString()
                    refresh()
                }
            }
            REQ_PICK_FOLDER -> {
                if (resultCode == Activity.RESULT_OK) {
                    val paths = data?.getStringSetExtra(FolderPickerActivity.EXTRA_PATH)
                    if (!paths.isNullOrEmpty()) {
                        val list = config.getSourceFolders().toMutableList()
                        paths.forEach { path ->
                            if (list.none { it.path == path }) list.add(FolderConfig(path))
                        }
                        config.saveSourceFolders(list)
                    }
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        tvStatus.text = if (TransferService.isRunning) getString(R.string.status_running) else getString(R.string.status_idle)
        btnToggle.text = if (TransferService.isRunning) getString(R.string.btn_pause) else getString(R.string.btn_start)

        tvRecovered.text = StorageUtils.humanSize(config.totalMovedBytes)
        tvFilesMoved.text = config.totalFilesMoved.toString()

        val sdUri = config.sdTreeUri
        tvSdStatus.text = if (sdUri != null) {
            getString(R.string.sd_dest) + ": " + sdUri
        } else {
            getString(R.string.status_no_sd)
        }

        folderAdapter.update(config.getSourceFolders())
        transferAdapter.update(db.recentTransfers(50))
        chkBoot.isChecked = config.bootAutostart
    }
}
