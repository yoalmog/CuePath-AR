package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.snapshotFlow
import com.google.ar.core.Config
import com.google.ar.sceneform.ux.ArFragment
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraXPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SavedShot
import com.example.physics.BilliardsPhysics
import com.example.physics.PointF
import com.example.physics.TableSize
import com.example.ui.BilliardsViewModel
import com.example.ui.theme.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : FragmentActivity() {
    private val viewModel: BilliardsViewModel by viewModels()
    private var arFragment: ArFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load the XML layout containing the ARFragment
        setContentView(R.layout.activity_main)

        // Locate the ARFragment from the XML layout
        arFragment = supportFragmentManager.findFragmentById(R.id.ar_fragment) as? ArFragment

        // Set up the ARCore session configuration and plane detection listener
        setupARCorePlaneDetection()

        // Embed the Jetpack Compose overlays inside overlay_container on top of AR view
        val overlayContainer = findViewById<FrameLayout>(R.id.overlay_container)
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                MyApplicationTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        BilliardsApp(viewModel = viewModel)
                    }
                }
            }
        }
        overlayContainer.addView(composeView)

        // Reactively toggle visibility of the ARFragment based on current tab and permissions
        lifecycleScope.launch {
            snapshotFlow { Pair(viewModel.currentTab, viewModel.cameraPermissionGranted) }.collect { (tab, granted) ->
                val arView = arFragment?.view
                if (tab == "ar_camera" && granted) {
                    arView?.visibility = android.view.View.VISIBLE
                } else {
                    arView?.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun setupARCorePlaneDetection() {
        arFragment?.setOnSessionConfigurationListener { session, config ->
            // Configure horizontal plane detection to detect pool table surfaces
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.focusMode = Config.FocusMode.AUTO
            session.configure(config)
        }

        arFragment?.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            // Create an anchor at the user's tapped location on the detected pool table plane
            val anchor = hitResult.createAnchor()

            // Display feedback toast
            Toast.makeText(
                this,
                "פני שולחן הביליארד זוהו בהצלחה! נקודת העיגון של קווי ההכוון נקבעה.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@Composable
fun OrientationSensorObserver(viewModel: BilliardsViewModel) {
    val context = LocalContext.current
    DisposableEffect(viewModel.isOrientationAimEnabled) {
        if (!viewModel.isOrientationAimEnabled) {
            viewModel.deviceTiltAngle = 0f
            return@DisposableEffect onDispose {}
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientationValues = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientationValues)
                    // orientationValues[2] is roll (left/right tilt) in radians.
                    // We invert it slightly to match the physical tilt direction in landscape/portrait correctly.
                    val rollAngleDeg = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
                    viewModel.deviceTiltAngle = rollAngleDeg.coerceIn(-45f, 45f)
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && rotationSensor == null) {
                    val x = event.values[0]
                    val rollAngleDeg = -x * 9f
                    viewModel.deviceTiltAngle = rollAngleDeg.coerceIn(-45f, 45f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
            viewModel.deviceTiltAngle = 0f
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilliardsApp(viewModel: BilliardsViewModel) {
    val context = LocalContext.current
    
    // Register real-time orientation sensor tracking
    OrientationSensorObserver(viewModel = viewModel)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.cameraPermissionGranted = isGranted
    }

    // Check camera permission
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.cameraPermissionGranted = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Adjust,
                            contentDescription = "CuePath Log",
                            tint = NeonFelt,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CuePath AR",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 22.sp
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.showAlignmentOverlay = true },
                        modifier = Modifier.testTag("alignment_help_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Help,
                            contentDescription = "Camera Alignment Help",
                            tint = NeonFelt
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = VelvetBlack
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = VelvetBlack,
                tonalElevation = 8.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = viewModel.currentTab == "ar_camera",
                    onClick = { viewModel.currentTab = "ar_camera" },
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = "AR Mode") },
                    label = { Text("מציאות רבודה", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonFelt,
                        selectedTextColor = NeonFelt,
                        indicatorColor = BorderDark,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_ar_camera")
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == "simulator_2d",
                    onClick = { viewModel.currentTab = "simulator_2d" },
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = "2D Simulator") },
                    label = { Text("סימולטור דו-מימד", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonFelt,
                        selectedTextColor = NeonFelt,
                        indicatorColor = BorderDark,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_simulator")
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == "saved_drills",
                    onClick = { viewModel.currentTab = "saved_drills" },
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = "Saved Drills") },
                    label = { Text("תרגילים שמורים", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonFelt,
                        selectedTextColor = NeonFelt,
                        indicatorColor = BorderDark,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_drills")
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == "physics_guide",
                    onClick = { viewModel.currentTab = "physics_guide" },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = "Physics Guide") },
                    label = { Text("מדריך פיזיקה", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonFelt,
                        selectedTextColor = NeonFelt,
                        indicatorColor = BorderDark,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_guide")
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (viewModel.currentTab == "ar_camera") Color.Transparent else VelvetBlack)
                .padding(paddingValues)
        ) {
            when (viewModel.currentTab) {
                "ar_camera" -> ARCameraScreen(viewModel = viewModel)
                "simulator_2d" -> SimulatorScreen(viewModel = viewModel)
                "saved_drills" -> SavedDrillsScreen(viewModel = viewModel)
                "physics_guide" -> PhysicsGuideScreen()
            }

            if (viewModel.showAlignmentOverlay) {
                CameraAlignmentOverlay(onDismiss = { viewModel.showAlignmentOverlay = false })
            }
        }
    }
}

// --- SCREEN 1: AR CAMERA SCREEN ---
@Composable
fun ARCameraScreen(viewModel: BilliardsViewModel) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (viewModel.cameraPermissionGranted) {
            CameraXView(modifier = Modifier.fillMaxSize())
        } else {
            // Permission fallback message
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VelvetBlack)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PanelDark),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoPhotography,
                            contentDescription = "No Camera Access",
                            tint = Color.LightGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "מצב מציאות רבודה (AR)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "נדרשת הרשאת מצלמה על מנת להציג את קווי ההכוון המדויקים על גבי שולחן הביליארד האמיתי שלך.",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        val permissionLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.RequestPermission()
                        ) { isGranted ->
                            viewModel.cameraPermissionGranted = isGranted
                        }
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonFelt),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("request_camera_btn")
                        ) {
                            Text("אשר גישה למצלמה", fontWeight = FontWeight.Bold, color = VelvetBlack)
                        }
                    }
                }
            }
        }

        // Overlay Interactive aiming canvas
        // This Canvas is completely transparent, only drawing the pool elements on top of the Camera!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Info HUD Card at top
                InfoHUDCard(viewModel = viewModel, isAR = true)
                
                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Table
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    BilliardsTableCanvas(
                        viewModel = viewModel,
                        showLiveCamera = viewModel.cameraPermissionGranted,
                        modifier = Modifier
                            .fillMaxSize()
                            .border(2.dp, BorderDark, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .testTag("ar_table_canvas")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Control panel at bottom
                AimControlPanel(viewModel = viewModel)
            }
        }
    }
}

