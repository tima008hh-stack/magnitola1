package com.suptek.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
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
    private var mc: MediaController? = null
    private var viz: Visualizer? = null
    private var gpsOn = false
    private var asked = false
    private var lastKey = ""

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
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onResume() { super.onResume(); startGps(); startViz(); startMedia() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); js("pg=0;snap()") }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { startGps(); startViz() }
    override fun onBackPressed() {}
    override fun onDestroy() { try { viz?.release() } catch (_: Exception) {}; super.onDestroy() }

    private fun js(c: String) = runOnUiThread { web.evaluateJavascript(c, null) }
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        js("onGps(${l.speed * 3.6f},${if (l.hasBearing()) l.bearing else -1f})")
                    }
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                })
            gpsOn = true
        } catch (_: Exception) {}
    }

    private fun startViz() {
        if (viz != null || !granted(Manifest.permission.RECORD_AUDIO)) return
        try {
            val v = Visualizer(0)
            v.setEnabled(false)
            v.setCaptureSize(256)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, r: Int) {
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
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.setEnabled(true)
            viz = v
        } catch (_: Exception) {}
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(s: PlaybackState?) = push()
    }

    private fun startMedia() {
        try {
            val cn = ComponentName(this, MediaService::class.java)
            val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            m.addOnActiveSessionsChangedListener({ l -> pick(l) }, cn)
            pick(m.getActiveSessions(cn))
        } catch (e: SecurityException) {
            if (!asked) {
                asked = true
                Toast.makeText(this, "Разреши «Магнитола» доступ к уведомлениям — так появятся обложки", Toast.LENGTH_LONG).show()
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun pick(l: List<MediaController>?) {
        try { mc?.unregisterCallback(cb) } catch (_: Exception) {}
        mc = l?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l?.firstOrNull()
        mc?.registerCallback(cb)
        lastKey = ""
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
        val o = JSONObject()
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val a = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        o.put("t", t); o.put("a", a)
        o.put("d", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        o.put("p", s?.position ?: 0L)
        o.put("pl", s?.state == PlaybackState.STATE_PLAYING)
        if (t + a != lastKey) {
            lastKey = t + a
            val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val sc = if (bmp != null && bmp.width > 400) Bitmap.createScaledBitmap(bmp, 400, 400 * bmp.height / bmp.width, true) else bmp
            o.put("c", if (sc != null) "data:image/jpeg;base64," + b64(sc, Bitmap.CompressFormat.JPEG, 85) else "")
        }
        js("onMedia($o)")
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
                    val d = r.loadIcon(pm)
                    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 96, 96); d.draw(Canvas(bmp))
                    arr.put(JSONObject().put("n", r.loadLabel(pm).toString()).put("p", r.activityInfo.packageName)
                        .put("i", b64(bmp, Bitmap.CompressFormat.PNG, 100)))
                }
            return arr.toString()
        }

        @JavascriptInterface
        fun open(s: String) = runOnUiThread {
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
    }
}
