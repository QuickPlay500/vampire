package com.github.noamm9.vampire.features.impl.vampire

import com.github.noamm9.compat.KEYBOARD_TYPE
import com.github.noamm9.compat.isKeyDown
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
 * mid-air (Jump!) or on the ground (Don't jump!), and Punch! wants a left click.
 */
object TinyDancer: Feature(
    description = "Dances the Mirrorverse Dance Room for you: walks the square and follows every order, to earn the Tiny Dancer.",
    name = "Tiny Dancer",
    toggled = false
) {
    private val ping by SliderSetting("Ping", 100, 0, 300, 5, " ms")
        .withDescription("Your ping. Sneak, jump and punch happen this much earlier, so the server sees them in time for the check.")
    private val fullDance by ToggleSetting("Full Dance", true)
        .withDescription("Keeps dancing after the hole opens, until the full dance and the Tiny Dancer. Off stops at the first \"You completed the dance!\".")
    private val rotationTime by SliderSetting("Rotation Time", 400, 0, 1000, 10, " ms")
        .withDescription("Time (ms) for the one turn at the start, towards the dance floor.")
    private val earlyRelease by SliderSetting("Early Release", 0.15, 0.0, 0.5, 0.05, " blocks")
        .withDescription("Lets go of the movement keys this much earlier, so you slide onto the block instead of overshooting and walking back.")
    private val landingSpread by SliderSetting("Landing Spread", 0.2, 0.0, 0.3, 0.05, " blocks")
        .withDescription("Aims up to this far off the block center, randomly, so you don't land on the exact middle every time.")
    private val minStartDelay by SliderSetting("Min Start Delay", 150, 0, 600, 50, " ms")
        .withDescription("Waits at least this long after each order before walking, like a player's reaction time.")
    private val startDelay by SliderSetting("Max Start Delay", 450, 0, 600, 50, " ms")
        .withDescription("Waits at most this long after each order before walking. Cut short when needed so you still reach the block before the check.")

    /** Centers of the four dance blocks, in the order they are danced. The first one is where the dance starts. */
    private val path = listOf(
        Vec3(- 264.5, 33.0, - 107.5),
        Vec3(- 264.5, 33.0, - 105.5),
        Vec3(- 262.5, 33.0, - 105.5),
        Vec3(- 262.5, 33.0, - 107.5)
    )
    /** The room counts as entered within this distance of the first block. */
    private const val ROOM_RADIUS = 12.0
    /** Ticks from an order to the beat that checks it. */
    private const val CHECK_TICKS = 20
    /** A jump keeps you in the air for about 12 ticks; jumping this many ticks after the order lands the check mid-jump. */
    private const val JUMP_TICKS = 14
    /** After letting go of a key on the ground you keep sliding about 1.2x your current speed (friction 0.546). */
    private const val SLIDE = 1.2
    /** About how many ticks walking one block and settling takes; the start delay leaves this much time before the check. */
    private const val WALK_TICKS = 7
    /** How far off the block center is close enough. */
    private const val TOLERANCE = 0.15

    private class Order(val tick: Long, val sneak: Boolean, val jump: Boolean, val punch: Boolean) {
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
            if (ticks >= walkTick) target?.let(::steer)
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
        ChatUtils.modMessage("&7[Vampire] Tiny Dancer: $message")
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
        parts.filter { it !in known }.forEach { ChatUtils.modMessage("&7[Vampire] Tiny Dancer: &eunknown order \"$it\"") }

        val center = path[(nearestBlock() + 1) % path.size]
        val next = jitter(center)
        targetCenter = center
        target = next
        // Steering runs once a tick, so the start delay is rounded to whole ticks (50 ms). It's capped so you still
        // land and sneak before the last moment an action reaches the server in time for the check.
        val minDelay = min(minStartDelay.value, startDelay.value)
        val maxDelayTicks = (CHECK_TICKS - pingTicks - 2 - WALK_TICKS).coerceAtLeast(0)
        val delayTicks = (Random.nextInt(minDelay, startDelay.value + 1) / 50.0).roundToInt().coerceAtMost(maxDelayTicks)
        walkTick = ticks + delayTicks
        // Sneaking walks at 30% speed, so let go of sneak to walk and sneak again once on the block.
        sneaking = false
        order = Order(ticks, sneak = "sneak!" in parts, jump = "jump!" in parts, punch = "punch!" in parts)
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

        if (order.punch && ! order.punched && (arrived || elapsed >= lastChance)) {
            order.punched = true
            // A swing that hit nothing in the last 10 ticks would make Minecraft ignore this click.
            mc.missTime = 0
            PlayerUtils.leftClick()
            debug("+${elapsed}t punch")
        }

        if (order.jump && ! order.jumped && elapsed >= (JUMP_TICKS - pingTicks).coerceAtLeast(1) && player.onGround()) {
            order.jumped = true
            jumpTicks = 2
            debug("+${elapsed}t jump (on block: $arrived)")
        }

        if (elapsed > CHECK_TICKS + 5) this.order = null
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

        setAxis(mc.options.keyUp, mc.options.keyDown, dx * forwardX + dz * forwardZ, velocity.x * forwardX + velocity.z * forwardZ)
        setAxis(mc.options.keyLeft, mc.options.keyRight, dx * leftX + dz * leftZ, velocity.x * leftX + velocity.z * leftZ)
    }

    private fun setAxis(positive: KeyMapping, negative: KeyMapping, distance: Double, speed: Double) {
        // Early release only applies in the direction you're already moving, so it never pushes you away from the block.
        val lead = if (speed * distance > 0) earlyRelease.value * Math.signum(distance) else 0.0
        val remaining = distance - speed * SLIDE - lead
        // Only ever press towards the target. If the slide would carry you past it, let go and coast instead of braking.
        positive.isDown = distance > 0 && remaining > TOLERANCE
        negative.isDown = distance < 0 && remaining < - TOLERANCE
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
        val duration = rotationTime.value.toDouble()
        if (duration <= 0) return PlayerUtils.rotate(endYaw, pitch)

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
        if (Debug.enabled) ChatUtils.modMessage("&7[Vampire] Tiny Dancer: &7$message")
    }

    override fun onDisable() {
        super.onDisable()
        rotationJob?.cancel()
        // Also called while features load, before the game options exist, so only touch the keys while dancing.
        if (inRoom) stopDancing()
        inRoom = false
    }
}