// --- SCREEN 2: 2D SIMULATOR SCREEN ---
@Composable
fun SimulatorScreen(viewModel: BilliardsViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Info HUD
            InfoHUDCard(viewModel = viewModel, isAR = false)

            Spacer(modifier = Modifier.height(12.dp))

            // Virtual Green Pool Table
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                BilliardsTableCanvas(
                    viewModel = viewModel,
                    showLiveCamera = false, // Draw solid green pool felt!
                    modifier = Modifier
                        .fillMaxSize()
                        .border(4.dp, Color(0xFF5C4033), RoundedCornerShape(12.dp)) // Nice wooden border
                        .clip(RoundedCornerShape(12.dp))
                        .testTag("simulator_table_canvas")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Spin & Speed Controller
            AimControlPanel(viewModel = viewModel)
        }
    }
}

// --- SCREEN 3: SAVED DRILLS SCREEN ---
@Composable
fun SavedDrillsScreen(viewModel: BilliardsViewModel) {
    val shots by viewModel.savedShots.collectAsStateWithLifecycle()

    if (shots.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = "No Saved Drills",
                    tint = BorderDark,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "אין תרגילים שמורים",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "תוכל לשמור סידורי כדורים, זוויות חיתוך וזריקות קשות במצב המצלמה או הסימולטור כדי להתאמן עליהם מאוחר יותר.",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "התרגילים והזריקות שלי",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(shots, key = { it.id }) { shot ->
                SavedShotCard(
                    shot = shot,
                    onLoad = { viewModel.loadShot(shot) },
                    onDelete = { viewModel.deleteShot(shot.id) }
                )
            }
        }
    }
}

