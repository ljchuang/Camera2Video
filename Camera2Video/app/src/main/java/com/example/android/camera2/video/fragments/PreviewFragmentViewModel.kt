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

    private val serialManager : SerialManager = SerialManager()

    private val _intensityValue = MutableLiveData(50)
    val intensityValue : LiveData<Int>
        get() = _intensityValue

    private val _alphaValue = MutableLiveData(0.5f)
    val alphaValue : LiveData<Float>
        get() = _alphaValue

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
                serialManager.openSerial()
            } catch (e: IOException) {
                e.message?.let { Log.d("CHISATO", it) }
            }
            serialManager.issueCmd()
        }
    }
}

class SerialManager {
    private var _serialHelper = object : SerialHelper("/dev/ttyS1", 1152000) {
        override fun onDataReceived(comBean: ComBean) {
        }
    }

    suspend fun openSerial() {
        if (!_serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                _serialHelper.open()
            }
        }
    }
    suspend fun issueCmd() {
        Log.d("CHISATO", "AC MIRROR 255")
        if (_serialHelper.isOpen) {
            return withContext(Dispatchers.IO) {
                _serialHelper.sendTxt("AC MIRROR 255")
                _serialHelper.sendHex("A")
            }
        }
    }
}