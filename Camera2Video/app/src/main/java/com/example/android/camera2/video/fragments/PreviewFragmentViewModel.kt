package com.example.android.camera2.video.fragments

import android.text.BoringLayout
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import tp.xmaihh.serialport.SerialHelper
import tp.xmaihh.serialport.bean.ComBean
import java.io.IOException


const val INTENSITY_STEP = 3

class PreviewFragmentViewModel : ViewModel() {

    private val _intensityValue = MutableLiveData(50)
    val intensityValue : LiveData<Int>
        get() = _intensityValue

    private val _alphaValue = MutableLiveData(0.5f)
    val alphaValue : LiveData<Float>
        get() = _alphaValue

    var lastSavedJpeg : String = ""
    init {
        connectISP()
    }

    private fun setPWMValue() {
        val pwmvalue = 25000 - (_intensityValue.value?.times(250) ?: 0)
        val cmdtext = "echo $pwmvalue > /sys/pwm/firefly_pwm"
        Log.d("CHISATO", cmdtext)
        Shell.cmd(cmdtext).exec()
    }

    fun setIntensity(intensity: Int) {
        _intensityValue.value = intensity
        setPWMValue()
    }

    fun increaseIntensity() {
        _intensityValue.value = _intensityValue.value?.plus(INTENSITY_STEP)?.let { min(it, 100) }
        setPWMValue()
    }

    fun decreaseIntensity() {
        _intensityValue.value = _intensityValue.value?.minus(INTENSITY_STEP)?.let { max(it, 0) }
        setPWMValue()
    }

    fun connectISP() {
        viewModelScope.launch {
            try {
                SerialManager.openSerial()
            } catch (e: IOException) {
                e.message?.let { Log.d("CHISATO", it) }
            }
        }
    }

    fun setMirrorState(state: Boolean) {
        viewModelScope.launch {
            SerialManager.mirror(state)
        }
    }

    fun setPatternLed(state: Boolean) {
        viewModelScope.launch {
            if (state)
                SerialManager.setLEDduty(255)
            else
                SerialManager.setLEDduty(0)
        }
    }

}

object SerialManager {
    private var serialHelper = object : SerialHelper("/dev/ttyS1", 1152000) {
        override fun onDataReceived(comBean: ComBean) {
            Log.d("CHISATO", String(comBean.bRec))
        }
    }

    suspend fun openSerial() {
        if (!serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                serialHelper.open()
                serialHelper.sendTxt("AC AE_TARGET 15000")
                serialHelper.sendHex("A")
                serialHelper.sendTxt("AC BRIGHTNESS 50")
                serialHelper.sendHex("A")
            }
        }
    }

    suspend fun setAEtarget(aetarget: Int) {
        Log.d("CHISATO", "AC AE_TARGET ${aetarget}")
        if (serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                serialHelper.sendTxt("AC AE_TARGET ${aetarget}")
                serialHelper.sendHex("A")
            }
        }
    }

    suspend fun mirror(state: Boolean) {
        var cmdstr = if (state) "AC MIRROR 1" else "AC MIRROR 0"
        Log.d("CHISATO", cmdstr)
        if (serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                serialHelper.sendTxt(cmdstr)
                serialHelper.sendHex("A")
            }
        }
    }

    suspend fun setLEDduty(pwmvalue: Int) {
        Log.d("CHISATO", "AC LED_DUTY $pwmvalue")
        if (serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                serialHelper.sendTxt("AC LED_CHANNEL 1")
                serialHelper.sendHex("A")
                serialHelper.sendTxt("AC LED_ON 0")
                serialHelper.sendHex("A")
                serialHelper.sendTxt("AC LED_DUTY $pwmvalue")
                serialHelper.sendHex("A")
            }
        }
    }
}