@Composable
fun SavedShotCard(
    shot: SavedShot,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLoad() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small Thumbnail of the Table Layout
            Box(
                modifier = Modifier
                    .size(90.dp, 55.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FeltGreen)
                    .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    
                    // Draw pockets
                    val pxs = listOf(5f, w / 2, w - 5f, 5f, w / 2, w - 5f)
                    val pys = listOf(5f, 4f, 5f, h - 5f, h - 4f, h - 5f)
                    for (i in pxs.indices) {
                        drawCircle(Color.Black, radius = 4f, center = Offset(pxs[i], pys[i]))
                    }

                    // Selected Pocket (Shining indicator)
                    if (shot.pocketId in 0..5) {
                        drawCircle(
                            color = NeonFelt.copy(alpha = 0.6f),
                            radius = 6f,
                            center = Offset(pxs[shot.pocketId], pys[shot.pocketId]),
                            style = Stroke(width = 1.5f)
                        )
                    }

                    // Cue Ball
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = Offset(shot.cueX * w, shot.cueY * h)
                    )

                    // Target Ball
                    drawCircle(
                        color = TargetRed,
                        radius = 4f,
                        center = Offset(shot.targetX * w, shot.targetY * h)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shot.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (shot.notes.isNotEmpty()) {
                    Text(
                        text = shot.notes,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pocketName = when (shot.pocketId) {
                        0 -> "פינה עליונה שמאל"
                        1 -> "אמצע עליון"
                        2 -> "פינה עליונה ימין"
                        3 -> "פינה תחתונה שמאל"
                        4 -> "אמצע תחתון"
                        5 -> "פינה תחתונה ימין"
                        else -> "כיס"
                    }
                    Text(
                        text = "כיס מטרה: $pocketName",
                        fontSize = 11.sp,
                        color = NeonFelt,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_shot_btn_${shot.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Saved Shot",
                    tint = Color.Gray
                )
            }
        }
    }
}

// --- SCREEN 4: PHYSICS GUIDE SCREEN ---
@Composable
fun PhysicsGuideScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "חוקי הפיזיקה של הביליארד",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "לימוד והבנת הפיזיקה והגיאומטריה של השולחן מאפשרת הכוונה מושלמת ושליטה מלאה בכדור הלבן.",
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        item {
            GuideCard(
                title = "שיטת כדור הרפאים (Ghost Ball)",
                explanation = "הדרך הנפוצה ביותר לכיוון זריקות חיתוך. על מנת לשלוח את הכדור הצבעוני ישירות לכיס, על הכדור הלבן לפגוע בו בנקודה המדויקת שנמצאת בצד הנגדי של קו הכיס. נקודה זו היא מרכזו של כדור הרפאים התיאורטי - הדמיה מושלמת של מיקום הכדור הלבן ברגע הפגיעה.",
                ruleName = "קו השפעה",
                illustrationColor = NeonFelt
            ) {
                // Interactive miniature demonstration canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(FeltGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    
                    val pocket = Offset(w - 30f, h / 2)
                    val target = Offset(w * 0.6f, h / 2)
                    val ghost = Offset(w * 0.6f - 30f, h / 2 - 10f)
                    val cue = Offset(50f, h - 30f)

                    // Draw pocket
                    drawCircle(Color.Black, radius = 12f, center = pocket)
                    
                    // Draw lines
                    // Pocket to Target
                    drawLine(Color.Gray, start = pocket, end = target, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    // Cue to Ghost
                    drawLine(Color.White.copy(alpha = 0.6f), start = cue, end = ghost, strokeWidth = 2f)

                    // Draw balls
                    // Cue
                    drawCircle(Color.White, radius = 10f, center = cue)
                    // Ghost (Dotted)
                    drawCircle(Color.White.copy(alpha = 0.3f), radius = 10f, center = ghost, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
                    // Target
                    drawCircle(TargetRed, radius = 10f, center = target)

                    // Text labels
                    // Hebrew annotations are drawn via layout or left simple
                }
            }
        }

        item {
            GuideCard(
                title = "חוק 90 המעלות (Tangent Line)",
                explanation = "חוק הפיזיקה החשוב ביותר לשליטה בלבן לאחר הפגיעה. כאשר כדור לבן מחליק (ללא סיבוב עילי או תחתי) ופוגע בכדור צבעוני בזווית כלשהי, הלבן תמיד ימשיך להתגלגל בדיוק בזווית של 90 מעלות (ניצב) לקו הפגיעה. זהו קו המשיק (Tangent Line).",
                ruleName = "כדור מחליק (Stun Shot)",
                illustrationColor = ChalkBlue
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(FeltGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    
                    val impact = Offset(w / 2, h / 2)
                    val targetPath = Offset(w / 2 + 50f, h / 2 - 40f)
                    val cuePath = Offset(w / 2 - 40f, h / 2 - 50f)
                    val cueOriginal = Offset(w / 2 - 80f, h / 2 + 60f)

                    // Draw paths
                    drawLine(Color.White.copy(alpha = 0.5f), start = cueOriginal, end = impact, strokeWidth = 2f)
                    drawLine(TargetRed.copy(alpha = 0.5f), start = impact, end = targetPath, strokeWidth = 2f)
                    drawLine(NeonFelt, start = impact, end = cuePath, strokeWidth = 2f) // Tangent

                    // Cue Ball
                    drawCircle(Color.White, radius = 10f, center = cueOriginal)
                    // Target Ball at impact
                    drawCircle(TargetRed, radius = 10f, center = impact)
                    
                    // Draw 90 deg arc indicator
                    drawCircle(Color.Yellow, radius = 15f, center = impact, style = Stroke(width = 1.5f))
                }
            }
        }

        item {
            GuideCard(
                title = "חוק 30 המעלות & סיבוב עילי (Follow)",
                explanation = "כאשר הכדור הלבן מתגלגל עם סיבוב קדמי טבעי (Follow/Topspin) ופוגע בכדור צבעוני, הוא אינו נע לאורך קו המשיק של 90 מעלות. במקום זאת, הסיבוב הקדמי גורם לו 'להתכופף' קדימה ולנוע בזווית של כ-30 מעלות ביחס למסלול המקורי.",
                ruleName = "כדור מתגלגל (Rolling Cue Ball)",
                illustrationColor = BallGold
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(FeltGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    
                    val impact = Offset(w * 0.4f, h / 2)
                    val cueOriginal = Offset(30f, h - 20f)
                    val targetPath = Offset(w * 0.8f, h / 2 - 20f)
                    
                    // Curved path for topspin
                    val curveControl = Offset(w * 0.55f, h / 2 + 10f)
                    val curveEnd = Offset(w - 30f, h / 2 + 35f)

                    // Draw straight cue path to impact
                    drawLine(Color.White.copy(alpha = 0.5f), start = cueOriginal, end = impact, strokeWidth = 2f)
                    drawLine(TargetRed.copy(alpha = 0.5f), start = impact, end = targetPath, strokeWidth = 2f)
                    
                    // Draw curved follow path
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(impact.x, impact.y)
                        quadraticTo(curveControl.x, curveControl.y, curveEnd.x, curveEnd.y)
                    }
                    drawPath(path, color = BallGold, style = Stroke(width = 2.5f))

                    drawCircle(Color.White, radius = 10f, center = cueOriginal)
                    drawCircle(TargetRed, radius = 10f, center = impact)
                }
            }
        }
    }
}

@Composable
fun GuideCard(
    title: String,
    explanation: String,
    ruleName: String,
    illustrationColor: Color,
    illustration: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(illustrationColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = ruleName,
                        fontSize = 10.sp,
                        color = illustrationColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = explanation,
                fontSize = 13.sp,
                color = Color.LightGray,
                lineHeight = 18.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Visual illustration
            illustration()
        }
    }
}

// --- SUB-COMPONENT: INFO HUD CARD ---
@Composable
fun InfoHUDCard(viewModel: BilliardsViewModel, isAR: Boolean) {
    val dirHebrew = if (viewModel.cutDirection == "ימין") "לצד ימין" else "לצד שמאל"
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelDark.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Mode Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor by animateColorAsState(
                        targetValue = if (isAR) NeonFelt else BallGold,
                        animationSpec = tween(300), label = "status_color"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAR) "עוזר הכוון במציאות רבודה" else "סימולטור פיזיקלי דו-מימדי",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                // Active status indicators & Grid quick toggle button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.showCoordinateGrid = !viewModel.showCoordinateGrid },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("quick_grid_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (viewModel.showCoordinateGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = "Toggle Tactical Grid",
                            tint = if (viewModel.showCoordinateGrid) Color(0xFF00E5FF) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = if (viewModel.isShotAnimating) "זריקה פעילה..." else "מוכן לכיוון",
                        fontSize = 11.sp,
                        color = if (viewModel.isShotAnimating) BallGold else NeonFelt,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Divider(color = BorderDark, modifier = Modifier.padding(vertical = 8.dp))

            // Aiming Math Details Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("זווית חיתוך (Cut Angle)", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = "${String.format("%.1f", viewModel.cutAngle)}°",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                
                Column(modifier = Modifier.weight(1.2f)) {
                    Text("נקודת כיוון (Aim Point)", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = viewModel.aimFraction,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = BallGold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("חיתוך נדרש", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = dirHebrew,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (viewModel.cutDirection == "ימין") ChalkBlue else TargetRed
                    )
                }
            }
        }
    }
}

