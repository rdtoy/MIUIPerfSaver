package com.rdstory.miuiperfsaver.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.rdstory.miuiperfsaver.ConfigProvider
import com.rdstory.miuiperfsaver.ConfigProvider.Companion.COLUMN_DC_BRIGHTNESS
import com.rdstory.miuiperfsaver.ConfigProvider.Companion.COLUMN_DC_FPS_LIMIT
import com.rdstory.miuiperfsaver.ConfigProvider.Companion.DC_COMPAT_CONFIG_URI
import com.rdstory.miuiperfsaver.Constants.FPS_COOKIE_DEFAULT
import com.rdstory.miuiperfsaver.Constants.FPS_COOKIE_EXCLUDE
import com.rdstory.miuiperfsaver.Constants.LOG_LEVEL
import com.rdstory.miuiperfsaver.Constants.LOG_TAG
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

object DCFPSCompat {

    interface Callback {
        fun setFpsLimit(fpsLimit: Int?)
    }

    private const val ACTION_DELAY = 500L
    private var displayFeatureMan: Any? = null
    private var setMethodInternalIIS: Method? = null
    private var setMethodInternalII: Method? = null
    private var setMethodII: Method? = null
    private val updateHandler = Handler(Looper.getMainLooper())
    private var shouldLimitFps: Boolean? = null
    private var dcEnabled: Boolean? = null
    private lateinit var callback: Callback
    private lateinit var frameSettingObject: Any
    private var dcFpsLimit = 0
    private var dcBrightness = 0
    private var briFpsArray = arrayOf(intArrayOf(2047, 120), intArrayOf(200, 90,))

    fun init(context: Context, frameSettingObject: Any, callback: Callback) {
        if (!isDcFpsInCompat(context)) {
            return
        }
        this.frameSettingObject = frameSettingObject
        this.callback = callback
        val updateConfig = fun() {
            val config = ConfigProvider.getDCCompatConfig(context) ?: emptyMap()
            XposedBridge.log("[$LOG_TAG] dc compat config updated. $config")
            val fpsLimit = config[COLUMN_DC_FPS_LIMIT] ?: 0
            val brightness = config[COLUMN_DC_BRIGHTNESS] ?: 0
            dcFpsLimit = fpsLimit
            dcBrightness = brightness
            checkShouldLimitFps(context, true)
        }
        ConfigProvider.observeChange(context, DC_COMPAT_CONFIG_URI, updateConfig)
        initReflections(context)
        updateConfig()
        startObserving(context)
    }

