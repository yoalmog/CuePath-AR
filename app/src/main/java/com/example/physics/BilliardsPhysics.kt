package com.example.physics

import kotlin.math.*

data class PointF(val x: Float, val y: Float) {
    operator fun plus(other: PointF) = PointF(x + other.x, y + other.y)
    operator fun minus(other: PointF) = PointF(x - other.x, y - other.y)
    operator fun times(factor: Float) = PointF(x * factor, y * factor)
    operator fun div(factor: Float) = PointF(x / factor, y / factor)

    fun distance(other: PointF): Float = sqrt((x - other.x).pow(2) + (y - other.y).pow(2))
    fun length(): Float = sqrt(x * x + y * y)
    fun normalize(): PointF {
        val len = length()
        return if (len > 0f) this / len else PointF(0f, 0f)
    }
}

enum class TableSize(
    val displayName: String,
    val ballRadius: Float,
    val defaultFriction: Float,
    val defaultBallMass: Float,
    val description: String
) {
    SIZE_7FT("שולחן 7 פיט (7ft Bar Box)", 16.5f, 0.22f, 160f, "שולחן פאבים קומפקטי. לבד איטי יחסית, כדורים גדולים ביחס לשטח וכיוון צפוף יותר."),
    SIZE_8FT("שולחן 8 פיט (8ft Standard)", 15.0f, 0.16f, 170f, "שולחן מועדונים סטנדרטי. איזון מושלם בין גודל הכדורים למהירות המשטח."),
    SIZE_9FT("שולחן 9 פיט (9ft Pro Tournament)", 13.5f, 0.10f, 180f, "שולחן מקצועי גדול. לבד מהיר במיוחד (Worsted Cloth), כדורים קטנים יחסית המאפשרים כוונון עדין ומדויק.")
}

object BilliardsPhysics {
    // Standard table size for 2D calculations
    const val TABLE_WIDTH = 1000f
    const val TABLE_HEIGHT = 500f
    var BALL_RADIUS = 15f

    // Pockets positions
    val pockets = listOf(
        PointF(20f, 20f),            // Top Left
        PointF(TABLE_WIDTH / 2, 10f), // Top Center
        PointF(TABLE_WIDTH - 20f, 20f), // Top Right
        PointF(20f, TABLE_HEIGHT - 20f), // Bottom Left
        PointF(TABLE_WIDTH / 2, TABLE_HEIGHT - 10f), // Bottom Center
        PointF(TABLE_WIDTH - 20f, TABLE_HEIGHT - 20f) // Bottom Right
    )

    // Calculate ghost ball contact point
    // Cue ball must arrive at this point G to send target ball T to pocket P
    fun calculateGhostBall(cue: PointF, target: PointF, pocket: PointF): PointF {
        val dir = (target - pocket).normalize()
        // Ghost ball center is 2 * radius away from target center on the line from pocket to target
        return target + (dir * (BALL_RADIUS * 2f))
    }

    // Calculate cut angle in degrees
    fun calculateCutAngle(cue: PointF, target: PointF, pocket: PointF): Float {
        val ghost = calculateGhostBall(cue, target, pocket)
        val cueToGhost = (ghost - cue).normalize()
        val ghostToTarget = (target - ghost).normalize()

        // Dot product to find angle between direction of cue-to-ghost and direction of ghost-to-target
        val dot = cueToGhost.x * ghostToTarget.x + cueToGhost.y * ghostToTarget.y
        val clampedDot = dot.coerceIn(-1f, 1f)
        val angleRad = acos(clampedDot)
        var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // Return angle
        return if (angleDeg.isNaN()) 0f else angleDeg
    }

    // Determine if the cut is to the left or right
    fun calculateCutDirection(cue: PointF, target: PointF, pocket: PointF): String {
        val ghost = calculateGhostBall(cue, target, pocket)
        val cueToGhost = ghost - cue
        val ghostToTarget = target - ghost

        // Cross product in 2D
        val cross = cueToGhost.x * ghostToTarget.y - cueToGhost.y * ghostToTarget.x
        return if (cross > 0) "ימין" else "שמאל"
    }

