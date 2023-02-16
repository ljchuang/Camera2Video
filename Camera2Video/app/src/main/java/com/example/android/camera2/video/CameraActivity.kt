/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.android.camera2.video.databinding.ActivityCameraBinding
import com.example.android.camera2.video.fragments.PreviewFragment
import com.lzf.easyfloat.interfaces.OnTouchRangeListener
import com.lzf.easyfloat.permission.PermissionUtils
import com.lzf.easyfloat.utils.DragUtils
import com.lzf.easyfloat.widget.BaseSwitchView
import org.greenrobot.eventbus.EventBus

class CameraActivity : AppCompatActivity() {

    private lateinit var activityCameraBinding: ActivityCameraBinding
    data class NewEvent(val keyCode: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCameraBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(activityCameraBinding.root)
    }

    override fun onResume() {
        super.onResume()
        // A function to hide NavigationBar
        hideSystemUI()
    }

    // Function to hide NavigationBar
    //@RequiresApi(Build.VERSION_CODES.R)
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window,
            window.decorView.findViewById(android.R.id.content)).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // When the screen is swiped up at the bottom
            // of the application, the navigationBar shall
            // appear for some time
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 1. onKeyDown is a boolean function, which returns the state of the KeyEvent.
    // 2. This function is an internal function, that functions outside the actual application.
    // 3. When the any Key is pressed, a To ast appears with the following message.
    // 4. This code can be used to check if the device responds to any Key.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        /*
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> Toast.makeText(applicationContext, "Volume Down Key Pressed", Toast.LENGTH_SHORT).show()
            KeyEvent.KEYCODE_VOLUME_UP -> Toast.makeText(applicationContext, "Volume Up Key Pressed", Toast.LENGTH_SHORT).show()
            KeyEvent.KEYCODE_BACK -> Toast.makeText(applicationContext, "Back Key Pressed", Toast.LENGTH_SHORT).show()
        }
         */

        EventBus.getDefault().post(PreviewFragment.Event(keyCode))
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        /*
        DragUtils.registerSwipeAdd(ev, object : OnTouchRangeListener {
            override fun touchInRange(inRange: Boolean, view: BaseSwitchView) {
                /*
                view.findViewById<ImageView>(com.lzf.easyfloat.R.id.iv_add)
                    .setImageResource(
                        if (inRange) com.lzf.easyfloat.R.drawable.add_selected else com.lzf.easyfloat.R.drawable.add_normal
                    )
                 */
            }

            override fun touchUpInRange() {
                Log.d("CHISATO", "touchUpInRange")
                EventBus.getDefault().post(PreviewFragment.Event(KeyEvent.KEYCODE_F12))
            }
        }, start = 0.04f, end = 0.12f, slideOffset = -1f)
         */
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L
    }
}
