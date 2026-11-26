package com.github.noamm9.vampire.features.impl.rift

import com.github.noamm9.compat.KEYBOARD_TYPE
import com.github.noamm9.compat.isKeyDown
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.ui.utils.Animation.Companion.easeInOutCubic
import com.github.noamm9.utils.ActionBarParser
import com.github.noamm9.utils.ActionUtils.waitTicks
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils.lerp
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.ThreadUtils.scheduledTask
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import com.github.noamm9.vampire.utils.VampireBoss
import com.github.noamm9.vampire.utils.VampireBoss.findBossStand
import com.github.noamm9.vampire.utils.VampireBoss.isStakeable
import com.github.noamm9.vampire.utils.VampireBoss.standsAround
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.math.min

/**
 * Riftstalker Bloodfiend (Vampire Slayer) helper.
 *
 * - Healing Melon: right-clicks a Healing Melon when your health drops below a threshold.
 * - Holy Ice: right-clicks Holy Ice once the TWINCLAWS timer above the boss reaches a set value.
 * - Impel: performs the boss' order (CLICK UP / CLICK DOWN / JUMP / SNEAK).
 * - Steak Stake: switches to the stake once the boss can be staked (the hit itself stays with the player).
 *
 * Movement and sword hits stay with the player.
 * Detection mirrors Skyblocker's vampire indicators (armor stands within 2.5 blocks of the boss name stand).
 */