// --- SUB-COMPONENT: AIM CONTROL PANEL ---
@Composable
fun AimControlPanel(viewModel: BilliardsViewModel) {
    var showSaveDialog by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        SaveShotDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { title, notes ->
                viewModel.saveCurrentShot(title, notes)
                showSaveDialog = false
            }
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = PanelDark.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "אפקט ומהירות מכה (Spin & Power)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Row {
                    // Save Button
                    IconButton(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("save_drill_icon_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save Shot Layout",
                            tint = BallGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Part: Dual-Axis Spin pad (Cue ball simulation)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Text("סיבוב (English)", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Graphical English Pad
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.5.dp, Color.DarkGray, CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // Scale dragging values within -1f to 1f bounds
                                    val newX = (viewModel.spinX + dragAmount.x / 36f).coerceIn(-1f, 1f)
                                    val newY = (viewModel.spinY - dragAmount.y / 36f).coerceIn(-1f, 1f) // Invert Y for draw/follow
                                    viewModel.spinX = newX
                                    viewModel.spinY = newY
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Drawing crosshair lines for reference
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawLine(Color.LightGray, start = Offset(0f, size.height/2), end = Offset(size.width, size.height/2), strokeWidth = 1f)
                            drawLine(Color.LightGray, start = Offset(size.width/2, 0f), end = Offset(size.width/2, size.height), strokeWidth = 1f)
                        }
                        
                        // User Spin pointer
                        val offsetX = (viewModel.spinX * 30f)
                        val offsetY = -(viewModel.spinY * 30f)
                        Box(
                            modifier = Modifier
                                .offset(x = offsetX.dp, y = offsetY.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(TargetRed)
                                .border(1.dp, Color.White, CircleShape)
                        )
                    }
                    
                    // Center Spin Reset Button
                    Text(
                        text = "אפס סיבוב",
                        fontSize = 9.sp,
                        color = NeonFelt,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                viewModel.spinX = 0f
                                viewModel.spinY = 0f
                            }
                    )
                }

                // Right Part: Speed Slider and Launch Actions
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "עוצמת מכה (Speed): ${String.format("%.1f", viewModel.shotSpeed)}",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                    Slider(
                        value = viewModel.shotSpeed,
                        onValueChange = { viewModel.shotSpeed = it },
                        valueRange = 1f..10f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonFelt,
                            activeTrackColor = NeonFelt,
                            inactiveTrackColor = BorderDark
                        ),
                        modifier = Modifier.testTag("speed_slider")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Trigger Simulation
                        Button(
                            onClick = { viewModel.fireShot() },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonFelt),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !viewModel.isShotAnimating,
                            modifier = Modifier
                                .weight(1.5f)
                                .testTag("fire_shot_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Fire", tint = VelvetBlack)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("שגר מכה", fontWeight = FontWeight.Bold, color = VelvetBlack, fontSize = 13.sp)
                        }

                        // Reset Table Position
                        OutlinedButton(
                            onClick = { viewModel.resetAnimation() },
                            border = BorderStroke(1.dp, Color.Gray),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_shot_btn")
                        ) {
                            Text("אפס", fontSize = 13.sp)
                        }
                    }
                }
            }

            Divider(color = BorderDark, modifier = Modifier.padding(vertical = 10.dp))

            var isCalibrationExpanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isCalibrationExpanded = !isCalibrationExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Tune Physics",
                        tint = NeonFelt,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "כיול שולחן, כדורים וחיישנים",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Icon(
                    imageVector = if (isCalibrationExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = Color.Gray
                )
            }

            AnimatedVisibility(visible = isCalibrationExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pool Table Size Selector Dropdown
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BorderDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                                .clickable { dropdownExpanded = true }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "מידת שולחן (Table Size)",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = viewModel.selectedTableSize.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "בחר גודל שולחן",
                                tint = NeonFelt
                            )
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .background(PanelDark)
                                .border(1.5.dp, BorderDark, RoundedCornerShape(8.dp))
                                .testTag("table_size_dropdown_menu")
                        ) {
                            TableSize.values().forEach { size ->
                                DropdownMenuItem(
                                    text = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = size.displayName,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (viewModel.selectedTableSize == size) NeonFelt else Color.White,
                                                    fontSize = 13.sp
                                                )
                                                if (viewModel.selectedTableSize == size) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = NeonFelt,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = size.description,
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateTableSize(size)
                                        dropdownExpanded = false
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("table_size_item_${size.name.lowercase()}")
                                )
                            }
                        }
                    }

                    Divider(color = BorderDark.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

                    // Friction calibration slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val surfaceText = when {
                                viewModel.friction < 0.10f -> "חלק במיוחד (ספיד לבד)"
                                viewModel.friction < 0.20f -> "לבד סטנדרטי (Classic Felt)"
                                viewModel.friction < 0.30f -> "חיכוך בינוני (בד עבה)"
                                else -> "חיכוך גבוה מאוד (שולחן איטי)"
                            }
                            Text(
                                text = "חיכוך משטח: ${String.format("%.2f", viewModel.friction)} ($surfaceText)",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                        Slider(
                            value = viewModel.friction,
                            onValueChange = { viewModel.friction = it },
                            valueRange = 0.05f..0.40f,
                            colors = SliderDefaults.colors(
                                thumbColor = ChalkBlue,
                                activeTrackColor = ChalkBlue,
                                inactiveTrackColor = BorderDark
                            ),
                            modifier = Modifier.testTag("friction_slider")
                        )
                    }

                    // Ball Mass calibration slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val massText = when {
                                viewModel.ballMass < 150f -> "כדור קל (סנוקר)"
                                viewModel.ballMass < 190f -> "כדור סטנדרטי (Pool - 170g)"
                                viewModel.ballMass < 240f -> "כדור כבד (אימונים)"
                                else -> "כדור כבד במיוחד (מגנטי/ביליארד רוסי)"
                            }
                            Text(
                                text = "מסת הכדור: ${viewModel.ballMass.toInt()} גרם ($massText)",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                        Slider(
                            value = viewModel.ballMass,
                            onValueChange = { viewModel.ballMass = it },
                            valueRange = 100f..300f,
                            colors = SliderDefaults.colors(
                                thumbColor = BallGold,
                                activeTrackColor = BallGold,
                                inactiveTrackColor = BorderDark
                            ),
                            modifier = Modifier.testTag("mass_slider")
                        )
                    }

                    // Orientation Aiming Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BorderDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "הכוון בזמן אמת עפ\"י הטיית המכשיר",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "הטה את הטלפון ימינה/שמאלה כדי לראות את קו הלייזר המנבא זז בהתאם",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (viewModel.isOrientationAimEnabled) {
                                Text(
                                    text = "${String.format("%.1f", viewModel.deviceTiltAngle)}°",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF)
                                )
                            }
                            Switch(
                                checked = viewModel.isOrientationAimEnabled,
                                onCheckedChange = { viewModel.isOrientationAimEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00E5FF),
                                    checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                                    uncheckedThumbColor = Color.Gray,
                                    uncheckedTrackColor = BorderDark
                                ),
                                modifier = Modifier.testTag("orientation_aim_switch")
                            )
                        }
                    }

                    // Squirt (Deflection) Compensation Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BorderDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "פיצוי סטיית כדור (Squirt)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (viewModel.squirtCompensation) 
                                    "מופעל: מסלול הכדור מיושר אוטומטית בהתאם לסיבוב (מומלץ למתחילים)" 
                                else 
                                    "כבוי: הדמיית סטיית מכה אמיתית עקב סיבוב צדי (למתקדמים)" ,
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        
                        Switch(
                            checked = viewModel.squirtCompensation,
                            onCheckedChange = { viewModel.squirtCompensation = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonFelt,
                                checkedTrackColor = NeonFelt.copy(alpha = 0.4f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = BorderDark
                            ),
                            modifier = Modifier.testTag("squirt_compensation_switch")
                        )
                    }

                    // English Sidespin Intensity calibration slider
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "עוצמת אפקט סיבוב (English Intensity): ${String.format("%.1f", viewModel.englishIntensity)}x",
                                fontSize = 11.sp,
                                color = Color.LightGray,
                                fontWeight = FontWeight.Bold
                            )
                            val englishDesc = when {
                                viewModel.englishIntensity < 0.6f -> "סטייה נמוכה (Low Deflection)"
                                viewModel.englishIntensity < 1.4f -> "ספין רגיל (Standard)"
                                viewModel.englishIntensity < 2.0f -> "ספין חזק (High Spin)"
                                else -> "אפקט קיצוני (Maximum Curve)"
                            }
                            Text(
                                text = englishDesc,
                                fontSize = 10.sp,
                                color = NeonFelt
                            )
                        }
                        Slider(
                            value = viewModel.englishIntensity,
                            onValueChange = { viewModel.englishIntensity = it },
                            valueRange = 0.0f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonFelt,
                                activeTrackColor = NeonFelt,
                                inactiveTrackColor = BorderDark
                            ),
                            modifier = Modifier.testTag("english_intensity_slider")
                        )
                    }

                    // Coordinate Grid Overlay Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BorderDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "רשת קואורדינטות (Grid Overlay)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "מציג רשת קווים דקה וערכי מיקום לחישוב מכות דופן (Bank Shots)",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                        
                        Switch(
                            checked = viewModel.showCoordinateGrid,
                            onCheckedChange = { viewModel.showCoordinateGrid = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = BorderDark
                            ),
                            modifier = Modifier.testTag("coordinate_grid_switch")
                        )
                    }

                    // Button to trigger Alignment Help Walkthrough Overlay
                    OutlinedButton(
                        onClick = { viewModel.showAlignmentOverlay = true },
                        border = BorderStroke(1.dp, NeonFelt.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonFelt),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("show_alignment_guide_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "Show Alignment Guide",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("הצג מדריך יישור וכיול מצלמה", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// --- SUB-COMPONENT: SAVE DRILL DIALOG ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveShotDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("מכה קשה לאימון") }
    var notes by remember { mutableStateOf("זווית חיתוך גדולה, דורש סיבוב תחתי") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "שמירת תרגיל לאימון",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "תוכל לשמור את המיקומים הנוכחיים של הכדורים על גבי השולחן כדי לתרגל את הזווית הזו בכל עת.",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
                
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("שם התרגיל / כותרת") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonFelt,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = NeonFelt,
                        unfocusedLabelColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_title_input")
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("הערות או טיפים למכה") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonFelt,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = NeonFelt,
                        unfocusedLabelColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_notes_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onSave(title, notes) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonFelt),
                modifier = Modifier.testTag("confirm_save_btn")
            ) {
                Text("שמור", fontWeight = FontWeight.Bold, color = VelvetBlack)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
            ) {
                Text("ביטול")
            }
        },
        containerColor = PanelDark
    )
}