    // Calculate aim fraction for players (e.g. "Full", "3/4 Ball", "1/2 Ball", "1/4 Ball", "Thin")
    fun getAimFractionString(cutAngle: Float): String {
        val absAngle = abs(cutAngle)
        return when {
            absAngle < 5f -> "מלא (Full Ball)"
            absAngle < 20f -> "3/4 כדור (3/4 Ball)"
            absAngle < 38f -> "חצי כדור (1/2 Ball)"
            absAngle < 53f -> "רבע כדור (1/4 Ball)"
            absAngle < 75f -> "דק מאוד (Thin Cut)"
            else -> "קשה במיוחד / בלתי אפשרי"
        }
    }

    // Calculate the cue ball tangent line path after hitting the target ball
    // It is perpendicular (90 degrees) to the line of impact (ghost to target)
    fun calculateCueBallTangentDir(cue: PointF, target: PointF, pocket: PointF): PointF {
        val ghost = calculateGhostBall(cue, target, pocket)
        val cueToGhost = (ghost - cue).normalize()
        val impactLine = (target - ghost).normalize()

        // Tangent line is perpendicular to the impact line
        // We project the cue's incoming vector onto the perpendicular of the impact line
        val perp1 = PointF(-impactLine.y, impactLine.x)
        val perp2 = PointF(impactLine.y, -impactLine.x)

        // Choose the perpendicular that goes in the same general direction as incoming cue ball
        val dot1 = cueToGhost.x * perp1.x + cueToGhost.y * perp1.y
        val dot2 = cueToGhost.x * perp2.x + cueToGhost.y * perp2.y

        return if (dot1 > dot2) perp1 else perp2
    }

    // Calculate the cue ball tangent line path after hitting the target ball, using a custom ghost ball position
    fun calculateCueBallTangentDirWithGhost(cue: PointF, target: PointF, ghost: PointF): PointF {
        val cueToGhost = (ghost - cue).normalize()
        val impactLine = (target - ghost).normalize()

        // Tangent line is perpendicular to the impact line
        // We project the cue's incoming vector onto the perpendicular of the impact line
        val perp1 = PointF(-impactLine.y, impactLine.x)
        val perp2 = PointF(impactLine.y, -impactLine.x)

        // Choose the perpendicular that goes in the same general direction as incoming cue ball
        val dot1 = cueToGhost.x * perp1.x + cueToGhost.y * perp1.y
        val dot2 = cueToGhost.x * perp2.x + cueToGhost.y * perp2.y

        return if (dot1 > dot2) perp1 else perp2
    }

    // Physics path generation for previewing shots
    // Returns list of points for Target Ball and Cue Ball trajectories
    fun generateTrajectories(
        cue: PointF,
        target: PointF,
        pocket: PointF,
        spinX: Float, // -1 to 1 (left to right spin)
        spinY: Float, // -1 to 1 (backspin/draw to topspin/follow)
        speed: Float,  // 1 to 10
        friction: Float = 0.15f,
        ballMass: Float = 170f,
        squirtCompensation: Boolean = true,
        englishIntensity: Float = 1.0f,
        minX: Float = 20f,
        maxX: Float = TABLE_WIDTH - 20f,
        minY: Float = 20f,
        maxY: Float = TABLE_HEIGHT - 20f,
        onBounce: (PointF) -> Unit = {}
    ): Pair<List<PointF>, List<PointF>> {
        val baseGhost = calculateGhostBall(cue, target, pocket)

        // Squirt (cue deflection) calculation
        val ghost = if (!squirtCompensation && abs(spinX) > 0.01f) {
            val dir = (baseGhost - cue).normalize()
            val perp = PointF(-dir.y, dir.x)
            // Left spin (spinX < 0) squirts right, right spin (spinX > 0) squirts left
            val cueToGhostDist = cue.distance(baseGhost)
            // Scaling deflection based on distance, speed, and spinX. Max deflection is ~8% of the distance.
            val deflectionDistance = -spinX * englishIntensity * 0.04f * cueToGhostDist * (speed / 5f)
            PointF(baseGhost.x + perp.x * deflectionDistance, baseGhost.y + perp.y * deflectionDistance)
        } else {
            baseGhost
        }
        
        // --- Target Ball Path ---
        // Target ball starts at T and rolls in the line of impact (from ghost to target)
        val targetPath = mutableListOf<PointF>()
        targetPath.add(target)
        
        val dirToPocket = (target - ghost).normalize()
        val targetDist = target.distance(pocket)
        val steps = 30
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            targetPath.add(target + (dirToPocket * (targetDist * fraction)))
        }

