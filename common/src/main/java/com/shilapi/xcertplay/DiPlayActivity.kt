// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = parentPage(); render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && (page == "home" || page == "settings" || page == "connection")) render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun parentPage() = if (page == "settings") "home" else "settings"

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG); isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false
        }
        val short = resources.configuration.screenHeightDp < 640
        val content = column().apply { setPadding(dp(32), dp(if (short) 12 else 20), dp(32), dp(if (short) 16 else 40)) }
        // Settings-style pages read best as a centred column, like iOS on a wide display.
        val width = if (page == "home") -1 else minOf(dp(1040), dp(resources.configuration.screenWidthDp))
        scroll.addView(content, FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL))
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        if (page == "home") {
            // Logo and status share the top bar so the tiles get the rest of a short screen.
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_looplink); contentDescription = getString(R.string.diplay)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            status = label(getString(R.string.ready_when_you_are), 22, TEXT, weight = 700).apply {
                compoundDrawablePadding = dp(10); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            header.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            // In the header, not under the tiles, so it never pushes them off a short screen.
            disconnectButton = button(getString(R.string.disconnect), false, RED, radius = 24, icon = R.drawable.ic_dp_close) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { textSize = 17f; setPadding(dp(24), 0, dp(24), 0); visibility = View.GONE }
            header.addView(disconnectButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(12) })
            header.addView(button(getString(R.string.car_home), false, TEXT, radius = 24, icon = R.drawable.ic_dp_home) {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }.apply { textSize = 17f; setPadding(dp(24), 0, dp(24), 0) }, LinearLayout.LayoutParams(-2, dp(48)))
        } else {
            header.addView(label(getString(R.string.back), 19, ACCENT).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_dp_back, ACCENT, 30), null, null, null)
                compoundDrawablePadding = dp(2); setPaddingRelative(0, 0, dp(16), 0)
                background = pressable(Color.TRANSPARENT, 12); isClickable = true; isFocusable = true
                setOnClickListener { page = parentPage(); render() }
            }, LinearLayout.LayoutParams(-2, dp(48)))
        }
        content.addView(header)
        content.addView(space(if (page == "home") (if (short) 8 else 16) else 12))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            "advanced" -> advanced(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    // Glanceable in a car: three large icon tiles under a status bar. Details live in Settings.
    private fun home(content: LinearLayout) {
        // Head units range from ~500dp to 1000dp tall; the tiles take the height left under the top bar.
        val height = resources.configuration.screenHeightDp
        val body = column().apply { gravity = Gravity.CENTER }
        val actions = column()
        // Problems sit above the tiles they block, as one notice each, never as loose coloured text.
        setupError?.let { actions.addView(notice(it)) }
        if (carHotspotOff()) actions.addView(notice(getString(R.string.msg_car_hotspot_off), getString(R.string.open_car_hotspot_settings)) { openCarWifiSettings() })
        val tiles = row()
        val tileHeight = dp(((height - 140) * .7f).toInt().coerceIn(120, 220))
        connectButton = homeTile(getString(R.string.connect_phone), R.drawable.ic_dp_connection, tileHeight, true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        tiles.addView(connectButton, LinearLayout.LayoutParams(0, tileHeight, 1f))
        tiles.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        tiles.addView(homeTile(getString(R.string.connect_with_usb), R.drawable.ic_dp_usb, tileHeight) { connect(false) }, LinearLayout.LayoutParams(0, tileHeight, 1f))
        tiles.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        tiles.addView(homeTile(getString(R.string.settings), R.drawable.ic_dp_settings, tileHeight) { page = "settings"; render() }, LinearLayout.LayoutParams(0, tileHeight, 1f))
        actions.addView(tiles)
        body.addView(actions, LinearLayout.LayoutParams(minOf(dp(960), dp(resources.configuration.screenWidthDp) - dp(64)), -2))
        // Fill the space under the header and centre the tiles in it.
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Large icon-over-label tile for the home screen. */
    private fun homeTile(title: String, icon: Int, height: Int, primary: Boolean = false, click: () -> Unit) =
        button(title, primary, if (primary) Color.WHITE else TEXT, radius = 24, click = click).apply {
            textSize = 19f; contentDescription = title
            // Pinned from the top so icons line up even when one label wraps to two lines.
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            val iconSize = (height / resources.displayMetrics.density * .3f).toInt().coerceIn(40, 64)
            setPadding(dp(12), (height * .2f).toInt(), dp(12), dp(8)); compoundDrawablePadding = dp(12)
            setCompoundDrawablesRelative(null, icon(icon, if (primary) Color.WHITE else ACCENT, iconSize), null, null)
        }

    private fun settings(content: LinearLayout) {
        content.addView(largeTitle(getString(R.string.settings), 40).apply { setPadding(0, 0, 0, dp(8)) })
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(valueRow(getString(R.string.choose_iphone), DiPlayPreferences.phoneName(this), R.drawable.ic_dp_phone) { choosePhone() }.first)
            card.addView(valueRow(getString(R.string.open_connection_setup), "", R.drawable.ic_dp_connection) { page = "connection"; render() }.first)
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
        }
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            carPlaySizeControl(card)
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.car_icon_in_carplay), getString(R.string.car_icon_in_carplay_description), AirPlayPersistence.loadOemIconVisible(this)) {
                AirPlayPersistence.saveOemIconVisible(this, it)
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this)) {
                AirPlayPersistence.saveLocationReportingEnabled(this, it)
                if (it && !hasPreciseLocation()) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    reconnectForLocation()
                }
            }
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false, icon = R.drawable.ic_dp_permissions) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false, icon = R.drawable.ic_dp_bluetooth) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false, icon = R.drawable.ic_dp_help) { wirelessHelp() }, matchButton(10, 60))
        }
        languageSettings(content)
        section(content, "") { card ->
            card.addView(valueRow(getString(R.string.about_diplay), "", R.drawable.ic_dp_about) { page = "about"; render() }.first)
            card.addView(valueRow(getString(R.string.advanced), "", R.drawable.ic_dp_settings) { page = "advanced"; render() }.first)
        }
        content.addView(label(getString(R.string.advanced_hint), 14, MUTED).apply { setPaddingRelative(dp(4), 0, 0, 0) })
    }

    // Options most drivers never need: video tuning, audio channels and diagnostic reports.
    private fun advanced(content: LinearLayout) {
        content.addView(largeTitle(getString(R.string.advanced), 40))
        content.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            choice(card, getString(R.string.video_rendering),
                listOf(getString(R.string.video_rendering_compatibility), getString(R.string.video_rendering_efficient)),
                if (AirPlayPersistence.loadSurfaceViewEnabled(this)) 1 else 0) {
                AirPlayPersistence.saveSurfaceViewEnabled(this, it == 1)
            }
            card.addView(label(getString(R.string.video_rendering_hint), 14, MUTED))
            choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
        }
        section(content, getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false, icon = R.drawable.ic_dp_save) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false, icon = R.drawable.ic_dp_folder) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(largeTitle(getString(R.string.diplay), 44))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    // With no saved hotspot name, Connect already opens hotspot setup, so there is nothing to warn about.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            storedSsid().isNotBlank() &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    // Three short steps; each is a picture choice or a single row, with the details kept in dialogs.
    private fun connectionSetup(content: LinearLayout) {
        content.addView(largeTitle(getString(R.string.connection_setup), 40).apply { setPadding(0, 0, 0, dp(8)) })
        section(content, getString(R.string.s_1_choose_your_connection), R.drawable.ic_dp_connection) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone), R.drawable.ic_dp_bluetooth) { card ->
            card.addView(valueRow(getString(R.string.choose_iphone), DiPlayPreferences.phoneName(this), R.drawable.ic_dp_phone) { choosePhone() }.first)
            card.addView(valueRow(getString(R.string.review_app_permissions), "", R.drawable.ic_dp_permissions) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.first)
        }
        section(content, getString(R.string.s_3_connect), R.drawable.ic_dp_phone) { card ->
            card.addView(row().apply {
                setPadding(0, dp(10), 0, dp(10))
                addView(button(getString(R.string.connect_phone), true, icon = R.drawable.ic_dp_connection) { connect(true) }, LinearLayout.LayoutParams(0, dp(64), 1f))
                addView(space(12), LinearLayout.LayoutParams(dp(12), 1))
                addView(button(getString(R.string.connect_with_usb), false, TEXT, icon = R.drawable.ic_dp_usb) { connect(false) }, LinearLayout.LayoutParams(0, dp(64), 1f))
            })
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val choices = row().apply { setPadding(0, dp(10), 0, dp(10)); isBaselineAligned = false }
        choices.addView(modeTile(R.drawable.ic_dp_hotspot, getString(R.string.built_in_car_hotspot), getString(R.string.hotspot_mode_manual_desc), mode == WirelessHotspotMode.MANUAL) {
            pendingCarHotspotSetup = true
            render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        choices.addView(space(12), LinearLayout.LayoutParams(dp(12), 1))
        choices.addView(modeTile(R.drawable.ic_dp_connection, getString(R.string.wifi_direct), getString(R.string.hotspot_mode_p2p_desc), mode == WirelessHotspotMode.WIFI_P2P) {
            pendingCarHotspotSetup = false
            applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(choices)
        if (mode == WirelessHotspotMode.MANUAL) {
            val saveDetails = {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }
            if (pendingCarHotspotSetup || storedSsid().isBlank()) {
                parent.addView(button(getString(R.string.save_hotspot_details_and_use_this_mode), true, icon = R.drawable.ic_dp_hotspot) { saveDetails() }, matchButton(4, 60))
            } else {
                parent.addView(valueRow(getString(R.string.hotspot_name), storedSsid(), R.drawable.ic_dp_hotspot) { saveDetails() }.first)
            }
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false, icon = R.drawable.ic_dp_settings) { openCarWifiSettings() }, matchButton(0, 60))
            if (carHotspotOff()) parent.addView(notice(getString(R.string.hotspot_details_off)).apply {
                background = GradientDrawable().apply { setColor(FILL); cornerRadius = dp(16).toFloat() }
            })
        } else {
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false, icon = R.drawable.ic_dp_settings) { openCarClientWifiSettings() }, matchButton(0, 60))
        }
    }

    /** Selectable picture card for a connection type; the chosen one is outlined and checked. */
    private fun modeTile(icon: Int, title: String, description: String, selected: Boolean, click: () -> Unit) = column().apply {
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(PRESSED), GradientDrawable().apply {
            setColor(FILL); cornerRadius = dp(18).toFloat()
            if (selected) setStroke(dp(2), ACCENT)
        }, null)
        // A shared minimum height keeps the two cards level when one description wraps.
        setPadding(dp(18), dp(16), dp(18), dp(18)); minimumHeight = dp(150)
        isClickable = true; isFocusable = true; isSelected = selected
        contentDescription = "$title. $description"
        setOnClickListener { click() }
        addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(space(1), LinearLayout.LayoutParams(0, 1, 1f))
            if (selected) addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(R.drawable.ic_dp_check); imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
        })
        addView(label(title, 18, TEXT, weight = 600).apply { setPadding(0, dp(12), 0, dp(4)) })
        addView(label(description, 14, MUTED))
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        lateinit var value: TextView
        val (line, view) = valueRow(getString(R.string.contrib_audio_home_media_channel_label), channelLabel(AirPlayPersistence.loadMediaAudioChannel(this)), R.drawable.ic_dp_audio) {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { selected -> applyMediaChannel(selected, current, value) },
            )
        }
        value = view
        parent.addView(line)
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        lateinit var value: TextView
        val (line, view) = valueRow(getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(AirPlayPersistence.loadNavigationAudioChannel(this)), R.drawable.ic_dp_navigation) {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { selected -> applyNavigationChannel(selected, current, value) },
            )
        }
        value = view
        parent.addView(line)
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(4), 0, dp(14))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val labels = (0..10).map(Int::toString).toTypedArray()
        var selection = current.coerceIn(0, 10)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: TextView) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = channelLabel(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: TextView) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = channelLabel(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        val dot = when {
            setupError != null -> RED
            CarPlayBackgroundSession.active -> GREEN
            running -> ACCENT
            else -> GRAY
        }
        status?.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(dot); setSize(dp(12), dp(12))
        }, null, null, null)
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        connectButton?.compoundDrawablesRelative?.get(1)?.setTint(if (setupError == null) Color.WHITE else MUTED)
    }
    private fun reportFileName() = "LoopLink-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("LoopLink ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("Saved renderer preference (may differ from active view): ${if (AirPlayPersistence.loadSurfaceViewEnabled(appContext)) "SurfaceView" else "TextureView"}")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = withIcon(getString(R.string.save_diagnostic_report), R.drawable.ic_dp_save, ACCENT) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) "Downloads/LoopLink/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            card.addView(valueRow(getString(R.string.language_app_language), AppLocale.displayName(this, current), R.drawable.ic_dp_language) { AppLocale.showPicker(this) }.first)
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(dp(4), dp(18), 0, dp(10)) }
        if (title.isEmpty()) heading.setPadding(0, dp(18), 0, 0)
        else if (icon != null) heading.addView(tile(icon, tileColor(icon), 30), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        if (title.isNotEmpty()) heading.addView(label(title, 20, TEXT, weight = 600), LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(heading)
        val card = card()
        build(card)
        groupRows(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    // iOS Settings tile colours, one per section.
    private fun tileColor(icon: Int) = when (icon) {
        R.drawable.ic_dp_connection, R.drawable.ic_dp_language -> Color.rgb(10, 132, 255)
        R.drawable.ic_dp_diagnostics -> Color.rgb(94, 92, 230)
        R.drawable.ic_dp_automation -> Color.rgb(191, 90, 242)
        R.drawable.ic_dp_display -> Color.rgb(100, 160, 255)
        R.drawable.ic_dp_navigation -> GREEN
        R.drawable.ic_dp_permissions -> Color.rgb(255, 159, 10)
        R.drawable.ic_dp_audio -> Color.rgb(255, 55, 95)
        else -> GRAY
    }

    // Plain buttons in a grouped card become full-width action rows; hairlines separate adjacent rows only,
    // so descriptive text reads as part of the cell above it.
    private fun groupRows(card: LinearLayout) {
        val children = (0 until card.childCount).map(card::getChildAt)
        card.removeAllViews()
        var previousRow = false
        for (child in children) {
            if (child is Button && child.tag != PRIMARY) actionRow(child)
            val isRow = child.tag == ROW
            if (isRow && previousRow) card.addView(separator())
            card.addView(child)
            previousRow = isRow
        }
    }

    private fun actionRow(button: Button) = button.apply {
        background = pressable(Color.TRANSPARENT, 12)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        typeface = font(400); textSize = 18f; setPadding(0, 0, 0, 0)
        minHeight = dp(60); minimumHeight = dp(60)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        tag = ROW
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(0, dp(14), 0, dp(14)); tag = ROW }
        val text = column(); text.addView(label(title, 18, TEXT)); text.addView(label(description, 14, MUTED).apply { setPaddingRelative(0, dp(4), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply {
            contentDescription = title; isChecked = value; minHeight = dp(56); background = null
            // iOS switch: white knob on a green (on) or grey (off) capsule.
            thumbDrawable = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(28), dp(28)) }, dp(2), dp(3), dp(2), dp(3))
            trackDrawable = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(17).toFloat(); setSize(dp(64), dp(34)) }
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, Color.rgb(57, 57, 61)))
            switchMinWidth = dp(64)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        })
        parent.addView(line)
    }

    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        lateinit var value: TextView
        val (line, view) = valueRow(title, options[selection]) {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        value.text = options[selection]
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        value = view
        parent.addView(line)
    }

    /** iOS disclosure row: title, current value and a chevron. Returns the row and its value label. */
    private fun valueRow(title: String, value: String, icon: Int? = null, click: () -> Unit): Pair<LinearLayout, TextView> {
        val valueView = label(value, 17, MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(380)
        }
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); tag = ROW
            background = pressable(Color.TRANSPARENT, 12); isClickable = true; isFocusable = true
            setOnClickListener { click() }
            if (icon != null) addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
            addView(label(title, 18, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(valueView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(R.drawable.ic_dp_chevron); imageTintList = ColorStateList.valueOf(TERTIARY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) })
        }
        return line to valueView
    }

    /** Inline notice: brand-tinted icon, plain readable text and an optional action. */
    private fun notice(message: String, actionTitle: String? = null, action: (() -> Unit)? = null) = column().apply {
        background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(16), dp(16), dp(if (actionTitle == null) 16 else 8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(row().apply {
            gravity = Gravity.TOP
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(R.drawable.ic_dp_notice); imageTintList = ColorStateList.valueOf(ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addView(label(message, 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (actionTitle != null && action != null) addView(label(actionTitle, 16, ACCENT, weight = 600).apply {
            minHeight = dp(48); setPaddingRelative(dp(36), 0, 0, 0)
            background = pressable(Color.TRANSPARENT, 8); isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun tile(icon: Int, color: Int, size: Int) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(size).toFloat() * .26f }
        val inset = (dp(size) * .2f).toInt(); setPadding(inset, inset, inset, inset)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun icon(resource: Int, color: Int, size: Int) = getDrawable(resource)!!.mutate().apply {
        setTint(color); setBounds(0, 0, dp(size), dp(size))
    }

    private fun separator(insetStart: Int = 0) = View(this).apply {
        setBackgroundColor(SEPARATOR)
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { marginStart = insetStart }
    }

    private fun card(radius: Int = 22) = column().apply {
        background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(radius).toFloat() }
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun font(weight: Int) = Typeface.create(Typeface.SANS_SERIF, weight, false)
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, weight: Int = if (bold) 600 else 400) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL or Gravity.START
        typeface = font(weight)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun largeTitle(value: String, size: Int) = label(value, size, TEXT, weight = 700).apply { letterSpacing = -.015f }
    private fun button(title: String, primary: Boolean, textColor: Int = if (primary) Color.WHITE else ACCENT, radius: Int = 18, icon: Int? = null, click: () -> Unit) = Button(this).apply {
        text = if (icon == null) title else withIcon(title, icon, textColor); isAllCaps = false
        if (icon != null) contentDescription = title; textSize = 18f; typeface = font(600)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            // A disabled primary button dims as a whole (alpha), so only its text stays at full strength.
            intArrayOf(if (primary) MUTED else Color.argb(100, Color.red(textColor), Color.green(textColor), Color.blue(textColor)), textColor)))
        background = if (primary) android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(PRESSED), GradientDrawable().apply {
            // Disabled reads as unavailable (neutral grey), not as a faded brand colour.
            color = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(FILL, ACCENT))
            cornerRadius = dp(radius).toFloat()
        }, null) else pressable(FILL, radius)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        if (primary) tag = PRIMARY
        setOnClickListener { click() }
    }
    // The icon rides in the text, so it stays beside the label in both centred buttons and start-aligned rows.
    private fun withIcon(title: String, icon: Int, color: Int) = android.text.SpannableString("\u0000  $title").apply {
        setSpan(CenteredIcon(icon(icon, color, 22)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private class CenteredIcon(drawable: android.graphics.drawable.Drawable) : android.text.style.ImageSpan(drawable) {
        override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            val icon = drawable
            canvas.save()
            // Centre on the glyphs around the baseline, not the line box (which includes extra line spacing).
            val metrics = paint.fontMetrics
            canvas.translate(x, y + (metrics.ascent + metrics.descent) / 2f - icon.bounds.height() / 2f)
            icon.draw(canvas)
            canvas.restore()
        }
    }

    private fun pressable(color: Int, radius: Int) = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(PRESSED),
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }, null)
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 64) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        // iOS dark-mode system colours, tinted with the LoopLink orange.
        private val BG = Color.BLACK
        private val SURFACE = Color.rgb(28, 28, 30)
        private val FILL = Color.rgb(44, 44, 46)
        private val SEPARATOR = Color.rgb(56, 56, 58)
        private val BORDER = SEPARATOR
        private val ACCENT = Color.rgb(244, 122, 58)
        private val TEXT = Color.WHITE
        private val MUTED = Color.rgb(152, 152, 159)
        private val TERTIARY = Color.rgb(99, 99, 102)
        private val GRAY = Color.rgb(142, 142, 147)
        private val GREEN = Color.rgb(48, 209, 88)
        private val RED = Color.rgb(255, 69, 58)
        // Soft peach: noticeable, but in the same family as the orange tint.
        private val WARNING = Color.rgb(255, 184, 140)
        private const val PRESSED = 0x33FFFFFF
        private const val ROW = "row"
        private const val PRIMARY = "primary"
    }
}