    private fun isDcFpsInCompat(context: Context): Boolean {
        return try {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("miui.util.FeatureParser", context.classLoader),
                "getBoolean",
                "dc_backlight_fps_incompatible",
                false
            ) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    private fun setHardwareDcEnabled(enable: Boolean) {
        displayFeatureMan?.callMethod<Unit>("setScreenEffect", 20, if (enable) 1 else 0)
    }

    private fun initReflections(context: Context) {
        val classDisplayFrameSetting = frameSettingObject::class.java
        setMethodInternalIIS = XposedHelpers.findMethodExactIfExists(
            classDisplayFrameSetting,
            "setScreenEffectInternal",
            Int::class.java, Int::class.java, String::class.java
        )
        setMethodInternalII = XposedHelpers.findMethodExactIfExists(
            classDisplayFrameSetting,
            "setScreenEffectInternal",
            Int::class.java, Int::class.java
        )
        setMethodII = XposedHelpers.findMethodExactIfExists(
            classDisplayFrameSetting,
            "setScreenEffect",
            Int::class.java, Int::class.java
        )
        displayFeatureMan = XposedHelpers.findClassIfExists(
            "miui.hardware.display.DisplayFeatureManager",
            context.classLoader
        )?.callStaticMethod("getInstance")
    }

    private fun startObserving(context: Context) {
        val observerHandler = Handler(Looper.getMainLooper())
        val dcURI = Settings.System.getUriFor("dc_back_light")
        context.contentResolver.registerContentObserver(dcURI, false,
            object : ContentObserver(observerHandler) {
                override fun onChange(selfChange: Boolean) {
                    checkShouldLimitFps(context)
                }
            })
        val brightnessURI = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
        context.contentResolver.registerContentObserver(brightnessURI, false,
            object : ContentObserver(observerHandler) {
                override fun onChange(selfChange: Boolean) {
                    checkShouldLimitFps(context)
                }
            })
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                observerHandler.postDelayed({ checkShouldLimitFps(context, true) }, 500)
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    private fun updateFpsLimit(retry: Int = 10) {
        if (Log.isLoggable(LOG_TAG, LOG_LEVEL)) {
            XposedBridge.log("[$LOG_TAG] updateFpsLimit. " +
                    "shouldLimit=$shouldLimitFps, limit=$dcFpsLimit")
        }
        updateHandler.removeCallbacksAndMessages(null)
        if (shouldLimitFps == true) {
            callback.setFpsLimit(60)
            updateCurrentFps()
            setHardwareDcEnabled(true)
            if (dcFpsLimit > 60) {
                updateHandler.postDelayed({
                    callback.setFpsLimit(dcFpsLimit)
                    if (!updateCurrentFps() && retry > 0) {
                        updateHandler.postDelayed({ updateFpsLimit(retry - 1) }, ACTION_DELAY)
                    }
                    setHardwareDcEnabled(true)
                }, ACTION_DELAY)
            }
        } else if (shouldLimitFps == false) {
            callback.setFpsLimit(null)
            updateCurrentFps()
            dcEnabled?.let { updateHandler.postDelayed({ setHardwareDcEnabled(it) }, ACTION_DELAY) }
        }
    }

    private fun checkShouldLimitFps(context: Context, forceUpdate: Boolean = false) {
        val dcEnabled = Settings.System.getInt(context.contentResolver, "dc_back_light", 0) == 1
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        this.dcEnabled = dcEnabled
        val wasLimit = shouldLimitFps
        val prevFpsLimit = dcFpsLimit
        briFpsArray.sortedWith { a, b -> b[0] - a[0] }.forEach {
            val bri = it[0]
            val fps = it[1]
            if (brightness <= bri) {
                dcFpsLimit = fps
                dcBrightness = bri
            }
        }
        shouldLimitFps = dcEnabled && brightness <= dcBrightness && dcFpsLimit > 0
        if (wasLimit != shouldLimitFps || forceUpdate || prevFpsLimit != dcFpsLimit) {
            updateFpsLimit()
        }
        if (Log.isLoggable(LOG_TAG, LOG_LEVEL)) {
            XposedBridge.log("[$LOG_TAG] checkShouldLimitFps. dcEnabled=$dcEnabled, " +
                    "brightness=$brightness, dcBrightness=$dcBrightness, " +
                    "dcFpsLimit=$dcFpsLimit, forceUpdate=$forceUpdate, " +
                    "wasLimit=$wasLimit, shouldLimitFps=$shouldLimitFps")
        }
    }

    private fun updateCurrentFps(): Boolean {
        val fgResult = frameSettingObject.getObjectField<Any>("mCurrentFgInfo")?.let {
            frameSettingObject.callMethod<Unit>("onForegroundChanged", it)
            true
        } ?: false
        frameSettingObject.getObjectField<Int>("mCurrentFps")?.let {
            return setFps(it) && fgResult
        }
        return false
    }

    private fun setFps(fps: Int): Boolean {
        val curCookie = frameSettingObject.getObjectField<Int>("mCurrentCookie")
        val cookie = if (curCookie == FPS_COOKIE_EXCLUDE) FPS_COOKIE_DEFAULT else FPS_COOKIE_EXCLUDE
        setMethodInternalIIS?.let { method ->
            frameSettingObject.getObjectField<Any>("mCurrentFgPkg")?.let {
                method.invoke(frameSettingObject, fps, cookie, it)
                return true
            }
            return false
        }
        (setMethodInternalII ?: setMethodII)?.let { method ->
            method.invoke(fps, cookie)
            return true
        }
        return false
    }
}