        // --- Cue Ball Path ---
        val cuePath = mutableListOf<PointF>()
        // 1. Path from cue to ghost ball
        cuePath.add(cue)
        val cueToGhostDist = cue.distance(ghost)
        val approachSteps = 15
        for (i in 1..approachSteps) {
            val fraction = i.toFloat() / approachSteps
            val progressPoint = cue + ((ghost - cue).normalize() * (cueToGhostDist * fraction))
            cuePath.add(progressPoint)
        }

        // 2. Post-collision tangent line deflected by spin
        var tangentDir = calculateCueBallTangentDirWithGhost(cue, target, ghost)
        val impactLine = (target - ghost).normalize() // Line from ghost to target

        // Adjust path length based on friction and mass
        // More friction = shorter paths. Higher mass = retains momentum longer
        val baseFrictionFactor = 0.15f / friction.coerceAtLeast(0.01f)
        val massFactor = ballMass / 170f
        val pathLength = (150f + (speed * 15f)) * baseFrictionFactor * massFactor
        
        val postCollisionSteps = 40
        var currentPoint = ghost
        val stepLength = pathLength / postCollisionSteps
        var velocity = tangentDir * stepLength

        // Topspin bends the path forward (towards impact line), draw bends it backward (away from impact line)
        // Sidespin causes minor horizontal curvature
        // Friction accelerates the grip of spin (earlier curvature), while higher mass resists bending (inertia)
        for (i in 1..postCollisionSteps) {
            val t = i.toFloat() / postCollisionSteps
            
            // Curve deflection factor: grows with step/distance
            val curveIntensity = spinY * stepLength * 0.18f * (friction / 0.15f) * (170f / ballMass)
            val spinXDeflection = spinX * englishIntensity * stepLength * 0.08f * (friction / 0.15f) * (170f / ballMass)
            
            // Apply curved deflection to velocity direction
            val perpDir = PointF(-velocity.y, velocity.x).normalize()
            velocity = velocity + (impactLine * curveIntensity) + (perpDir * spinXDeflection)
            velocity = velocity.normalize() * stepLength
            
            val nextPoint = currentPoint + velocity
            
            // Bouncing logic with the calibrated boundaries
            var finalX = nextPoint.x
            var finalY = nextPoint.y
            var didBounce = false
            
            val limitMinX = minX + BALL_RADIUS
            val limitMaxX = maxX - BALL_RADIUS
            val limitMinY = minY + BALL_RADIUS
            val limitMaxY = maxY - BALL_RADIUS
            
            if (finalX <= limitMinX) {
                finalX = limitMinX
                velocity = PointF(-velocity.x, velocity.y)
                didBounce = true
            } else if (finalX >= limitMaxX) {
                finalX = limitMaxX
                velocity = PointF(-velocity.x, velocity.y)
                didBounce = true
            }
            
            if (finalY <= limitMinY) {
                finalY = limitMinY
                velocity = PointF(velocity.x, -velocity.y)
                didBounce = true
            } else if (finalY >= limitMaxY) {
                finalY = limitMaxY
                velocity = PointF(velocity.x, -velocity.y)
                didBounce = true
            }
            
            currentPoint = PointF(finalX, finalY)
            cuePath.add(currentPoint)
            
            if (didBounce) {
                onBounce(currentPoint)
            }
        }

        return Pair(targetPath, cuePath)
    }
}