// --- SUB-COMPONENT: BILLIARDS TABLE CANVAS DRAWING ---
@Composable
fun BilliardsTableCanvas(
    viewModel: BilliardsViewModel,
    showLiveCamera: Boolean,
    modifier: Modifier = Modifier
) {
    var draggingBall by remember { mutableStateOf<String?>(null) }

    Canvas(
        modifier = modifier
            .background(if (showLiveCamera) Color.Transparent else FeltGreen)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Convert touch points using Canvas view boundaries
                        val canvasW = size.width.toFloat()
                        val scale = canvasW / BilliardsPhysics.TABLE_WIDTH
                        
                        val pCue = Offset(viewModel.cuePos.x * scale, viewModel.cuePos.y * scale)
                        val pTarget = Offset(viewModel.targetPos.x * scale, viewModel.targetPos.y * scale)
                        
                        // Generous touch target size (48dp mapped to canvas pixels)
                        val touchRadius = BilliardsPhysics.BALL_RADIUS * scale * 2.8f

                        val distCue = sqrt((offset.x - pCue.x).pow(2) + (offset.y - pCue.y).pow(2))
                        val distTarget = sqrt((offset.x - pTarget.x).pow(2) + (offset.y - pTarget.y).pow(2))

                        draggingBall = when {
                            distCue < touchRadius -> "cue"
                            distTarget < touchRadius -> "target"
                            else -> null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (viewModel.isShotAnimating) return@detectDragGestures
                        
                        val canvasW = size.width.toFloat()
                        val scale = canvasW / BilliardsPhysics.TABLE_WIDTH
                        val scaleRecip = 1f / scale

                        when (draggingBall) {
                            "cue" -> {
                                val newX = (viewModel.cuePos.x + dragAmount.x * scaleRecip)
                                    .coerceIn(BilliardsPhysics.BALL_RADIUS + 30f, BilliardsPhysics.TABLE_WIDTH - BilliardsPhysics.BALL_RADIUS - 30f)
                                val newY = (viewModel.cuePos.y + dragAmount.y * scaleRecip)
                                    .coerceIn(BilliardsPhysics.BALL_RADIUS + 30f, BilliardsPhysics.TABLE_HEIGHT - BilliardsPhysics.BALL_RADIUS - 30f)
                                viewModel.cuePos = PointF(newX, newY)
                                viewModel.resetAnimation()
                            }
                            "target" -> {
                                val newX = (viewModel.targetPos.x + dragAmount.x * scaleRecip)
                                    .coerceIn(BilliardsPhysics.BALL_RADIUS + 30f, BilliardsPhysics.TABLE_WIDTH - BilliardsPhysics.BALL_RADIUS - 30f)
                                val newY = (viewModel.targetPos.y + dragAmount.y * scaleRecip)
                                    .coerceIn(BilliardsPhysics.BALL_RADIUS + 30f, BilliardsPhysics.TABLE_HEIGHT - BilliardsPhysics.BALL_RADIUS - 30f)
                                viewModel.targetPos = PointF(newX, newY)
                                viewModel.resetAnimation()
                            }
                        }
                    },
                    onDragEnd = {
                        draggingBall = null
                    },
                    onDragCancel = {
                        draggingBall = null
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        
        // Physics coordinates are based on 1000 x 500 space.
        // Scale factor translates from physics space to view space.
        val scale = w / BilliardsPhysics.TABLE_WIDTH
        
        // Draw Cushions / Rail boundaries if felt mode
        if (!showLiveCamera) {
            // Draw wooden rim
            drawRect(
                color = Color(0xFF2C1B18),
                size = Size(w, h),
                style = Stroke(width = 16f * scale)
            )
            // Draw inner cushions
            drawRect(
                color = Color(0xFF13362B),
                topLeft = Offset(8f * scale, 8f * scale),
                size = Size(w - 16f * scale, h - 16f * scale),
                style = Stroke(width = 8f * scale)
            )
        }

        // Draw the 6 pockets
        val pocketRadiusPixels = 24f * scale
        val pocketOffsets = BilliardsPhysics.pockets.map { Offset(it.x * scale, it.y * scale) }
        
        for (i in pocketOffsets.indices) {
            val pocketPos = pocketOffsets[i]
            // Draw outer metal ring
            drawCircle(
                color = if (showLiveCamera) Color.Yellow.copy(alpha = 0.5f) else Color.DarkGray,
                radius = pocketRadiusPixels,
                center = pocketPos
            )
            // Draw inner black pocket
            drawCircle(
                color = Color.Black,
                radius = pocketRadiusPixels * 0.85f,
                center = pocketPos
            )
            
            // Hitbox / Tap pocket selection
            // If user taps on pocket in AR or Simulator, select it!
            // Handled dynamically. For visual indicator, we draw indicator ring around selected pocket.
            if (i == viewModel.selectedPocketIndex) {
                // Glow selected target pocket
                drawCircle(
                    color = NeonFelt,
                    radius = pocketRadiusPixels * 1.3f,
                    center = pocketPos,
                    style = Stroke(width = 3f * scale)
                )
                // Draw target crosshair
                drawLine(
                    color = NeonFelt,
                    start = Offset(pocketPos.x - pocketRadiusPixels * 1.6f, pocketPos.y),
                    end = Offset(pocketPos.x + pocketRadiusPixels * 1.6f, pocketPos.y),
                    strokeWidth = 2f * scale
                )
                drawLine(
                    color = NeonFelt,
                    start = Offset(pocketPos.x, pocketPos.y - pocketRadiusPixels * 1.6f),
                    end = Offset(pocketPos.x, pocketPos.y + pocketRadiusPixels * 1.6f),
                    strokeWidth = 2f * scale
                )
            }
        }

        // Pocket selection detection logic (manual distance touch matching on click is also simulated since dragging on pockets is very easy)
        // Draw Table Markings (Baulk line, spot center) if felt mode
        if (!showLiveCamera) {
            // Spot spot point (where object balls are usually racked)
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                radius = 4f * scale,
                center = Offset(BilliardsPhysics.TABLE_WIDTH * 0.75f * scale, BilliardsPhysics.TABLE_HEIGHT * 0.5f * scale)
            )
        }

        // --- COORD GRID OVERLAY (Bank Shot Assistance) ---
        if (viewModel.showCoordinateGrid) {
            // Horizontal lines
            for (yVal in 100..400 step 100) {
                val yPixels = yVal * scale
                drawLine(
                    color = Color(0xFF00E5FF).copy(alpha = 0.22f),
                    start = Offset(0f, yPixels),
                    end = Offset(w, yPixels),
                    strokeWidth = 1f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f))
                )
            }
            // Vertical lines
            for (xVal in 100..900 step 100) {
                val xPixels = xVal * scale
                drawLine(
                    color = Color(0xFF00E5FF).copy(alpha = 0.22f),
                    start = Offset(xPixels, 0f),
                    end = Offset(xPixels, h),
                    strokeWidth = 1f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f))
                )
            }
            
            // Draw coordinate text labels to assist with bank shot calculations
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(125, 0, 229, 255) // Cyan
                textSize = 9.5f * scale
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            }
            
            // Draw labels along top edge
            for (xVal in 200..800 step 200) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$xVal",
                    xVal * scale - (8f * scale),
                    14f * scale,
                    paint
                )
            }
            
            // Draw labels along left edge
            for (yVal in 100..400 step 100) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$yVal",
                    8f * scale,
                    yVal * scale + (3.5f * scale),
                    paint
                )
            }
        }

        // --- MATH CALCULATIONS ---
        val cue = if (viewModel.isShotAnimating) viewModel.animCueBallPos else viewModel.cuePos
        val target = if (viewModel.isShotAnimating) viewModel.animTargetBallPos else viewModel.targetPos
        val selectedPocket = pocketOffsets[viewModel.selectedPocketIndex]

        val ghostBall = BilliardsPhysics.calculateGhostBall(cue, target, BilliardsPhysics.pockets[viewModel.selectedPocketIndex])
        val ghostOffset = Offset(ghostBall.x * scale, ghostBall.y * scale)
        val cueOffset = Offset(cue.x * scale, cue.y * scale)
        val targetOffset = Offset(target.x * scale, target.y * scale)

        val ballRadiusPixels = BilliardsPhysics.BALL_RADIUS * scale

        // --- TRAJECTORY DRAWING ---
        if (!viewModel.isShotAnimating) {
            val trajectories = viewModel.trajectories
            val targetPath = trajectories.first
            val cuePath = trajectories.second

            // Draw line from Cue Ball to Ghost Ball
            drawLine(
                color = Color.White,
                start = cueOffset,
                end = ghostOffset,
                strokeWidth = 2f * scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f))
            )

            // Draw line from Ghost Ball (Impact point) to selected Pocket (Target trajectory)
            if (targetPath.isNotEmpty()) {
                val pathPoints = targetPath.map { Offset(it.x * scale, it.y * scale) }
                for (i in 0 until pathPoints.size - 1) {
                    drawLine(
                        color = NeonFelt,
                        start = pathPoints[i],
                        end = pathPoints[i+1],
                        strokeWidth = 3f * scale
                    )
                }
            }

            // Draw post-collision deflected Tangent Line / curve of Cue Ball
            if (cuePath.size > 15) {
                // Index 15 is where the cue ball hits the ghost ball
                val collisionIndex = 15.coerceAtMost(cuePath.size - 1)
                val postHitPoints = cuePath.subList(collisionIndex, cuePath.size).map { Offset(it.x * scale, it.y * scale) }
                
                for (i in 0 until postHitPoints.size - 1) {
                    drawLine(
                        color = BallGold.copy(alpha = 0.8f),
                        start = postHitPoints[i],
                        end = postHitPoints[i+1],
                        strokeWidth = 2f * scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }
            }

            // Create a 'Ghost Ball' visual aid at the impact position to help the user see exactly where the object ball will be hit
            // 3D sphere translucent backing
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = ballRadiusPixels,
                center = ghostOffset
            )
            // SPECULAR HIGHLIGHT: A glossy white specular highlight on the top-left of the Ghost Ball to give it a polished 3D pool ball look!
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = ballRadiusPixels * 0.35f,
                center = Offset(ghostOffset.x - ballRadiusPixels * 0.35f, ghostOffset.y - ballRadiusPixels * 0.35f)
            )
            // Ghost Ball outline
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = ballRadiusPixels,
                center = ghostOffset,
                style = Stroke(width = 2f * scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            )
            // Center spot for Ghost ball
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = 2.5f * scale,
                center = ghostOffset
            )

            // 1. Point of Impact Indicator: Draw neon circular ring at the exact collision point
            val collisionVector = targetOffset - ghostOffset
            val collisionDist = sqrt(collisionVector.x * collisionVector.x + collisionVector.y * collisionVector.y)
            val contactOffset = if (collisionDist > 0f) {
                ghostOffset + (collisionVector / 2f)
            } else {
                ghostOffset
            }
            drawCircle(
                color = Color.Cyan,
                radius = 7.5f * scale,
                center = contactOffset,
                style = Stroke(width = 2f * scale)
            )
            drawCircle(
                color = Color.Cyan,
                radius = 2f * scale,
                center = contactOffset
            )

            // 2. Real-time Orientation-dependent predictive trajectory path
            if (viewModel.isOrientationAimEnabled) {
                val tiltRad = Math.toRadians(viewModel.deviceTiltAngle.toDouble()).toFloat()
                
                val dirX = targetOffset.x - cueOffset.x
                val dirY = targetOffset.y - cueOffset.y
                val dirLen = sqrt(dirX * dirX + dirY * dirY)
                
                if (dirLen > 0f) {
                    val baseDirX = dirX / dirLen
                    val baseDirY = dirY / dirLen
                    
                    val rotatedDirX = baseDirX * kotlin.math.cos(tiltRad) - baseDirY * kotlin.math.sin(tiltRad)
                    val rotatedDirY = baseDirX * kotlin.math.sin(tiltRad) + baseDirY * kotlin.math.cos(tiltRad)
                    
                    val laserLength = 500f * scale
                    val laserEnd = cueOffset + Offset(rotatedDirX * laserLength, rotatedDirY * laserLength)
                    
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                        start = cueOffset,
                        end = laserEnd,
                        strokeWidth = 3.5f * scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f))
                    )
                    drawLine(
                        color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                        start = cueOffset,
                        end = laserEnd,
                        strokeWidth = 8f * scale
                    )
                    
                    val compassTip = cueOffset + Offset(rotatedDirX * 32f * scale, rotatedDirY * 32f * scale)
                    drawLine(
                        color = Color(0xFF00E5FF),
                        start = cueOffset,
                        end = compassTip,
                        strokeWidth = 2.5f * scale
                    )
                }
            }
            
            // Draw tangent angle icon / line label
            val tangentDir = BilliardsPhysics.calculateCueBallTangentDir(cue, target, BilliardsPhysics.pockets[viewModel.selectedPocketIndex])
            val labelPos = ghostOffset + Offset(tangentDir.x * 40f * scale, tangentDir.y * 40f * scale)
            // Draw little 90-deg angle indicator
            val rawImpact = targetOffset - ghostOffset
            val impactLen = sqrt(rawImpact.x * rawImpact.x + rawImpact.y * rawImpact.y)
            val impactLine = if (impactLen > 0f) Offset(rawImpact.x / impactLen, rawImpact.y / impactLen) else Offset.Zero
            val perpOffset = Offset(-impactLine.y * 15f * scale, impactLine.x * 15f * scale)
        }

        // --- DRAW BALLS ---
        // 1. Cue Ball (White)
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f),
            radius = ballRadiusPixels * 1.1f,
            center = Offset(cueOffset.x + 2f * scale, cueOffset.y + 4f * scale)
        )
        drawCircle(
            color = Color.White,
            radius = ballRadiusPixels,
            center = cueOffset
        )
        // Red dot representing English/Spin on Cue ball
        val dotOffsetX = viewModel.spinX * (ballRadiusPixels * 0.6f)
        val dotOffsetY = -viewModel.spinY * (ballRadiusPixels * 0.6f)
        drawCircle(
            color = TargetRed,
            radius = 2.5f * scale,
            center = Offset(cueOffset.x + dotOffsetX, cueOffset.y + dotOffsetY)
        )

        // 2. Target Ball (Red or Yellow depending on index)
        if (!viewModel.isTargetPocketed) {
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = ballRadiusPixels * 1.1f,
                center = Offset(targetOffset.x + 2f * scale, targetOffset.y + 4f * scale)
            )
            drawCircle(
                color = TargetRed,
                radius = ballRadiusPixels,
                center = targetOffset
            )
            // Yellow stripe to look like a high-end pool ball
            drawCircle(
                color = BallGold,
                radius = ballRadiusPixels * 0.4f,
                center = targetOffset,
                style = Stroke(width = 2.5f * scale)
            )
        }
    }
}

