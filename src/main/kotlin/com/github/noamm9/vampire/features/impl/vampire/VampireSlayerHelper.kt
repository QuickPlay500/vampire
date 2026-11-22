package com.github.noamm9.vampire.features.impl.vampire

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
import com.github.noamm9.utils.ActionUtils.waitTicks
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils.lerp
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.ThreadUtils.scheduledTask
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
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
 * - Holy Ice: right-clicks Holy Ice a configurable delay after the TWINCLAWS indicator shows up above the boss.
 * - Impel: performs the boss' order (CLICK UP / CLICK DOWN / JUMP / SNEAK).
 * - Steak Stake: switches to the stake once the boss can be staked (the hit itself stays with the player).
 *
 * Movement and sword hits stay with the player.
 * Detection mirrors Skyblocker's vampire indicators (armor stands within 2.5 blocks of the boss name stand).
 */
object VampireSlayerHelper: Feature(
    description = "Automates Healing Melon, Holy Ice, Impel orders and the Steak Stake swap during the Vampire Slayer.",
    name = "Vampire Slayer Helper",
    toggled = false
) {
    private val autoMelon by ToggleSetting("Auto Healing Melon", true).section("Healing Melon")
        .withDescription("Uses a Healing Melon from your hotbar when your health is low.")
    private val melonHearts by SliderSetting("Heal Below", 6.0, 1.0, 20.0, 0.5, " hearts")
        .withDescription("Uses the melon when your health is at or below this many hearts.")
        .showIf { autoMelon.value }
    private val melonCooldown by SliderSetting("Melon Cooldown", 20, 5, 100, 1, " ticks")
        .withDescription("Minimum time between two melon uses, so mana isn't wasted.")
        .showIf { autoMelon.value }

    private val autoIce by ToggleSetting("Auto Holy Ice", true).section("Twinclaws")
        .withDescription("Uses Holy Ice when the boss starts a Twinclaws attack.")
    private val iceDelay by SliderSetting("Ice Delay", 12, 0, 40, 1, " ticks")
        .withDescription("Ticks to wait after TWINCLAWS appears. The wiki recommends 0.5 to 1s (10 to 20 ticks); Holy Ice lasts 1.5s.")
        .showIf { autoIce.value }

    private val autoImpel by ToggleSetting("Auto Impel", true).section("Impel")
        .withDescription("Performs the boss' orders: CLICK UP, CLICK DOWN, JUMP and SNEAK.")
    private val impelDelay by SliderSetting("Impel Delay", 2, 0, 20, 1, " ticks")
        .withDescription("Ticks to wait before reacting to an order. Tier IV only gives you 2 seconds (40 ticks).")
        .showIf { autoImpel.value }
    private val rotationTime by SliderSetting("Rotation Time", 150, 0, 500, 1, " ms")
        .withDescription("Time (ms) to rotate up/down for CLICK UP and CLICK DOWN, and the same again to rotate back. &eSet to 0 to snap instantly.")
        .showIf { autoImpel.value }

    private val autoStake by ToggleSetting("Auto Steak Stake", true).section("Steak Stake")
        .withDescription("Switches to the Steak Stake in your hotbar once the boss is low enough to be staked.")

    private val onlyOwnBoss by ToggleSetting("Only Own Boss", true).section("General")
        .withDescription("Only reacts to a boss you spawned.")
    private val debug by ToggleSetting("Debug", false)
        .withDescription("Prints titles and boss nametags to chat, to check the detection strings.")

    private const val BOSS_NAME = "Bloodfiend"
    private val impelRegex = Regex("CLICK UP|CLICK DOWN|JUMP|SNEAK")
    /** The order stays visible (nametag + repeated titles) for up to 2.5s, so ignore further triggers for longer than that. */
    private const val IMPEL_LOCK_TICKS = 60
    /** Added to the boss name once it can be staked, same check as Skyblocker's StakeIndicator. */
    private const val STAKE_MARKER = "҉"

    private var bossStand: ArmorStand? = null
    private var busy = false
    private var lastMelonTick = 0L
    private var ticks = 0L
    private var twinclawsHandled = false
    private var impelLockedUntil = 0L
    private var stakeHandled = false
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

            if (debug.value) stands.map { it.name.unformattedText }.filter(seenDebugNames::add).forEach {
                ChatUtils.modMessage("&7[Vampire] nametag: &f$it")
            }

            if (autoStake.value) checkStake(boss)
            if (autoMelon.value) checkMelon()
            if (autoIce.value) checkTwinclaws(stands)
            if (autoImpel.value) {
                stands.firstNotNullOfOrNull { impelRegex.find(it.name.unformattedText.uppercase())?.value }?.let(::handleImpel)
            }
        }

        register<MainThreadPacketReceivedEvent.Post> {
            if (bossStand == null) return@register
            val text = when (val packet = event.packet) {
                is ClientboundSetTitleTextPacket -> packet.text.unformattedText
                is ClientboundSetSubtitleTextPacket -> packet.text.unformattedText
                else -> return@register
            }.takeUnless(String::isBlank) ?: return@register

            if (debug.value) ChatUtils.modMessage("&7[Vampire] title: &f$text")
            if (! autoImpel.value) return@register
            impelRegex.find(text.uppercase())?.value?.let(::handleImpel)
        }
    }

    /** Swaps to the stake once per stake window, so the player can still switch away afterwards. */
    private fun checkStake(boss: ArmorStand) {
        if (STAKE_MARKER !in boss.name.string) return run { stakeHandled = false }
        // Melon/Ice swap back to their previous slot when done, which would undo the swap.
        if (stakeHandled || busy) return

        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "STEAK_STAKE" } ?: return
        stakeHandled = true
        player.inventory.selectedSlot = slot
    }

    private fun checkMelon() {
        if (busy || ticks - lastMelonTick < melonCooldown.value) return
        if (player.health > melonHearts.value * 2) return
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId.endsWith("HEALING_MELON") } ?: return
        lastMelonTick = ticks
        useHotbarItem(slot)
    }

    private fun checkTwinclaws(stands: List<ArmorStand>) {
        val active = stands.any { "TWINCLAWS" in it.name.unformattedText.uppercase() }
        if (! active) return run { twinclawsHandled = false }
        if (twinclawsHandled) return

        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "HOLY_ICE" } ?: return
        twinclawsHandled = true
        scheduledTask(iceDelay.value) {
            // Melon and Impel can be in progress, wait for them instead of dropping the ice.
            fun tryUse(attempts: Int) {
                if (bossStand == null) return
                if (! busy) return useHotbarItem(slot)
                if (attempts > 0) scheduledTask(1) { tryUse(attempts - 1) }
            }
            tryUse(10)
        }
    }

    private fun handleImpel(order: String) {
        if (ticks < impelLockedUntil) return
        impelLockedUntil = ticks + impelDelay.value + IMPEL_LOCK_TICKS
        if (debug.value) ChatUtils.modMessage("&7[Vampire] Impel: &e$order")

        scheduledTask(impelDelay.value) {
            when (order) {
                "CLICK UP" -> clickWithPitch(- 90f)
                "CLICK DOWN" -> clickWithPitch(90f)
                "JUMP" -> holdKey(mc.options.keyJump, 3)
                "SNEAK" -> holdKey(mc.options.keyShift, 4)
            }
        }
    }

    /** Looks straight up/down, right-clicks one tick later so the server has the new rotation, then restores the camera. */
    private fun clickWithPitch(pitch: Float) {
        val prevPitch = player.xRot
        busy = true
        rotationJob = scope.launch {
            try {
                rotatePitch(pitch)
                waitTicks(1, PlayerUtils::rightClick)
                waitTicks(2)
                rotatePitch(prevPitch)
            } finally {
                busy = false
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

    /** Presses [key] for [duration] ticks, then releases it unless the player is physically holding it. */
    private fun holdKey(key: KeyMapping, duration: Int) {
        key.isDown = true
        scheduledTask(duration) { key.isDown = isPhysicallyDown(key) }
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
                mc.player?.inventory?.selectedSlot = prevSlot
                busy = false
            }
        }
    }

    private fun findBossStand(): ArmorStand? {
        val username = mc.user.name
        val nameStands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(25.0)) {
            it.hasCustomName() && BOSS_NAME in it.name.unformattedText
        }.sortedBy { it.distanceToSqr(player) }

        if (! onlyOwnBoss.value) return nameStands.firstOrNull()
        return nameStands.firstOrNull { stand -> standsAround(stand).any { username in it.name.unformattedText } }
    }

    private fun standsAround(stand: ArmorStand) = level.getEntitiesOfClass(
        ArmorStand::class.java, stand.boundingBox.inflate(0.1, 2.5, 0.1)
    ) { it != stand && it.hasCustomName() }

    private fun reset() {
        bossStand = null
        twinclawsHandled = false
        stakeHandled = false
        seenDebugNames.clear()
    }

    override fun onDisable() {
        super.onDisable()
        rotationJob?.cancel()
        reset()
    }
}
