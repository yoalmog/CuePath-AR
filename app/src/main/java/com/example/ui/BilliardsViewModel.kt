package com.example.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.SavedShot
import com.example.data.ShotRepository
import com.example.physics.BilliardsPhysics
import com.example.physics.PointF
import com.example.physics.TableSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BilliardsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ShotRepository
    var selectedTableSize by mutableStateOf(TableSize.SIZE_8FT)
        private set

    fun updateTableSize(size: TableSize) {
        selectedTableSize = size
        BilliardsPhysics.BALL_RADIUS = size.ballRadius
        friction = size.defaultFriction
        ballMass = size.defaultBallMass
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ShotRepository(database.savedShotDao())
        // Initialize default physics ball radius
        BilliardsPhysics.BALL_RADIUS = selectedTableSize.ballRadius
    }

    // Reactive flow of saved shots
    val savedShots: StateFlow<List<SavedShot>> = repository.allShots
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current screen selection
    var currentTab by mutableStateOf("ar_camera") // "ar_camera", "simulator_2d", "saved_drills", "physics_guide"

    // Table parameters
    var cuePos by mutableStateOf(PointF(250f, 250f))
    var targetPos by mutableStateOf(PointF(700f, 150f))
    var selectedPocketIndex by mutableStateOf(2) // Default: Top Right

    // Cue Speed and Spin
    var spinX by mutableStateOf(0f) // -1f (Left spin) to 1f (Right spin)
    var spinY by mutableStateOf(0f) // -1f (Draw/Back) to 1f (Follow/Top)
    var shotSpeed by mutableStateOf(5f) // 1f to 10f

    // Surface Calibration Parameters
    var friction by mutableStateOf(0.15f) // 0.05f to 0.40f
    var ballMass by mutableStateOf(170f)  // 100g to 300g
    var squirtCompensation by mutableStateOf(true) // Toggle for squirt (cue deflection) compensation
    var englishIntensity by mutableStateOf(1.0f) // 0.0f to 2.5f (English sidespin intensity calibration multiplier)
    var showCoordinateGrid by mutableStateOf(false) // Toggleable faint coordinate grid AR overlay

    // Real-time Phone Orientation Aiming
    var deviceTiltAngle by mutableStateOf(0f) // -45 to +45 degrees roll
    var isOrientationAimEnabled by mutableStateOf(true)

    // Tutorial/Instruction overlays
    var showAlignmentOverlay by mutableStateOf(false)

    // Camera State
    var cameraPermissionGranted by mutableStateOf(false)

    // Derived values for aiming math
    val pocketPos: PointF
        get() = BilliardsPhysics.pockets[selectedPocketIndex]

    val ghostBallPos: PointF
        get() = BilliardsPhysics.calculateGhostBall(cuePos, targetPos, pocketPos)

    val cutAngle: Float
        get() = BilliardsPhysics.calculateCutAngle(cuePos, targetPos, pocketPos)

    val cutDirection: String
        get() = BilliardsPhysics.calculateCutDirection(cuePos, targetPos, pocketPos)

    val aimFraction: String
        get() = BilliardsPhysics.getAimFractionString(cutAngle)

    // Trajectories
    val trajectories: Pair<List<PointF>, List<PointF>>
    get() = BilliardsPhysics.generateTrajectories(
        cue = cuePos,
        target = targetPos,
        pocket = pocketPos,
        spinX = spinX,
        spinY = spinY,
        speed = shotSpeed,
        friction = friction,
        ballMass = ballMass,
        squirtCompensation = squirtCompensation,
        englishIntensity = englishIntensity
    )

    // --- Animation State for Simulator ---
    var isShotAnimating by mutableStateOf(false)
        private set
    var animCueBallPos by mutableStateOf(cuePos)
        private set
    var animTargetBallPos by mutableStateOf(targetPos)
        private set
    var isTargetPocketed by mutableStateOf(false)
        private set

    private var animationJob: Job? = null

    fun fireShot() {
        if (isShotAnimating) return
        animationJob?.cancel()
        
        val targetPath = trajectories.first
        val cuePath = trajectories.second
        
        isShotAnimating = true
        isTargetPocketed = false
        animCueBallPos = cuePos
        animTargetBallPos = targetPos

        animationJob = viewModelScope.launch {
            // Step 1: Cue Ball travels to Ghost Ball (collision point)
            // The first section of cuePath is from cue to ghost (approx 15 points)
            val collisionIndex = 15.coerceAtMost(cuePath.size - 1)
            
            // Animate cue ball moving to collision point
            for (i in 0..collisionIndex) {
                animCueBallPos = cuePath[i]
                delay(20)
            }

            // Step 2: At collision point, both balls start moving
            val targetSteps = targetPath.size
            val cueRemainingSteps = cuePath.size - collisionIndex
            val maxSteps = maxOf(targetSteps, cueRemainingSteps)

            for (step in 0 until maxSteps) {
                // Move Target Ball
                if (step < targetSteps) {
                    animTargetBallPos = targetPath[step]
                }
                // Move Cue Ball along the deflected tangent line
                if (collisionIndex + step < cuePath.size) {
                    animCueBallPos = cuePath[collisionIndex + step]
                }
                
                // If target ball is very close to the pocket, simulate pocketing (make it disappear)
                if (step >= targetSteps - 3) {
                    isTargetPocketed = true
                }
                delay(20)
            }
            
            delay(1000)
            resetAnimation()
        }
    }

    fun resetAnimation() {
        animationJob?.cancel()
        isShotAnimating = false
        isTargetPocketed = false
        animCueBallPos = cuePos
        animTargetBallPos = targetPos
    }

    // --- Database Operations ---
    fun saveCurrentShot(title: String, notes: String) {
        viewModelScope.launch {
            val shot = SavedShot(
                title = title,
                notes = notes,
                cueX = cuePos.x / BilliardsPhysics.TABLE_WIDTH,
                cueY = cuePos.y / BilliardsPhysics.TABLE_HEIGHT,
                targetX = targetPos.x / BilliardsPhysics.TABLE_WIDTH,
                targetY = targetPos.y / BilliardsPhysics.TABLE_HEIGHT,
                pocketId = selectedPocketIndex,
                englishSpinX = spinX,
                englishSpinY = spinY,
                shotSpeed = shotSpeed
            )
            repository.insert(shot)
        }
    }

    fun loadShot(shot: SavedShot) {
        cuePos = PointF(shot.cueX * BilliardsPhysics.TABLE_WIDTH, shot.cueY * BilliardsPhysics.TABLE_HEIGHT)
        targetPos = PointF(shot.targetX * BilliardsPhysics.TABLE_WIDTH, shot.targetY * BilliardsPhysics.TABLE_HEIGHT)
        selectedPocketIndex = shot.pocketId
        spinX = shot.englishSpinX
        spinY = shot.englishSpinY
        shotSpeed = shot.shotSpeed
        resetAnimation()
        currentTab = "simulator_2d" // Navigate to simulator to see it!
    }

    fun deleteShot(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }
}