object AutoSlayer: Feature(
    description = "Automates Healing Melon, Holy Ice, Impels and the Steak Stake.",
    name = "Auto Slayer",
    toggled = false
) {
    private val autoMelon by ToggleSetting("Auto Healing Melon", true).section("Healing Melon")
        .withDescription("Uses a Healing Melon from your hotbar when your health is low.")
    private val melonHearts by SliderSetting("Heal Below", 7.0, 0.0, 16.0, 0.5, " hearts")
        .withDescription("Uses the melon when your health is at or below this many hearts.")
        .showIf { autoMelon.value }
    private val melonCooldown by SliderSetting("Melon Cooldown", 40, 0, 100, 1, " ticks")
        .withDescription("Minimum time between two melon uses.")
        .showIf { autoMelon.value }

    private val autoIce by ToggleSetting("Auto Holy Ice", true).section("Twinclaws")
        .withDescription("Uses Holy Ice when the boss starts a Twinclaws attack.")
    private val iceAt by SliderSetting("Ice At", 0.7, 0.2, 1.5, 0.1, "s")
        .withDescription("Uses Holy Ice once the TWINCLAWS timer is at or below this. The hit lands at 0.0s and Holy Ice lasts 1.5s.")
        .showIf { autoIce.value }

    private val autoImpel by ToggleSetting("Auto Impel", true).section("Impel")
        .withDescription("Performs the boss' orders: CLICK UP, CLICK DOWN, JUMP and SNEAK.")
    private val impelDelay by SliderSetting("Impel Delay", 2, 0, 20, 1, " ticks")
        .withDescription("Ticks to wait before reacting to an order.")
        .showIf { autoImpel.value }
    private val rotationTime by SliderSetting("Rotation Time", 230, 0, 500, 1, " ms")
        .withDescription("Time (ms) to rotate up/down for CLICK UP and CLICK DOWN, and the same again to rotate back. &eSet to 0 to snap instantly.")
        .showIf { autoImpel.value }
    private val sneakFailsafe by ToggleSetting("Sneak Failsafe", true)
        .withDescription("If you are already sneaking on a SNEAK order, briefly unsneaks and sneaks again so it registers as a new sneak.")
        .showIf { autoImpel.value }
    private val jumpFailsafe by ToggleSetting("Jump Failsafe", true)
        .withDescription("If you are in the air on a JUMP order, waits until you land and jumps then.")
        .showIf { autoImpel.value }

    private val autoTuba by ToggleSetting("Auto Tuba", false).section("Tuba")
        .withDescription("Uses the Weird Tuba or Weirder Tuba from your hotbar every 30s during the boss.")
    private val tubaMinMana by SliderSetting("Min Mana", 100, 0, 200, 10, " mana")
        .withDescription("Only uses the tuba with at least this much mana, so Holy Ice and the Healing Melon keep enough.")
        .showIf { autoTuba.value }

    private val autoStake by ToggleSetting("Auto Steak Stake", true).section("Steak Stake")
        .withDescription("Switches to the Steak Stake in your hotbar once the boss is low enough to be staked.")

    /**
     * The order comes as a title that counts down every 2 ticks, e.g. "Impel: CLICK UP 2.0s".
     * The "IMPEL 0.5s" on the boss nametag is only the warning before it and never contains the order.
     */
    private val twinclawsRegex = Regex("TWINCLAWS\\s+(\\d+(?:\\.\\d+)?)S")
    private val impelRegex = Regex("(CLICK UP|CLICK DOWN|JUMP|SNEAK)(?:\\s+(\\d+(?:\\.\\d+)?)S)?")
    /** Fallback for a title without countdown: the same order counts as new after it was gone this long. */
    private const val IMPEL_GONE_TICKS = 5
    /** Ticks without a TWINCLAWS timer before the attack counts as over. Attacks are at least 7s apart. */
    private const val TWINCLAWS_GONE_TICKS = 20
    /** Ticks the sneak key stays released for the Sneak Failsafe, long enough for the server to get the input change. */
    private const val SNEAK_RELEASE_TICKS = 2
    /** Longest the Jump Failsafe waits for the ground, kept below the 2s (40 ticks) Impel window. */
    private const val JUMP_WAIT_TICKS = 30
    /** Cooldown of the tuba's ability, 30s. */
    private const val TUBA_COOLDOWN = 600
    private val tubaIds = setOf("WEIRD_TUBA", "WEIRDER_TUBA")

    private var bossStand: ArmorStand? = null
    /** Set while a hotbar item is being swapped to and used. Impels left-click and don't set it, so Holy Ice (right-click) can go through mid-Impel. */
    private var busy = false
    private var lastMelonTick = 0L
    private var lastTubaTick = - TUBA_COOLDOWN.toLong()
    private var ticks = 0L
    private var twinclawsHandled = false
    private var twinclawsActive = false
    private var lastTwinclawsTick = 0L
    private var lastImpel: String? = null
    private var lastImpelSeenTick = 0L
    private var lastImpelRemaining: Double? = null
    private var impelRunning = false
    private var pendingImpel: String? = null
    private var runningImpel: String? = null
    private var impelStartTick = 0L
    private var stakeHandled = false
    private var stakeSlot: Int? = null
    private var stakeMissingLogged = false
    private var rotationJob: Job? = null
    private val seenDebugNames = mutableSetOf<String>()

    override fun init() {
        register<WorldChangeEvent> { reset() }

        register<TickEvent.Start> {
            ticks ++
            if (mc.player == null || mc.level == null) return@register
            if (LocationUtils.world != WorldType.Rift) return@register reset()

            val boss = findBossStand() ?: return@register reset()
            bossStand = boss
            val stands = standsAround(boss)

            if (Debug.enabled) {
                stands.map { it.name.unformattedText }.filter(seenDebugNames::add).forEach {
                    ChatUtils.modMessage("&7[Rift] nametag: &f$it")
                }
                // Raw name with formatting codes, so the stake marker / bold styling shows up.
                boss.name.string.takeIf(seenDebugNames::add)?.let { ChatUtils.modMessage("&7[Rift] boss: &f${it.replace("§", "¦")}") }
            }

            if (autoStake.value) checkStake(boss)
            if (autoMelon.value) checkMelon()
            if (autoIce.value) checkTwinclaws(stands)
            if (autoTuba.value) checkTuba()
        }

        register<MainThreadPacketReceivedEvent.Post> {
            val text = when (val packet = event.packet) {
                is ClientboundSetTitleTextPacket -> packet.text.unformattedText
                is ClientboundSetSubtitleTextPacket -> packet.text.unformattedText
                else -> return@register
            }.takeUnless(String::isBlank) ?: return@register
            if (bossStand == null) {
                if (Debug.enabled && "IMPEL" in text.uppercase()) ChatUtils.modMessage("&7[Rift] Impel: &ctitle ignored, boss not detected: &f$text")
                return@register
            }

            if (Debug.enabled) ChatUtils.modMessage("&7[Rift] title: &f$text")
            if (! autoImpel.value) return@register
            val match = impelRegex.find(text.uppercase()) ?: return@register
            handleImpel(match.groupValues[1], match.groupValues[2].toDoubleOrNull())
        }
    }

    /** Swaps to the stake once per stake window, so the player can still switch away afterwards. */
    private fun checkStake(boss: ArmorStand) {
        if (! isStakeable(boss)) return run {
            stakeHandled = false
            stakeMissingLogged = false
            stakeSlot = null
        }
        // Melon/Ice swap back to their previous slot when done, which would undo the swap.
        if (stakeHandled || busy) return

        val slot = PlayerUtils.findHotbarSlot(VampireBoss::isSteakStake) ?: return run {
            if (Debug.enabled && ! stakeMissingLogged) {
                stakeMissingLogged = true
                val ids = (0 .. 8).map { player.inventory.getItem(it).skyblockId.ifEmpty { "-" } }
                ChatUtils.modMessage("&7[Rift] Stake: &cno stake in hotbar &7$ids")
            }
        }
        stakeHandled = true
        stakeSlot = slot
        player.inventory.selectedSlot = slot
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Stake: &eswapped to slot ${slot + 1}")
    }

    private fun checkMelon() {
        if (busy || ticks - lastMelonTick < melonCooldown.value) return
        if (player.health > melonHearts.value * 2) return
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId.endsWith("HEALING_MELON") } ?: return
        lastMelonTick = ticks
        useHotbarItem(slot)
    }

    /** Checked after melon and ice, and skipped while an ice is still due, so the tuba never takes their turn. */
    private fun checkTuba() {
        if (busy || ticks - lastTubaTick < TUBA_COOLDOWN) return
        if (twinclawsActive && ! twinclawsHandled) return
        if (ActionBarParser.currentMana < tubaMinMana.value) return
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId in tubaIds } ?: return
        lastTubaTick = ticks
        useHotbarItem(slot)
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Tuba: &eused &7(mana ${ActionBarParser.currentMana}/${ActionBarParser.maxMana})")
    }

    private fun checkTwinclaws(stands: List<ArmorStand>) {
        val remaining = stands.firstNotNullOfOrNull { twinclawsRegex.find(it.name.unformattedText.uppercase()) }
            ?.groupValues?.get(1)?.toDoubleOrNull()
        if (remaining == null) return run {
            // The timer stand drops out for a tick or two while the boss moves; only a longer gap ends the attack.
            if (! twinclawsActive || ticks - lastTwinclawsTick < TWINCLAWS_GONE_TICKS) return
            if (! twinclawsHandled && Debug.enabled) ChatUtils.modMessage("&7[Rift] Ice: &cmissed, timer ended")
            twinclawsActive = false
            twinclawsHandled = false
        }
        if (! twinclawsActive && Debug.enabled) ChatUtils.modMessage("&7[Rift] Ice: &fTwinclaws seen at ${remaining}s")
        twinclawsActive = true
        lastTwinclawsTick = ticks
        // Checked every tick until used, so a late timer stand or a running Impel only delays the ice.
        if (twinclawsHandled || remaining > iceAt.value || busy) return

        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "HOLY_ICE" } ?: return run {
            if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Ice: &cno Holy Ice in hotbar")
            twinclawsHandled = true
        }
        twinclawsHandled = true
        useHotbarItem(slot)
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Ice: &eused at ${remaining}s")
    }

    /**
     * Called on every title update of an order. Within one Impel the countdown only goes down, so a different order
     * or a countdown that went back up is a new Impel (Tier V sends them back to back, even the same order twice).
     * A new order during a running one is queued.
     */
    private fun handleImpel(order: String, remaining: Double?) {
        val prevRemaining = lastImpelRemaining
        val isNew = when {
            order != lastImpel -> true
            remaining != null && prevRemaining != null -> remaining > prevRemaining
            else -> ticks - lastImpelSeenTick > IMPEL_GONE_TICKS
        }
        lastImpel = order
        lastImpelSeenTick = ticks
        lastImpelRemaining = remaining
        if (! isNew) return

        impelDebug("new order &e$order&7 (title ${remaining ?: "?"}s, last was ${prevRemaining ?: "-"}s)")
        if (impelRunning) {
            impelDebug("&6queued&7 $order behind $runningImpel" + (pendingImpel?.let { ", replaces queued $it" } ?: ""))
            pendingImpel = order
            return
        }
        runImpel(order)
    }

    private fun runImpel(order: String) {
        impelRunning = true
        runningImpel = order
        impelStartTick = ticks
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Impel: &e$order")

        scheduledTask(impelDelay.value) {
            impelDebug("executing $order")
            when (order) {
                "CLICK UP" -> clickWithPitch(- 90f, ::finishImpel)
                "CLICK DOWN" -> clickWithPitch(90f, ::finishImpel)
                "JUMP" -> if (jumpFailsafe.value) jumpWhenLanded(JUMP_WAIT_TICKS) else pressJump()
                "SNEAK" -> {
                    val key = mc.options.keyShift
                    if (! sneakFailsafe.value || ! key.isDown) {
                        impelDebug("sneak pressed (was sneaking: ${player.isShiftKeyDown}, screen: ${screenName()})")
                        return@scheduledTask holdKey(key, 4, ::finishImpel)
                    }
                    // Already sneaking: the server only sees a new sneak if it sees the key released first.
                    impelDebug("already sneaking, releasing for $SNEAK_RELEASE_TICKS ticks")
                    key.isDown = false
                    scheduledTask(SNEAK_RELEASE_TICKS) {
                        impelDebug("sneak pressed again (screen: ${screenName()})")
                        holdKey(key, 4, ::finishImpel)
                    }
                }
            }
        }
    }

    /** A jump pressed mid-air doesn't count, so wait for the ground (up to [ticksLeft]) and jump right when landing. */
    private fun jumpWhenLanded(ticksLeft: Int) {
        val onGround = mc.player?.onGround() ?: return finishImpel()
        if (onGround || ticksLeft <= 0) return pressJump()
        if (ticksLeft == JUMP_WAIT_TICKS) impelDebug("in the air, waiting for ground")
        scheduledTask(1) { jumpWhenLanded(ticksLeft - 1) }
    }

    private fun pressJump() {
        impelDebug("jump pressed (on ground: ${player.onGround()}, screen: ${screenName()})")
        holdKey(mc.options.keyJump, 3, ::finishImpel)
    }

    private fun finishImpel() {
        impelRunning = false
        impelDebug("$runningImpel done")
        runningImpel = null
        val next = pendingImpel ?: return
        pendingImpel = null
        runImpel(next)
    }

    /** Looks straight up/down, left-clicks one tick later so the server has the new rotation, then restores the camera. */
    private fun clickWithPitch(pitch: Float, onDone: () -> Unit) {
        val prevPitch = player.xRot
        rotationJob = scope.launch {
            try {
                rotatePitch(pitch)
                waitTicks(1) {
                    // A swing that hit nothing in the last 10 ticks would make Minecraft ignore this click.
                    val missTime = mc.missTime
                    mc.missTime = 0
                    impelDebug("click at pitch ${"%.0f".format(player.xRot)}" + (if (missTime > 0) " &e(cleared miss cooldown ${missTime}t)" else "") +
                        (clickBlocker()?.let { " &cDROPPED: $it" } ?: " &aok"))
                    PlayerUtils.leftClick()
                }
                waitTicks(2)
                rotatePitch(prevPitch)
            } finally {
                scheduledTask(0, onDone)
            }
        }
    }

    /** Eases only the pitch like Auto I4 does, so the player keeps control of the yaw while it rotates. */
    private suspend fun rotatePitch(target: Float) {
        val duration = rotationTime.value.toDouble()
        if (duration <= 0) return PlayerUtils.rotate(player.yRot, target)

        val startPitch = player.xRot
        val startTime = System.currentTimeMillis()
        while (true) {
            val progress = min((System.currentTimeMillis() - startTime) / duration, 1.0)
            val pitch = lerp(startPitch, target, easeInOutCubic(progress).toFloat()).toFloat()
            PlayerUtils.rotate(player.yRot, pitch)
            if (progress >= 1) break
            delay(1)
        }
    }

    /**
     * Why Minecraft would swallow a left click right now, or null. It ignores clicks for 10 ticks after a swing that
     * hit nothing (missTime), while an item is in use or the hands are busy, and while a screen is open.
     */
    private fun clickBlocker(): String? = when {
        mc.missTime > 0 -> "miss cooldown ${mc.missTime} ticks"
        player.isUsingItem -> "using an item"
        player.isHandsBusy -> "hands busy"
        mc.gui.screen() != null -> "screen open (${screenName()})"
        else -> null
    }

    private fun screenName() = mc.gui.screen()?.javaClass?.simpleName ?: "none"

    /** Impel debug line with the ticks since the order started, so its timing can be read from the log. */
    private fun impelDebug(message: String) {
        if (Debug.enabled) ChatUtils.modMessage("&7[Rift] Impel +${ticks - impelStartTick}t: &7$message")
    }

    /** Presses [key] for [duration] ticks, then releases it unless the player is physically holding it. */
    private fun holdKey(key: KeyMapping, duration: Int, onDone: () -> Unit) {
        key.isDown = true
        scheduledTask(duration) {
            key.isDown = isPhysicallyDown(key)
            onDone()
        }
    }

    private fun isPhysicallyDown(key: KeyMapping): Boolean {
        val bound = KeyMappingHelper.getBoundKeyOf(key)
        return bound.type == KEYBOARD_TYPE && isKeyDown(bound.value)
    }

    /** Swaps to [slot], right-clicks on the next tick, and swaps back afterwards. */
    private fun useHotbarItem(slot: Int) {
        val inventory = player.inventory
        val prevSlot = inventory.selectedSlot
        busy = true
        inventory.selectedSlot = slot
        scheduledTask(1) {
            PlayerUtils.rightClick()
            scheduledTask(2) {
                // During the stake window always go back to the stake, never to the sword.
                mc.player?.inventory?.selectedSlot = stakeSlot ?: prevSlot
                busy = false
            }
        }
    }

    private fun reset() {
        bossStand = null
        twinclawsHandled = false
        twinclawsActive = false
        stakeHandled = false
        stakeSlot = null
        pendingImpel = null
        seenDebugNames.clear()
    }

    override fun onDisable() {
        super.onDisable()
        rotationJob?.cancel()
        reset()
    }
}
