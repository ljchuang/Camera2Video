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

package com.example.android.camera2.video.fragments

//import android.graphics.Color
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.media.*
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.*
import android_serialport_api.SerialPortFinder
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ContentScale.Companion.Fit
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.video.*
import com.example.android.camera2.video.R
import com.example.android.camera2.video.databinding.FragmentPreviewBinding
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.SidePattern
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Runnable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class PreviewFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentBinding: FragmentPreviewBinding? = null

    private val fragmentBinding get() = _fragmentBinding!!

    private val myViewModel: PreviewFragmentViewModel by viewModels<PreviewFragmentViewModel>()

    private val pipeline: Pipeline by lazy {
        if (args.useHardware) {
            HardwarePipeline(args.width, args.height, args.fps, args.filterOn,
                    characteristics, encoder, fragmentBinding.viewFinder)
        } else {
            SoftwarePipeline(args.width, args.height, args.fps, args.filterOn,
                    characteristics, encoder, fragmentBinding.viewFinder)
        }
    }

    /** AndroidX navigation arguments */
    private val args: PreviewFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /**
     * Setup a [Surface] for the encoder
     */
    private val encoderSurface: Surface by lazy {
        encoder.getInputSurface()
    }

    /** [EncoderWrapper] utility class */
    private val encoder: EncoderWrapper by lazy { createEncoder() }

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
             fragmentBinding.overlay.foreground = android.graphics.Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentBinding.overlay.postDelayed({
                if (isCurrentlyRecording()) {
                    // Remove white flash animation
                    fragmentBinding.overlay.foreground = null
                    // Restart animation recursively
                    if (isCurrentlyRecording()) {
                        fragmentBinding.overlay.postDelayed(animationTask,
                                CameraActivity.ANIMATION_FAST_MILLIS)
                    }
                }
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest? by lazy {
        pipeline.createPreviewRequest(session, args.previewStabilization)
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        pipeline.createRecordRequest(session, args.previewStabilization)
    }

    private var recordingStartMillis: Long = 0L

    /** Orientation of the camera as 0, 90, 180, or 270 degrees */
    private val orientation: Int by lazy {
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
    }

    @Volatile
    private var recordingStarted = false

    @Volatile
    private var recordingComplete = false

    /** Condition variable for blocking until the recording completes */
    private val cvRecordingStarted = ConditionVariable(false)
    private val cvRecordingComplete = ConditionVariable(false)

    data class Event(val keyCode: Int)

    data class CallbackListDataItem(val text: String) {
    }

    private lateinit var composeView: ComposeView

    private lateinit var mySerialPortFinder: SerialPortFinder

    private var myTimer = object: CountDownTimer(5000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            Log.d( TAG, "seconds remaining: " + millisUntilFinished / 1000)
        }

        override fun onFinish() {
            Log.d(TAG, "done!")
            fragmentBinding.DemoSlider.visibility = View.INVISIBLE
        }
    }.start()


    @Subscribe
    fun onKeyEvent(event: Event) {
        // Called by eventBus when an event occurs
        Log.d(TAG, "${event.keyCode}")

        when (event.keyCode) {
            //KeyEvent.KEYCODE_VOLUME_DOWN -> intensityValue -= 10
            KeyEvent.KEYCODE_VOLUME_DOWN -> myViewModel.decreaseIntensity()
            //KeyEvent.KEYCODE_VOLUME_UP -> intensityValue += 10
            KeyEvent.KEYCODE_VOLUME_UP -> myViewModel.increaseIntensity()
            //KeyEvent.KEYCODE_F12 ->
        }

        //intensityValue = min(intensityValue, 100)
        //intensityValue = max(intensityValue, 0)

        //fragmentBinding.DemoSlider.visibility = View.VISIBLE
        //fragmentBinding.DemoSlider.setProgress(intensityValue)

        //myTimer.cancel()
        //myTimer.start()

        /*
        val pwmvalue = 25000 - intensityValue * 250
        val cmdtext = "echo " + pwmvalue.toString() + " > /sys/pwm/firefly_pwm"
        Log.d("CHISATO", cmdtext)
        Shell.cmd(cmdtext).exec()
         */
    }

    @Composable
    @Preview(device = "spec:width=1024dp,height=600dp,dpi=144")
    fun MyCanvas(model: PreviewFragmentViewModel) {
        val instaColors = listOf(Color.Red, Color.Red)
        val ttyStrings = mySerialPortFinder.getAllDevicesPath().toList()
        var enabled by remember { mutableStateOf(false) }
        val alpha: Float by animateFloatAsState(
            targetValue = if (enabled) 1f else 0.2f,
            // Configure the animation duration and easing.
            animationSpec = tween(durationMillis = 1000)
        )
        Column {
            Button(
                onClick = { enabled = !enabled },
                modifier = Modifier
                    .height(50.dp)
                    .width(100.dp)
                    .padding(top = 10.dp),
                content = {
                    Text(text = "Animate", color = Color.White)
                })
            Canvas(modifier = Modifier
                .width(200.dp)
                .height(200.dp)
                .graphicsLayer(alpha = alpha)
                .background(Color.Red)
                .border(color = Color.Magenta, width = 2.dp)) {
                drawCircle(
                    brush = Brush.linearGradient(colors = instaColors),
                    radius = 20f,
                    center = Offset(30f, 30f)
                )
            }
            LazyColumn {
                items(ttyStrings) { data ->
                    CallbackListItem(CallbackListDataItem(data), { content ->
                        model.connectISP()
                        println(content.text)
                    } )
                }
            }

        }
    }

    @Composable
    fun CallbackListItem(
        callbackListDataItem: CallbackListDataItem,
        itemClickedCallback: (callbackListDataItem: CallbackListDataItem) -> Unit,
    ) {
        Button(onClick = { itemClickedCallback(callbackListDataItem) }) {
            Text(text = callbackListDataItem.text, color = colorResource(R.color.white))
        }
    }

    @Composable
    fun Greeting(name: String = "CHISATO", model: PreviewFragmentViewModel) {

        val intensity: Int by model.intensityValue.observeAsState(50)
        val alpha: Float by model.alphaValue.observeAsState(1f)
        var sliderPosition by remember { mutableStateOf(50f) }

        Card (
            backgroundColor = Color.Magenta.copy(alpha = 0f),
            modifier = Modifier.alpha(alpha)
        ) {
            Column() {
                Text(
                    text = "Hello $name!",
                    color = colorResource(R.color.white),
                    modifier = Modifier.clickable {
                        EventBus.getDefault().post(Event(KeyEvent.KEYCODE_F12))
                    }
                )
                Text(
                    text = intensity.toString(),
                    color = colorResource(R.color.white)
                )
                Text(text = sliderPosition.toInt().toString(),
                    color = colorResource(R.color.white))
                Slider(value = intensity.toFloat(),
                       modifier = Modifier.width(400.dp),
                       valueRange = 0f..100f,
                       steps=10,
                       onValueChange = { sliderPosition = it
                                         model.setIntensity(it.toInt()) })
            }
        }
    }

    @Composable
    fun BitmapImage(bitmap: Bitmap) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "some useful description",
        )
    }

    @Composable
    @Preview
    fun ModalDrawerSample(model: PreviewFragmentViewModel) {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val intensity: Int by model.intensityValue.observeAsState(50)
        val alpha: Float by model.alphaValue.observeAsState(1f)
        var sliderPosition by remember { mutableStateOf(50f) }

        val imageResource: State<Bitmap?> = model.bitmap.observeAsState()

        ModalDrawer(
            drawerState = drawerState,
            drawerShape = customShape(),
            scrimColor = Color.White.copy(alpha=0f),
            drawerElevation = 0.dp,
            drawerBackgroundColor = Color.Gray.copy(alpha=0.5f),
            drawerContent = { IconButtonDemo() },
            content = {
                val instaColors = listOf(Color.Red, Color.Red)
                Box(modifier = Modifier.fillMaxSize()) {
                    Slider(value = intensity.toFloat(),
                            modifier = Modifier
                                .width(300.dp)
                                .align(Alignment.TopEnd),
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Red,
                                activeTrackColor = Color.LightGray
                            ),
                            onValueChange = {
                                sliderPosition = it
                                model.setIntensity(it.toInt())
                            })
                    Button(
                        onClick = { myTakePhoto() },
                        colors = ButtonDefaults.buttonColors(Color.Red),
                        shape = CircleShape,
                        modifier= Modifier
                            .size(70.dp)
                            .align(Alignment.CenterEnd),
                    ) {
                        Image(bitmap = ImageBitmap.imageResource(id = R.drawable.camera_capture_image_white_50),
                            null, alignment = Alignment.Center)
                    }
                    Box (modifier=Modifier.clickable { model.clearBitmap() } ){
                        if (imageResource.value != null) {
                            Image(bitmap = imageResource.value!!.asImageBitmap(), contentDescription = "image", contentScale=ContentScale.FillBounds)
                        } else {
                            // Placeholder image or loading spinner
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            /*
            {
            Column {
                Text("Text in Bodycontext")
                Button(onClick = {

                    scope.launch {
                        drawerState.open()
                    }

                }) {
                    Text("Click to open")
                }
            }
            }
            */
        )
    }

    @Composable
    fun ScaffoldDemo() {
        val materialBlue700= Color(0xFF1976D2)
        val scaffoldState = rememberScaffoldState(rememberDrawerState(DrawerValue.Open))
        val scope = rememberCoroutineScope()
        Scaffold(
            scaffoldState = scaffoldState,
            topBar = { TopAppBar(title = {Text("TopAppBar")},backgroundColor = materialBlue700)  },
            //floatingActionButtonPosition = FabPosition.End,
            //floatingActionButton = { FloatingActionButton(onClick = {}){
            //    Text("X")
            //} },
            drawerContent = { Text("Drawer content") },
            drawerShape = customShape(),
            drawerContentColor = Color.Yellow,
            drawerBackgroundColor = Color.Cyan.copy(alpha=0.5f),
            drawerElevation = 0.dp,
            //content = {},
            drawerScrimColor = Color.White.copy(alpha=0f),
            content = { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    Text("Text in Bodycontext")
                    Button(onClick = {

                        scope.launch {
                            scaffoldState.drawerState.open()
                        }

                    }) {
                        Text("Click to open")
                    }
                }
            },
            contentColor = Color.White.copy(alpha=0f),
            backgroundColor = Color.White.copy(alpha=0f),
            bottomBar = { BottomAppBar(backgroundColor = materialBlue700) { Text("BottomAppBar") } }
        )
    }

    fun customShape() =  object : Shape {
        override fun createOutline(
            size: androidx.compose.ui.geometry.Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            return Outline.Rectangle(Rect(0f,0f,106f /* width */, 600f /* height */))
        }
    }

    @Composable
    fun ClickableSample() {
        val count = remember { mutableStateOf(0) }
        val offset = Offset(5.0f, 10.0f)
        // content that you want to make clickable
        Text(
            text = count.value.toString(),
            modifier = Modifier.clickable { count.value += 1 },
            color = colorResource(R.color.white),
            fontSize = 64.sp,
            style = TextStyle(
                fontSize = 64.sp,
                shadow = Shadow(
                    color = colorResource(R.color.purple_700),
                    offset = offset,
                    blurRadius = 3f
                )
            )
        )
    }

    @Composable
    fun ScrollBoxes() {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
        ) {
            repeat(100) {
                Text("Item $it", modifier = Modifier.padding(2.dp), color = colorResource(R.color.white))
            }
        }
    }

    @Composable
    fun IconButtonDemo() {
        Column {
            Box(modifier = Modifier
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse("example://www.qt.com")
                    startActivity(intent)
                }
                .width(80.dp)){
                Image(bitmap = ImageBitmap.imageResource(id = R.drawable.v3d_model_icon),
                    null, contentScale = Fit, alignment = Alignment.Center)}
            Box(modifier = Modifier
                .clickable { println("Button Clicked!") }
                .width(80.dp))
                {
                Image(bitmap = ImageBitmap.imageResource(id = R.drawable.gallery),
                    null, contentScale = Fit, alignment = Alignment.Center)
                }
            Box(modifier = Modifier
                .clickable {
                    val deepUrl =
                        "mitcorp://process.x3000.io?key1=${myViewModel.lastSavedJpeg}&key2=value2" //key1 and key2 for sending data to other application
                    Log.d("CHISATO", deepUrl)

                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(deepUrl)
                    startActivity(intent)
                }
                .width(80.dp)){
                Image(bitmap = ImageBitmap.imageResource(id = R.drawable.settings),
                    null, contentScale = Fit, alignment = Alignment.Center)}
            Box(modifier = Modifier
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse("mitcorp://3dview.x3000.io")
                    startActivity(intent)
                }
                .width(80.dp)){
                Image(bitmap = ImageBitmap.imageResource(id = R.drawable.live_streaming),
                    null, contentScale = Fit, alignment = Alignment.Center)}
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentBinding = FragmentPreviewBinding.inflate(inflater, container, false)

        // initializeView()

        showFloatingMenu("PANEL", myViewModel)

        // Register for events
        EventBus.getDefault().register(this)

        composeView = fragmentBinding.MyComposeView
        composeView.setContent {
            MaterialTheme {
                //MyCanvas(myViewModel)
                //Greeting(name = "compose", myViewModel)
                //ClickableSample()
                //ScrollBoxes()
                //ScaffoldDemo()
                ModalDrawerSample(myViewModel)
            }
        }
        return fragmentBinding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // If we're displaying HDR, set the screen brightness to maximum. Otherwise, the preview
        // image will appear darker than video playback. It is up to the app to decide whether
        // this is appropriate - high brightness with HDR capture may dissipate a lot of heat.
        // In dark ambient environments, setting the brightness too high may make it uncomfortable
        // for users to view the screen, so apps will need to calibrate this depending on their
        // use case.
        if (args.dynamicRange != DynamicRangeProfiles.STANDARD) {
            val window = requireActivity().getWindow()
            var params = window.getAttributes()
            params.screenBrightness = 1.0f
            window.setAttributes(params)
        }

        super.onCreate(savedInstanceState)

        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                selectedImage = it.data?.getData()
                get_result = true
            }
        }

        mySerialPortFinder = SerialPortFinder()
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline.destroyWindowSurface()
            }

            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        fragmentBinding.viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${fragmentBinding.viewFinder.width} x ${fragmentBinding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")

                val mySize = Size(1843, 1080)

                fragmentBinding.viewFinder.setAspectRatio(mySize.width, mySize.height)

                pipeline.setPreviewSize(mySize)

                // To ensure that size is set, initialize camera in the view's thread
                fragmentBinding.viewFinder.post {
                    pipeline.createResources(holder.surface)
                    initializeCamera()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (get_result == true) {
            get_result = false
            Log.d("myTAG onResume", selectedImage.toString())

            val intent = Intent("com.vyw.androidopencvdemo.SHARE") //这个就是在上边配置intent-filter时设置的action name
            intent.setDataAndType(selectedImage, "share/text") //在上边intent-filter中设置的mimeType
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION //授予临时读取权限
            startActivity(intent)
        }
    }

    private fun isCurrentlyRecording(): Boolean {
        return recordingStarted && !recordingComplete
    }

    private fun createEncoder(): EncoderWrapper {
        val videoEncoder = when {
            args.dynamicRange == DynamicRangeProfiles.STANDARD -> MediaFormat.MIMETYPE_VIDEO_AVC
            args.dynamicRange < DynamicRangeProfiles.PUBLIC_MAX -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> throw IllegalArgumentException("Unknown dynamic range format")
        }

        val codecProfile = when {
            args.dynamicRange == DynamicRangeProfiles.HLG10 ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            args.dynamicRange == DynamicRangeProfiles.HDR10 ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            args.dynamicRange == DynamicRangeProfiles.HDR10_PLUS ->
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            else -> -1
        }

        var width = args.width
        var height = args.height
        var orientationHint = orientation

        if (args.useHardware) {
            if (orientation == 90 || orientation == 270) {
                width = args.height
                height = args.width
            }
            orientationHint = 0
        }

        Log.d(TAG, "width = " + width + " height = " + height + " fps = " + args.fps)

        return EncoderWrapper(width, height, RECORDER_VIDEO_BITRATE, args.fps,
                orientationHint, videoEncoder, codecProfile, outputFile)
    }

    private fun <T> concatenate(vararg lists: List<T>): List<T> {
        return listOf(*lists).flatten()
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width }!!

        val mysize = Size(1280, 720)

        imageReader = ImageReader.newInstance(
            mysize.width, mysize.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE)

        // Creates list of Surfaces where the camera will output frames
        val targets = pipeline.getTargets()

        var mytargets = mutableListOf<Surface>()

        for (target in targets) {
            mytargets.add(target)
        }
        mytargets.add(imageReader.surface)

        Log.d(TAG, "run to here")
        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, mytargets!!, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        if (previewRequest == null) {
            session.setRepeatingRequest(recordRequest, null, cameraHandler)
        } else {
            session.setRepeatingRequest(previewRequest!!, null, cameraHandler)
        }

        // Listen to the capture button
        fragmentBinding.snapButton.setOnClickListener {

            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    //imageReader.close()

                    Log.d(TAG, "Result received: $result")

                    // Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                    }

                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(PreviewFragmentDirections
                            .actionCameraToJpegViewer(output.absolutePath)
                            .setOrientation(result.orientation)
                            .setDepth(
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    result.format == ImageFormat.DEPTH_JPEG))
                    }

                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }

        // React to user touching the capture button
        fragmentBinding.captureButton.setOnTouchListener { view, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
                    /* If the recording was already started in the past, do nothing. */
                    if (!recordingStarted) {
                        // Prevents screen rotation during the video recording
                        requireActivity().requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_LOCKED

                        pipeline.actionDown(encoderSurface)

                        // Finalizes encoder setup and starts recording
                        recordingStarted = true
                        encoder.start()
                        cvRecordingStarted.open()
                        pipeline.startRecording()

                        // Start recording repeating requests, which will stop the ongoing preview
                        //  repeating requests without having to explicitly call
                        //  `session.stopRepeating`
                        if (previewRequest != null) {
                            session.setRepeatingRequest(recordRequest,
                                    object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                                request: CaptureRequest,
                                                                result: TotalCaptureResult) {
                                    if (isCurrentlyRecording()) {
                                        encoder.frameAvailable()
                                    }
                                }
                            }, cameraHandler)
                        }

                        recordingStartMillis = System.currentTimeMillis()
                        Log.d(TAG, "Recording started")

                        // Starts recording animation
                        fragmentBinding.overlay.post(animationTask)
                    }
                }

                MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
                    cvRecordingStarted.block()

                    /* Wait for at least one frame to process so we don't have an empty video */
                    encoder.waitForFirstFrame()

                    session.stopRepeating()

                    pipeline.clearFrameListener()
                    fragmentBinding.captureButton.setOnTouchListener(null)

                    /* Wait until the session signals onReady */
                    cvRecordingComplete.block()

                    // Unlocks screen rotation after recording finished
                    requireActivity().requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                    val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                    if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                        delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                    }

                    delay(CameraActivity.ANIMATION_SLOW_MILLIS)

                    Log.d(TAG, "Recording stopped. Output file: $outputFile")
                    encoder.shutdown()

                    pipeline.cleanup()

                    // Broadcasts the media file to the rest of the system
                    MediaScannerConnection.scanFile(
                            requireView().context, arrayOf(outputFile.absolutePath), null, null)

                    // Launch external activity via intent to play video recorded using our provider
                    startActivity(Intent().apply {
                        action = Intent.ACTION_VIEW
                        type = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(outputFile.extension)
                        val authority = "${BuildConfig.APPLICATION_ID}.provider"
                        data = FileProvider.getUriForFile(view.context, authority, outputFile)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })

                    navController.popBackStack()
                }
            }

            true
        }

        // Listen to the viewer button
        fragmentBinding.viewerButton.setOnClickListener {
            // launch file picker
            val picker = Intent(Intent.ACTION_GET_CONTENT)
            picker.type = "image/*"
            resultLauncher.launch(picker)
        }
    }

    private fun collapse(tag: String) {
        //EasyFloat.getFloatView(tag)!!.animate().translationX(280f)
        //EasyFloat.getFloatView(tag)!!.animate().translationY(280f)
        EasyFloat.getFloatView(tag)!!.findViewById<Button>(R.id.button1).isVisible = false
        EasyFloat.getFloatView(tag)!!.findViewById<Button>(R.id.ivClose).isVisible = false
        EasyFloat.getFloatView(tag)!!.findViewById<ImageView>(R.id.ivScale).layoutParams.width = 64
        EasyFloat.getFloatView(tag)!!.findViewById<ImageView>(R.id.ivScale).layoutParams.height = 64

        val layout = EasyFloat.getFloatView(tag)!!.findViewById(R.id.rlContent) as RelativeLayout
        layout.layoutParams.width = 64
        layout.layoutParams.height = 64
        layout.setBackgroundResource(R.drawable.layout_round)
        EasyFloat.updateFloat(tag, width = 64, height = 64  )
        EasyFloat.updateFloat(tag, -1, -1, -1, -1)
        isCollapsed = true
    }

    private fun expand(tag: String) {
        //view.animate().translationX(0f)
        //view.animate().translationY(0f)
        EasyFloat.getFloatView(tag)!!.findViewById<Button>(R.id.button1).isVisible = true
        EasyFloat.getFloatView(tag)!!.findViewById<Button>(R.id.ivClose).isVisible = false
        EasyFloat.getFloatView(tag)!!.findViewById<ImageView>(R.id.ivScale).layoutParams.width = 48
        EasyFloat.getFloatView(tag)!!.findViewById<ImageView>(R.id.ivScale).layoutParams.height = 48

        val layout = EasyFloat.getFloatView(tag)!!.findViewById(R.id.rlContent) as RelativeLayout
        layout.layoutParams.width = 320
        layout.layoutParams.height = 320
        layout.setBackgroundResource(R.drawable.layout_bg)
        EasyFloat.updateFloat(tag, width = 320, height = 320)
        EasyFloat.updateFloat(tag, -1, -1, -1, -1)
        isCollapsed = false
    }

    private fun showFloatingMenu(tag: String, model: PreviewFragmentViewModel) {
        //EasyFloat.with(getContext()!!)
        EasyFloat.with(requireContext())
            .setTag(tag)
            .setImmersionStatusBar(true)
            .setSidePattern(SidePattern.RESULT_SIDE)
            .setGravity(Gravity.CENTER)
            .setLayoutChangedGravity(Gravity.CENTER)
            .setLayout(R.layout.float_app_scale) {

                when (isCollapsed) {
                    true -> collapse(tag)
                    false -> expand(tag)
                }

                it.findViewById<ImageView>(R.id.ivScale).setOnClickListener {
                    when (isCollapsed) {
                        false -> collapse(tag)
                        true -> expand(tag)
                    }
                }

                it.findViewById<ImageView>(R.id.ivClose).setOnClickListener {
                    EasyFloat.dismiss(tag)
                }

                it.findViewById<ToggleButton>(R.id.button1).setOnCheckedChangeListener { _, isChecked ->
                    it.isEnabled = false
                    model.setMirrorState(isChecked)
                    it.post { it.isEnabled = true }
                }

                it.findViewById<ToggleButton>(R.id.button2).setOnCheckedChangeListener { _, isChecked ->
                    it.isEnabled = false
                    model.setPatternLed(isChecked)
                    it.post { it.isEnabled = true }
                }

                it.findViewById<Button>(R.id.button3).setOnClickListener {
                    model.loadJpegAndAddWatermark(requireContext())
                }
            }
            .registerCallback {
                // 在此处设置view也可以，建议在setLayout进行view操作
                createResult { isCreated, msg, _ ->
                    Toast.makeText(context, "isCreated: $isCreated", Toast.LENGTH_SHORT)
                }

                show { Toast.makeText(context, "show", Toast.LENGTH_SHORT) }

                hide { Toast.makeText(context, "hide", Toast.LENGTH_SHORT) }

                dismiss { Toast.makeText(context, "dismiss", Toast.LENGTH_SHORT) }

                touchEvent { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            Log.d(TAG, "ACTION_DOWN")
                            Log.d(TAG, event.getRawX().toString())
                            Log.d(TAG, event.getRawY().toString())
                        }
                        MotionEvent.ACTION_MOVE -> {
                            Log.d(TAG, "ACTION_MOVE")
                        }
                        MotionEvent.ACTION_UP -> {
                            Log.d(TAG, "ACTION_UP")
                        }
                    }
                }

                drag { view, motionEvent ->
                    Log.d(TAG, "DRAGING")
                }

                dragEnd {
                    Log.d(TAG, "DRAG END")
                }
            }
            .show()
    }

    private fun showSideMenu(tag: String) {
        //EasyFloat.with(getContext()!!)
        EasyFloat.with(requireContext())
            .setTag(tag)
            .setDragEnable(false)
            .setImmersionStatusBar(true)
            .setSidePattern(SidePattern.RESULT_SIDE)
            .setGravity(Gravity.START)
            .setLayoutChangedGravity(Gravity.CENTER)
            .setLayout(R.layout.float_sidebar) {

                it.findViewById<ImageView>(R.id.ivScale).setOnClickListener {
                    when (isCollapsed) {
                        false -> collapse(tag)
                        true -> expand(tag)
                    }
                }

                it.findViewById<ImageView>(R.id.ivClose).setOnClickListener {
                    EasyFloat.dismiss(tag)
                }

                it.findViewById<Button>(R.id.button1).setOnClickListener {
                    // Disable click listener to prevent multiple requests simultaneously in flight
                    it.isEnabled = false

                    // Perform I/O heavy operations in a different scope
                    lifecycleScope.launch(Dispatchers.IO) {
                        takePhoto().use { result ->
                            //imageReader.close()

                            Log.d(TAG, "Result received: $result")

                            // Save the result to disk
                            val output = saveResult(result)
                            Log.d(TAG, "Image saved: ${output.absolutePath}")

                            // If the result is a JPEG file, update EXIF metadata with orientation info
                            if (output.extension == "jpg") {
                                val exif = ExifInterface(output.absolutePath)
                                exif.setAttribute(
                                    ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                exif.saveAttributes()
                                Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")
                            }

                            // Display the photo taken to user
                            lifecycleScope.launch(Dispatchers.Main) {
                                navController.navigate(PreviewFragmentDirections
                                    .actionCameraToJpegViewer(output.absolutePath)
                                    .setOrientation(result.orientation)
                                    .setDepth(
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                                result.format == ImageFormat.DEPTH_JPEG))
                            }

                        }

                        // Re-enable click listener after photo is taken
                        it.post { it.isEnabled = true }
                    }
                }
            }
            .registerCallback {
                // 在此处设置view也可以，建议在setLayout进行view操作
                createResult { isCreated, msg, _ ->
                    Toast.makeText(context, "isCreated: $isCreated", Toast.LENGTH_SHORT)
                }

                show { Toast.makeText(context, "show", Toast.LENGTH_SHORT) }

                hide { Toast.makeText(context, "hide", Toast.LENGTH_SHORT) }

                dismiss { Toast.makeText(context, "dismiss", Toast.LENGTH_SHORT) }

                touchEvent { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            Log.d(TAG, "ACTION_DOWN")
                            Log.d(TAG, event.getRawX().toString())
                            Log.d(TAG, event.getRawY().toString())
                        }
                        MotionEvent.ACTION_MOVE -> {
                            Log.d(TAG, "ACTION_MOVE")
                        }
                        MotionEvent.ACTION_UP -> {
                            Log.d(TAG, "ACTION_UP")
                        }
                    }
                }

                drag { view, motionEvent ->
                    Log.d(TAG, "DRAGING")
                }

                dragEnd {
                    Log.d(TAG, "DRAG END")
                }
            }
            .show()
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] with the dynamic range profile set.
     */
    private fun setupSessionWithDynamicRangeProfile(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null,
            stateCallback: CameraCaptureSession.StateCallback
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val outputConfigs = mutableListOf<OutputConfiguration>()
            for (target in targets) {
                val outputConfig = OutputConfiguration(target)
                outputConfig.setDynamicRangeProfile(args.dynamicRange)
                outputConfigs.add(outputConfig)
            }

            device.createCaptureSessionByOutputConfigurations(
                    outputConfigs, stateCallback, handler)
            return true
        } else {
            device.createCaptureSession(targets, stateCallback, handler)
            return false
        }
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val stateCallback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

            /** Called after all captures have completed - shut down the encoder */
            override fun onReady(session: CameraCaptureSession) {
                if (!isCurrentlyRecording()) {
                    return
                }

                recordingComplete = true
                pipeline.stopRecording()
                cvRecordingComplete.open()
            }
        }

        setupSessionWithDynamicRangeProfile(device, targets, handler, stateCallback)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto():
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                //fragmentBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // if (image.timestamp != resultTimestamp) continue

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }
                        Log.d(TAG, "world ends here")

                        // Compute EXIF orientation metadata
                        //val rotation = relativeOrientation.value ?: 0
                        //val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        //        CameraCharacteristics.LENS_FACING_FRONT
                        //val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(
                            image, result, ExifInterface.ORIENTATION_NORMAL, imageReader.imageFormat))

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val output = createFile(requireContext(), "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // When the format is RAW we use the DngCreator utility library
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    val output = createFile(requireContext(), "dng")
                    FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write DNG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        encoderSurface.release()
        Log.d(TAG, "onDestory")
    }

    override fun onDestroyView() {
        _fragmentBinding = null
        super.onDestroyView()
    }

    fun myTakePhoto(){
        // Perform I/O heavy operations in a different scope
        lifecycleScope.launch(Dispatchers.IO) {
            takePhoto().use { result ->
                //imageReader.close()

                Log.d(TAG, "Result received: $result")

                // Save the result to disk
                val output = saveResult(result)
                Log.d(TAG, "Image saved: ${output.absolutePath}")

                val zipOutput = myViewModel.saveToZip(requireContext(), output.absolutePath)
                Log.d(TAG, "Zip saved: ${zipOutput.absolutePath}")

                // If the result is a JPEG file, update EXIF metadata with orientation info
                if (output.extension == "jpg") {
                    val exif = ExifInterface(output.absolutePath)
                    exif.setAttribute(
                        ExifInterface.TAG_ORIENTATION, result.orientation.toString()
                    )
                    exif.saveAttributes()
                    Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "${output.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    myViewModel.lastSavedJpeg = output.absolutePath
                }
            }
        }
    }

    private fun initializeView() {

        // set the max value of the slider using setMax function
        fragmentBinding.DemoSlider.max = 100
        fragmentBinding.DemoSlider.setProgress(intensityValue)
        fragmentBinding.DemoSlider.visibility = View.INVISIBLE

        fragmentBinding.DemoSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // override the onProgressChanged method to perform operations
            // whenever the there a change in SeekBar
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                intensityValue = progress
                fragmentBinding.numberViewer.text = progress.toString()

                val pwmvalue = 25000 - intensityValue * 250
                val cmdtext = "echo " + pwmvalue.toString() + " > /sys/pwm/firefly_pwm"
                Log.d("CHISATO", cmdtext)
                Shell.cmd(cmdtext).exec()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    companion object {
        private val TAG = PreviewFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val filepath = "MyFileStorage"
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            //return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
            return File(context.getExternalFilesDir(filepath), "VID_${sdf.format(Date())}.$extension")
        }

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        private val myRequestActivityCode = 1
        private lateinit var resultLauncher: ActivityResultLauncher<Intent>
        private var selectedImage: Uri ?= null
        private var get_result: Boolean ?= false

        private var intensityValue: Int = 50
        private var isCollapsed: Boolean = true
        private var maskActionDown: Boolean = false
        private var bActionMoved: Boolean = false
        private var eventX: Int = 0
        private var eventY: Int = 0
    }
}
