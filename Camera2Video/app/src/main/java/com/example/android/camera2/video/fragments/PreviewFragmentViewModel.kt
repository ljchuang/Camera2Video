package com.example.android.camera2.video.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.Image
import android.text.BoringLayout
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.example.android.camera2.video.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import tp.xmaihh.serialport.SerialHelper
import tp.xmaihh.serialport.bean.ComBean
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

const val INTENSITY_STEP = 3

class PreviewFragmentViewModel : ViewModel() {

    private val _intensityValue = MutableLiveData(50)
    val intensityValue : LiveData<Int>
        get() = _intensityValue

    private val _alphaValue = MutableLiveData(0.5f)
    val alphaValue : LiveData<Float>
        get() = _alphaValue

    private val _bitmap = MutableLiveData<Bitmap>()
    val bitmap : LiveData<Bitmap>
        get() = _bitmap

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
        Log.d("CHISATO", "setPatternLed " + state.toString())
        viewModelScope.launch {
            if (state)
                SerialManager.setLEDduty(255)
            else
                SerialManager.setLEDduty(0)
        }
    }

    fun loadJpegAndAddWatermark(context: Context) {
        val filepath = "MyFileStorage"
        val file = File(lastSavedJpeg)
        val inputStream: InputStream = FileInputStream(file)
        lateinit var originalBitmap : Bitmap

        inputStream.use {
            originalBitmap = BitmapFactory.decodeStream(inputStream)
        }
        val watermarkBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.newjeans)

        val resultBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, originalBitmap.config)
        val canvas = Canvas(resultBitmap)
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)

        val scaleFactor = 0.5f //調整浮水印大小的因素
        val scaledWidth = watermarkBitmap.width * scaleFactor
        val scaledHeight = watermarkBitmap.height * scaleFactor
        val scaledWatermark = Bitmap.createScaledBitmap(watermarkBitmap, scaledWidth.toInt(), scaledHeight.toInt(), false)

        val x = (originalBitmap.width - scaledWatermark.width).toFloat()
        val y = (originalBitmap.height - scaledWatermark.height).toFloat()
        val paint = Paint()
        paint.alpha = 100 //設置浮水印透明度
        canvas.drawBitmap(scaledWatermark, x, y, null)

        _bitmap.value = resultBitmap
    }

    fun clearBitmap() {
        _bitmap.value = null
    }

    fun saveToZip(context: Context, imageFileName: String): File {
        // 設定壓縮檔案的名稱和路徑
        //val zipFileName = "archive.zip"

        fun createFile(context: Context, extension: String): File {
            val filepath = "MyFileStorage"
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.getExternalFilesDir(filepath), "VID_${sdf.format(Date())}.$extension")
        }

        val zipFileName = createFile(context, "zip")

        val fos = FileOutputStream(zipFileName)
        val zos = ZipOutputStream(fos)

        // 加入要壓縮的檔案
        val fileNames = arrayOf(imageFileName)
        for (fileName in fileNames) {
            // 新增進 ZipEntry
            val entryName = fileName.substring(fileName.lastIndexOf("/")+1)
            Log.d("CHISATO", "Entry name: $entryName")

            zos.putNextEntry(ZipEntry(entryName))

            // 寫入檔案內容
            val fis = FileInputStream(fileName)
            val buffer = ByteArray(1024)
            var len: Int
            while (fis.read(buffer).also { len = it } > 0) {
                zos.write(buffer, 0, len)
            }
            fis.close()

            // 結束 ZipEntry
            zos.closeEntry()
        }

        // 關閉 ZipOutputStream
        zos.close()
        return zipFileName
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