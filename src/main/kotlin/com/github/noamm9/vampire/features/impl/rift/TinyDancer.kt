package com.github.noamm9.vampire.features.impl.rift

import com.github.noamm9.compat.KEYBOARD_TYPE
import com.github.noamm9.compat.isKeyDown
import com.github.noamm9.config.types.RangeSliderSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.utils.Animation.Companion.easeInOutCubic
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils.lerp
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dances the Mirrorverse Dance Room (sixth puzzle) for you.
 *
 * Every order arrives as a subtitle on a beat, and the server checks it on the next beat 20 ticks later. Each order
 * means "step to the next block of the square", plus a pose for the check: sneaking (Sneak!) or not (Stand!),
 * mid-air (Jump!) or on the ground (Don't jump!), and Punch! wants a left click shortly before the lone
 * "Punch!" subtitle that shows on the check beat. That subtitle is not an order of its own.
 */
object TinyDancer: Feature(
    description = "Dances the Mirrorverse Dance Room for you.",
    name = "Auto Dancer",
    toggled = false
) {
    private val ping by SliderSetting("Ping", 100, 0, 300, 5, " ms")
    private val fullDance by ToggleSetting("Full Dance", true)
        .withDescription("Keeps dancing until you get Tiny Dancer.")
    private val earlyRelease by SliderSetting("Early Release", 0.3, 0.0, 0.5, 0.05, " blocks")
        .withDescription("Lets go of the movement keys this much earlier, so you slide onto the block instead of overshooting and walking back.")
    private val landingSpread by SliderSetting("Landing Spread", 0.2, 0.0, 0.3, 0.05, " blocks")
        .withDescription("Aims up to this far off the block center, randomly, so you don't land on the exact middle every time.")
    private val startDelay by RangeSliderSetting("Delay", 150 to 450, 0, 600, 50, " ms")
        .withDescription("Waits a random time in this range after each order before walking, like a player's reaction time.")
    private val delayFailsafe by ToggleSetting("Delay Failsafe", false)
        .withDescription("Cuts the delay short when it would be too late to reach the block (and stand still before a jump) in time for the check.")
    private val punchTiming by RangeSliderSetting("Punch Timing", 0 to 50, -100, 150, 10, " ms")
        .withDescription("When your punch should reach the server, relative to the check beat (the \"Punch!\" subtitle). Your ping is taken into account, so this works for any ping. Picked at random in this range.")

    /** Centers of the four dance blocks, in the order they are danced. The first one is where the dance starts. */
    private val path = listOf(
        Vec3(- 264.5, 33.0, - 107.5),
        Vec3(- 264.5, 33.0, - 105.5),
        Vec3(- 262.5, 33.0, - 105.5),
        Vec3(- 262.5, 33.0, - 107.5)
    )
    /** The room counts as entered within this distance of the first block. */
    private const val ROOM_RADIUS = 12.0
    /** Time (ms) for the one turn at the start, towards the dance floor. */
    private const val ROTATION_TIME = 500.0
    /** Ticks from an order to the beat that checks it. */
    private const val CHECK_TICKS = 20
    /** A jump keeps you in the air for about 12 ticks; jumping this many ticks after the order lands the check mid-jump. */
    private const val JUMP_TICKS = 14
    /** After letting go of a key on the ground you keep sliding about 1.2x your current speed (friction 0.546). */
    private const val SLIDE = 1.2
    /** About how many ticks walking one block and settling takes; the start delay leaves this much time before the check. */
    private const val WALK_TICKS = 7
    /** Ticks to stop sliding after reaching the block, before a jump. */
    private const val SETTLE_TICKS = 2
    /** Slower than this (blocks per tick) you're standing still enough to jump straight up. */
    private const val SETTLED_SPEED = 0.05
    /** How far off the block center is close enough. */
    private const val TOLERANCE = 0.2
    /** Further than this from the center you are at most 0.1 inside the block, so keep walking whatever the slide estimate says. */
    private const val INSIDE = 0.4

    private class Order(val tick: Long, val sneak: Boolean, val jump: Boolean, val punch: Boolean, val punchAt: Long) {
        var jumped = false
        var punched = false
    }

    private var ticks = 0L
    private var inRoom = false
    /** Set once the dance is completed, until you leave the room. */
    private var finished = false
    /** True once the start turn is done; the player isn't turned again after that. */
    private var rotated = false
    private var rotationJob: Job? = null
    private var target: Vec3? = null
    /** Center of the block [target] is on. */
    private var targetCenter: Vec3? = null
    /** Tick from which [target] is walked to; before it the movement keys stay released. */
    private var walkTick = 0L
    private var order: Order? = null
    private var sneaking = false
    private var jumpTicks = 0

    private val movementKeys get() = with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight, keyShift, keyJump) }
    private val pingTicks get() = (ping.value / 50.0).roundToInt()
    /** Earliest tick after an order to jump so you're still in the air at the check. */
    private val jumpFrom get() = (JUMP_TICKS - pingTicks).coerceAtLeast(1)

    override fun init() {
        register<WorldChangeEvent> { leave() }

        register<TickEvent.Start> {
            ticks ++
            if (mc.player == null || mc.level == null) return@register
            val near = LocationUtils.world == WorldType.Rift && player.position().distanceTo(path[0]) <= ROOM_RADIUS
            if (near && ! inRoom) enter()
            else if (! near && inRoom) leave()
            if (! inRoom || finished || ! rotated) return@register

            order?.let(::runOrder)
            // A key held in the air carries you on past the block, so only steer on the ground.
            if (ticks >= walkTick && player.onGround() && jumpTicks == 0) target?.let(::steer)
            else with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight).forEach { it.isDown = false } }
            mc.options.keyShift.isDown = sneaking
            mc.options.keyJump.isDown = jumpTicks > 0
            if (jumpTicks > 0) jumpTicks --
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val packet = event.packet as? ClientboundSetSubtitleTextPacket ?: return@register
            if (inRoom && rotated && ! finished) onOrder(packet.text.unformattedText.trim())
        }

        register<ChatMessageEvent> {
            if (! inRoom || finished) return@register
            val text = event.unformattedText.trim()
            when {
                text.startsWith("You completed the full dance!") -> finish("&aFull dance done, enjoy the Tiny Dancer!")
                text.startsWith("You completed the dance!") && ! fullDance.value -> finish("&aDance done, the hole is open.")
                text.startsWith("You weren't") -> debug("&cfailed: $text")
            }
        }
    }

    private fun enter() {
        inRoom = true
        finished = false
        rotated = false
        order = null
        targetCenter = path[0]
        target = jitter(path[0])
        walkTick = 0
        debug("entered the Dance Room, turning to the floor")
        rotationJob = scope.launch {
            rotateTo(Random.nextDouble(- 3.0, 3.0).toFloat(), Random.nextDouble(35.0, 50.0).toFloat())
            rotated = true
        }
    }

    private fun leave() {
        if (! inRoom) return
        inRoom = false
        rotationJob?.cancel()
        stopDancing()
    }

    private fun finish(message: String) {
        finished = true
        stopDancing()
        ChatUtils.modMessage("&7[Rift] Tiny Dancer: $message")
    }

    private fun stopDancing() {
        order = null
        target = null
        targetCenter = null
        sneaking = false
        jumpTicks = 0
        movementKeys.forEach { it.isDown = isPhysicallyDown(it) }
    }

    /** Parses an order like "Sneak! and Jump! and Punch!" and starts the step to the next block. */
    private fun onOrder(text: String) {
        val parts = text.split(" and ").map { it.trim().lowercase() }
        val known = setOf("move!", "sneak!", "stand!", "jump!", "don't jump!", "punch!")
        if (parts.none { it in known }) return
        parts.filter { it !in known }.forEach { ChatUtils.modMessage("&7[Rift] Tiny Dancer: &eunknown order \"$it\"") }

        // A lone "Punch!" shows on the check beat of a punch order. It isn't an order; punch now if that hasn't happened yet.
        if (parts == listOf("punch!")) {
            val current = order
            if (current != null && current.punch && ! current.punched) {
                current.punched = true
                punch("+${ticks - current.tick}t punch on the \"Punch!\" subtitle (planned for +${current.punchAt}t)")
            }
            return
        }

        val center = path[(nearestBlock() + 1) % path.size]
        val next = jitter(center)
        targetCenter = center
        target = next
        // Steering runs once a tick, so the start delay is rounded to whole ticks (50 ms). The failsafe caps it so you
        // still land and sneak before the last moment an action reaches the server in time for the check.
        // A jump order also needs you standing still on the block before the jump, so the walk has to start earlier.
        val jump = "jump!" in parts
        val walkBy = if (jump) jumpFrom - WALK_TICKS - SETTLE_TICKS else CHECK_TICKS - pingTicks - 2 - WALK_TICKS
        val randomTicks = (startDelay.random() / 50.0).roundToInt()
        val delayTicks = if (delayFailsafe.value) randomTicks.coerceAtMost(walkBy.coerceAtLeast(0)) else randomTicks
        walkTick = ticks + delayTicks
        // Sneaking walks at 30% speed, so let go of sneak to walk and sneak again once on the block.
        sneaking = false
        // The check beat, and with it the "Punch!" subtitle, comes CHECK_TICKS after the order. The order reached you
        // half a ping late and the punch needs another half to reach the server, so send it a whole ping early.
        val punchAt = (CHECK_TICKS - pingTicks + (punchTiming.random() / 50.0).roundToInt()).toLong()
        order = Order(ticks, sneak = "sneak!" in parts, jump = jump, punch = "punch!" in parts, punchAt = punchAt)
        debug("order &e$text&7, walking to ${"%.2f".format(next.x)} ${"%.2f".format(next.z)} in ${delayTicks}t")
    }

    private fun runOrder(order: Order) {
        val elapsed = ticks - order.tick
        val arrived = target == null || inLandingZone()
        // Last tick an action can be sent and still reach the server before the check.
        val lastChance = CHECK_TICKS - pingTicks - 2

        if (order.sneak && ! sneaking && (arrived || elapsed >= lastChance)) {
            sneaking = true
            debug("+${elapsed}t sneak (on block: $arrived)")
        }
        if (order.punch && ! order.punched && elapsed >= order.punchAt) {
            order.punched = true
            punch("+${elapsed}t punch (on block: $arrived)")
        }

        if (elapsed == (CHECK_TICKS - pingTicks).toLong()) {
            val block = player.blockPosition()
            val onTarget = targetCenter?.let { block.x == Mth.floor(it.x) && block.z == Mth.floor(it.z) } ?: false
            debug("+${elapsed}t check: at ${"%.2f".format(player.x)} ${"%.2f".format(player.z)}, on target block: $onTarget, " +
                "sneaking: ${player.isShiftKeyDown}, in air: ${! player.onGround()}")
        }

        // Jumping while still walking carried you over the block, so first stand still on it like a player would.
        // From the earliest jump tick wait for that, up to the last tick a jump still has you in the air at the check.
        val settled = arrived && player.deltaMovement.horizontalDistance() < SETTLED_SPEED
        if (order.jump && ! order.jumped && player.onGround() && elapsed >= jumpFrom && (settled || elapsed >= lastChance)) {
            order.jumped = true
            jumpTicks = 2
            with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight).forEach { it.isDown = false } }
            debug("+${elapsed}t jump (standing still on block: $settled)")
        }

        if (elapsed > CHECK_TICKS + 5) this.order = null
    }

    private fun punch(message: String) {
        val blocker = when {
            player.isUsingItem -> "using an item"
            player.isHandsBusy -> "hands busy"
            mc.gui.screen() != null -> "screen open"
            else -> null
        }
        // A swing that hit nothing in the last 10 ticks would make Minecraft ignore this click.
        mc.missTime = 0
        PlayerUtils.leftClick()
        debug(message + (blocker?.let { " &c(may be dropped: $it)" } ?: ""))
    }

    /** Holds the movement keys that bring you to [target], letting go early enough to slide onto its center. */
    private fun steer(target: Vec3) {
        val pos = player.position()
        // Anywhere inside the random landing zone is good enough: let go and never counter steer, or you flicker on the block.
        if (inLandingZone()) {
            with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight).forEach { it.isDown = false } }
            return
        }
        val dx = target.x - pos.x
        val dz = target.z - pos.z
        val yaw = Math.toRadians(player.yRot.toDouble())
        val forwardX = - sin(yaw)
        val forwardZ = cos(yaw)
        val leftX = cos(yaw)
        val leftZ = sin(yaw)
        val velocity = player.deltaMovement
        val center = targetCenter ?: target
        val cx = center.x - pos.x
        val cz = center.z - pos.z

        setAxis(mc.options.keyUp, mc.options.keyDown, dx * forwardX + dz * forwardZ, velocity.x * forwardX + velocity.z * forwardZ, cx * forwardX + cz * forwardZ)
        setAxis(mc.options.keyLeft, mc.options.keyRight, dx * leftX + dz * leftZ, velocity.x * leftX + velocity.z * leftZ, cx * leftX + cz * leftZ)
    }

    /** [distance] is to the (spread) target, [toCenter] to the center of its block, both along this axis. */
    private fun setAxis(positive: KeyMapping, negative: KeyMapping, distance: Double, speed: Double, toCenter: Double) {
        // Early release only applies in the direction you're already moving, so it never pushes you away from the block.
        val lead = if (speed * distance > 0) earlyRelease.value * Math.signum(distance) else 0.0
        val remaining = distance - speed * SLIDE - lead
        // Letting go on the slide estimate alone stopped you a few hundredths short of the block (z -106.05 for -106),
        // so near the edge keep walking until you're properly inside it.
        val outside = abs(toCenter) > INSIDE
        // Only ever press towards the target. If the slide would carry you past it, let go and coast instead of braking.
        positive.isDown = distance > 0 && (remaining > TOLERANCE || (outside && toCenter > 0))
        negative.isDown = distance < 0 && (remaining < - TOLERANCE || (outside && toCenter < 0))
    }

    /** True while you're within [landingSpread] of the target block's center, on both axes. */
    private fun inLandingZone(): Boolean {
        val center = targetCenter ?: return false
        val pos = player.position()
        val zone = maxOf(landingSpread.value, TOLERANCE)
        return abs(pos.x - center.x) <= zone && abs(pos.z - center.z) <= zone
    }

    /** A random spot within [landingSpread] of the block center [center]. */
    private fun jitter(center: Vec3): Vec3 {
        val spread = landingSpread.value
        if (spread <= 0) return center
        return center.add(Random.nextDouble(- spread, spread), 0.0, Random.nextDouble(- spread, spread))
    }

    private fun nearestBlock(): Int {
        val pos = player.position()
        return path.indices.minBy { Vec3(path[it].x - pos.x, 0.0, path[it].z - pos.z).length() }
    }

    /** Eases the camera to [yaw]/[pitch] the short way round. */
    private suspend fun rotateTo(yaw: Float, pitch: Float) {
        val startYaw = player.yRot
        val endYaw = startYaw + Mth.wrapDegrees(yaw - startYaw)
        val startPitch = player.xRot
        val duration = ROTATION_TIME

        val startTime = System.currentTimeMillis()
        while (true) {
            val progress = min((System.currentTimeMillis() - startTime) / duration, 1.0)
            val eased = easeInOutCubic(progress).toFloat()
            PlayerUtils.rotate(lerp(startYaw, endYaw, eased).toFloat(), lerp(startPitch, pitch, eased).toFloat())
            if (progress >= 1) break
            delay(1)
        }
    }

    private fun isPhysicallyDown(key: KeyMapping): Boolean {
        val bound = KeyMappingHelper.getBoundKeyOf(key)
        return bound.type == KEYBOARD_TYPE && isKeyDown(bound.value)
    }

    private fun debug(message: String) {
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Tiny Dancer: &7$message")
    }

    override fun onDisable() {
        super.onDisable()
        rotationJob?.cancel()
        // Also called while features load, before the game options exist, so only touch the keys while dancing.
        if (inRoom) stopDancing()
        inRoom = false
    }
}
