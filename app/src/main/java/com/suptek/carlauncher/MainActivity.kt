package com.suptek.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var resumed = false
    private var mc: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var viz: Visualizer? = null
    private var gpsL: LocationListener? = null
    private var lastKey = ""
    private var coverOk = false
    private var bt = ""
    private var ba = ""
    private var bp = false
    private val prefs get() = getSharedPreferences("l", MODE_PRIVATE)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.BLACK)
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Car")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
        val f = IntentFilter()
        for (a in listOf("com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.playbackcomplete",
            "com.miui.player.metachanged", "com.htc.music.metachanged", "com.sec.android.app.music.metachanged",
            "com.sec.android.app.music.playstatechanged", "fm.last.android.metachanged")) f.addAction(a)
        registerReceiver(rcv, f)
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        web.onResume()
        startGps(); setViz(true); startMedia()
        lastKey = ""; coverOk = false
        push(); pushBc()
        ui.removeCallbacks(poll); ui.post(poll)
    }

    override fun onPause() {
        resumed = false
        web.onPause(); stopGps(); setViz(false); ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); js("goHome()", true) }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { startGps(); setViz(true) }
    override fun onBackPressed() { js("goHome()", true) }
    override fun onDestroy() { try { unregisterReceiver(rcv); viz?.release() } catch (_: Exception) {}; super.onDestroy() }

    private fun js(c: String, force: Boolean = false) { if (resumed || force) runOnUiThread { web.evaluateJavascript(c, null) } }
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /* ---------- GPS ---------- */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsL != null || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val l = object : LocationListener {
            override fun onLocationChanged(l: Location) { js("onGps(${l.speed * 3.6f},${if (l.hasBearing()) l.bearing else -1f})") }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l); gpsL = l } catch (_: Exception) {}
    }

    private fun stopGps() {
        try { gpsL?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } } catch (_: Exception) {}
        gpsL = null
    }

    /* ---------- волна под бит ---------- */
    private fun setViz(on: Boolean) {
        try {
            if (on && viz == null && granted(Manifest.permission.RECORD_AUDIO)) {
                val v = Visualizer(0)
                v.setEnabled(false)
                v.setCaptureSize(256)
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(x: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(x: Visualizer?, f: ByteArray?, r: Int) {
                        if (f == null) return
                        val sb = StringBuilder("[")
                        for (k in 0 until f.size / 2) {
                            val re = if (k == 0) f[0].toDouble() else f[2 * k].toDouble()
                            val im = if (k == 0) 0.0 else f[2 * k + 1].toDouble()
                            if (k > 0) sb.append(',')
                            sb.append(Math.min(255.0, Math.hypot(re, im) * 2.0).toInt())
                        }
                        js("onFft(" + sb.append(']') + ")")
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                viz = v
            }
            viz?.setEnabled(on)
        } catch (_: Exception) {}
    }

    /* ---------- музыка: 3 источника ---------- */
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(s: PlaybackState?) = push()
    }
    private val sl = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private fun startMedia() {
        if (msm != null) return
        try {
            val cn = ComponentName(this, MediaService::class.java)
            val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            m.addOnActiveSessionsChangedListener(sl, cn)
            msm = m
            pick(m.getActiveSessions(cn))
        } catch (_: Exception) {}
    }

    private fun pick(l: List<MediaController>?) {
        try { mc?.unregisterCallback(cb) } catch (_: Exception) {}
        mc = l?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l?.firstOrNull()
        mc?.registerCallback(cb)
        lastKey = ""; coverOk = false
        push()
    }

    private fun b64(b: Bitmap, fmt: Bitmap.CompressFormat, q: Int): String {
        val o = ByteArrayOutputStream(); b.compress(fmt, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    private fun push() {
        val c = mc ?: return
        val m = c.metadata
        val s = c.playbackState
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val a = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val o = JSONObject().put("t", t).put("a", a).put("d", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .put("p", s?.position ?: 0L).put("pl", s?.state == PlaybackState.STATE_PLAYING)
        val isNew = (t + "|" + a) != lastKey
        if (isNew) { lastKey = t + "|" + a; coverOk = false }
        if (isNew || !coverOk) {
            val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) {
                coverOk = true
                val sc = if (bmp.width > 400) Bitmap.createScaledBitmap(bmp, 400, 400 * bmp.height / bmp.width, true) else bmp
                o.put("c", "data:image/jpeg;base64," + b64(sc, Bitmap.CompressFormat.JPEG, 85))
                if (sc !== bmp) sc.recycle()
            } else if (isNew) o.put("c", "")
        }
        js("onMedia($o)")
    }

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            val t = i.getStringExtra("track") ?: i.getStringExtra("title")
            if (t != null) { bt = t; ba = i.getStringExtra("artist") ?: "" }
            if (i.hasExtra("playing")) bp = i.getBooleanExtra("playing", false)
            else if (i.action?.endsWith("playbackcomplete") == true) bp = false
            else if (t != null) bp = true
            if (mc == null) pushBc()
        }
    }

    private fun pushBc() {
        if (bt.isEmpty() || mc != null) return
        js("onMedia(" + JSONObject().put("t", bt).put("a", ba).put("pl", bp).put("d", 0).put("p", 0).put("c", "") + ")")
    }

    private val poll = object : Runnable {
        override fun run() {
            if (mc == null) js("onPlay(" + (getSystemService(AUDIO_SERVICE) as AudioManager).isMusicActive + ")")
            ui.postDelayed(this, 1500)
        }
    }

    private fun hasAccess() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun openAccess() {
        val l = listOf(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity"))
        for (i in l) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {} }
        Toast.makeText(this, "В этой прошивке нет экрана «Доступ к уведомлениям»", Toast.LENGTH_LONG).show()
    }

    /* ---------- кнопки-ярлыки ---------- */
    private fun startSaved(s: String): Boolean = try {
        val i = if (s.contains("/")) Intent().setClassName(s.substringBefore("/"), s.substringAfter("/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else packageManager.getLaunchIntentForPackage(s)!!
        startActivity(i); true
    } catch (e: Exception) { false }

    private fun findBt(): String? {
        val pm = packageManager
        val keys = listOf("bluetooth", "блютуз", "btphone", "btcall", "bt_", "bluesoleil", "handsfree")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .firstOrNull { (p, n) -> p != packageName && !p.startsWith("com.android.settings") && keys.any { k -> (p + " " + n).lowercase().contains(k) } }?.first
    }

    private fun openKey(n: String) {
        val s = prefs.getString(n, null) ?: when (n) { "bt" -> findBt(); "nav" -> "ru.yandex.yandexnavi"; "mus" -> "ru.yandex.music"; else -> null }
        if (s == null || !startSaved(s)) showPicker(n, true)
    }

    private fun showPicker(k: String, launch: Boolean) = runOnUiThread {
        val pm = packageManager
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val names = listOf("Авто (по умолчанию)") + l.map { it.loadLabel(pm).toString() + "  (" + it.activityInfo.packageName + ")" }
        AlertDialog.Builder(this).setTitle("Что открывать по этой кнопке?")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) { prefs.edit().remove(k).apply(); js("lbl()", true) }
                else if (k == "bt") pickAct(l[i - 1].activityInfo.packageName, launch)
                else { prefs.edit().putString(k, l[i - 1].activityInfo.packageName).apply(); js("lbl()", true); if (launch) openKey(k) }
            }.show()
    }

    private fun pickAct(pkg: String, launch: Boolean) {
        val acts: List<ActivityInfo> = try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities?.filter { it.exported && it.enabled } ?: listOf()
        } catch (e: Exception) { listOf() }
        val names = listOf("Главный экран приложения") + acts.map { it.name.substringAfterLast('.') }
        AlertDialog.Builder(this).setTitle("Какой экран открывать?")
            .setItems(names.toTypedArray()) { _, i ->
                prefs.edit().putString("bt", if (i == 0) pkg else pkg + "/" + acts[i - 1].name).apply()
                js("lbl()", true)
                if (launch) openKey("bt")
            }.show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val arr = JSONArray()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach { r ->
                    val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                    val d = r.loadIcon(pm)
                    d.setBounds(0, 0, 72, 72); d.draw(Canvas(bmp))
                    arr.put(JSONObject().put("n", r.loadLabel(pm).toString()).put("p", r.activityInfo.packageName).put("i", b64(bmp, Bitmap.CompressFormat.PNG, 100)))
                    bmp.recycle()
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun open(s: String) = runOnUiThread {
            if (s.startsWith("k:")) { openKey(s.substring(2)); return@runOnUiThread }
            try {
                if (s.startsWith("p:")) startActivity(packageManager.getLaunchIntentForPackage(s.substring(2))!!)
                else startActivity(Intent(s.substring(2)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Не удалось открыть — приложение не установлено", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun ctl(c: String) {
            val k = when (c) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k))
        }

        @JavascriptInterface
        fun seek(ms: Long) = runOnUiThread { mc?.transportControls?.seekTo(ms) }

        @JavascriptInterface
        fun get(k: String): String = prefs.getString(k, "") ?: ""

        @JavascriptInterface
        fun set(k: String, v: String) { prefs.edit().putString(k, v).apply() }

        @JavascriptInterface
        fun label(k: String): String {
            val s = prefs.getString(k, null) ?: return "авто"
            return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.substringBefore("/"), 0)).toString() } catch (e: Exception) { s }
        }

        @JavascriptInterface
        fun pick(k: String, go: Boolean) = showPicker(k, go)

        @JavascriptInterface
        fun hasAccess(): Boolean = this@MainActivity.hasAccess()

        @JavascriptInterface
        fun access() = runOnUiThread { openAccess() }
    }
}