@Composable
fun CameraXView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Transparent))
}

@Composable
fun CameraAlignmentOverlay(
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(enabled = true, onClick = onDismiss) // Click outside to close
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable(enabled = false) {}, // Prevent click propagation
            border = BorderStroke(1.5.dp, NeonFelt.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "מדריך: יישור מצלמה וכיול המקל",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_alignment_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray
                        )
                    }
                }

                Divider(color = BorderDark)

                // Custom Graphic of alignment using a Canvas
                Box(
                    modifier = Modifier
                        .size(width = 240.dp, height = 150.dp)
                        .background(FeltGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cw = size.width
                        val ch = size.height

                        // Draw cue stick (brown line)
                        drawLine(
                            color = Color(0xFF8B5A2B),
                            start = Offset(cw * 0.5f, ch * 0.9f),
                            end = Offset(cw * 0.5f, ch * 0.35f),
                            strokeWidth = 6f
                        )
                        // Cue tip (white/blue)
                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = Offset(cw * 0.5f, ch * 0.35f),
                            end = Offset(cw * 0.5f, ch * 0.32f),
                            strokeWidth = 6f
                        )

                        // Draw cue ball (white)
                        drawCircle(
                            color = Color.White,
                            radius = 12f,
                            center = Offset(cw * 0.5f, ch * 0.25f)
                        )

                        // Draw perfect vertical alignment lens/reticle guidelines (neon blue dashed line)
                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = Offset(cw * 0.5f, 0f),
                            end = Offset(cw * 0.5f, ch),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                        )
                        drawLine(
                            color = Color(0xFF00E5FF),
                            start = Offset(0f, ch * 0.25f),
                            end = Offset(cw, ch * 0.25f),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                        )

                        // Draw green arrow pointing down to represent looking straight down
                        // Reticle circle
                        drawCircle(
                            color = Color(0xFF00E5FF),
                            radius = 25f,
                            center = Offset(cw * 0.5f, ch * 0.25f),
                            style = Stroke(width = 2f)
                        )
                    }
                    
                    // Translucent HUD info overlaying the custom alignment visual
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("מבט מלמעלה (Bird's Eye)", fontSize = 8.sp, color = Color.White)
                    }
                }

                // Step-by-Step Instructions
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    InstructionRow(
                        num = "1",
                        title = "מבט אנכי מושלם",
                        desc = "החזק את המכשיר במקביל לפני השטח של שולחן הביליארד, ישירות מעל הכדור הלבן."
                    )
                    InstructionRow(
                        num = "2",
                        title = "יישור המקל לציר האופטי",
                        desc = "יישר את מקל הביליארד (ה-Cue) לאורך קו ההנחיה המקוקו המרכזי של המצלמה."
                    )
                    InstructionRow(
                        num = "3",
                        title = "הטיה למדידת זווית",
                        desc = "הטיית המכשיר ימינה/שמאלה משנה את זווית קו הלייזר המנבא בזמן אמת עפ\"י חיישני התנועה."
                    )
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonFelt),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("dismiss_alignment_overlay_btn")
                ) {
                    Text("הבנתי, בוא נתחיל!", fontWeight = FontWeight.Bold, color = VelvetBlack)
                }
            }
        }
    }
}

@Composable
fun InstructionRow(num: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(NeonFelt, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = num,
                color = VelvetBlack,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
    }
}

