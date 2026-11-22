package com.github.noamm9.vampire.features.impl.vampire

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.SoundSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.SoundUtils
import com.github.noamm9.utils.ThreadUtils.scheduledTask
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import com.github.noamm9.vampire.utils.VampireBoss.findBossStand
import com.github.noamm9.vampire.utils.VampireBoss.isStakeable
import com.github.noamm9.vampire.utils.VampireBoss.standsAround
import net.minecraft.sounds.SoundEvents
import java.util.UUID

/**
 * On-screen titles (plus a sound) for the Riftstalker Bloodfiend fight.
 *
 * Stonewrath has no timer on the boss nametag, so it is detected by the boss name stand rising above you.
 */
object VampireNotifications: Feature(
    description = "Shows titles for Twinclaws, Stonewrath, the Steak Stake window and boss spawn/death during the Vampire Slayer.",
    name = "Vampire Notifications",
    toggled = false
) {
    private val spawnAlert by ToggleSetting("Boss Spawned", true).section("Boss")
    private val deathAlert by ToggleSetting("Boss Dead", true)

    private val twinclawsAlert by ToggleSetting("Twinclaws", true).section("Twinclaws")
    private val twinclawsOnlyMania by ToggleSetting("Only During Mania", false)
        .withDescription("Only warns about Twinclaws while Mania is active (Tier IV+), where the knockback can push you off the green ring.")
        .showIf { twinclawsAlert.value }

    private val stonesAlert by ToggleSetting("Go To Stones", true).section("Stonewrath")
        .withDescription("Warns when the boss flies up, so you can go to the stones.")
    private val leaveStonesAlert by ToggleSetting("Leave Stones", true)
        .withDescription("Warns shortly before the boss comes back down, so you can leave the stones.")
    private val leaveDelay by SliderSetting("Leave Warning Delay", 25, 0, 60, 1, " ticks")
        .withDescription("Ticks after the boss flew up until the leave warning. The boss locks onto your position after about 2s (40 ticks).")
        .showIf { leaveStonesAlert.value }
    private val flyHeight by SliderSetting("Fly Height", 6.0, 3.0, 15.0, 0.5, " blocks")
        .withDescription("How far the boss name has to be above you to count as flying up.")
        .showIf { stonesAlert.value || leaveStonesAlert.value }

    private val stakeAlert by ToggleSetting("Steak Stake", true).section("Steak Stake")
        .withDescription("Warns once the boss is low enough to be staked.")

    private val playSound by ToggleSetting("Play Sound", true).section("General")
    private val sound by SoundSetting("Sound", SoundEvents.NOTE_BLOCK_PLING).showIf { playSound.value }
    private val onlyOwnBoss by ToggleSetting("Only Own Boss", true)
    private val debug by ToggleSetting("Debug", false)
        .withDescription("Prints the boss' height above you whenever it changes by a block, to tune Fly Height.")

    private val slainMessages = setOf("NICE! SLAYER BOSS SLAIN!", "SLAYER QUEST COMPLETE!")

    private var bossId: UUID? = null
    private var twinclawsActive = false
    private var stakeable = false
    private var flying = false
    /** Bumped on every fly-up, so a scheduled leave warning from an earlier one is ignored. */
    private var flyId = 0
    private var lastDebugHeight = Int.MIN_VALUE

    override fun init() {
        register<WorldChangeEvent> {
            bossId = null
            resetState()
        }

        register<TickEvent.Start> {
            if (mc.player == null || mc.level == null) return@register
            if (LocationUtils.world != WorldType.Rift) return@register resetState()
            val boss = findBossStand(onlyOwnBoss.value) ?: return@register resetState()

            if (boss.uuid != bossId) {
                bossId = boss.uuid
                if (spawnAlert.value) notify("&cBoss Spawned!")
            }

            val texts = standsAround(boss).map { it.name.string.uppercase() }

            val claws = texts.any { "TWINCLAWS" in it }
            if (claws && ! twinclawsActive && twinclawsAlert.value && (! twinclawsOnlyMania.value || texts.any { "MANIA" in it })) {
                notify("&bTwinclaws!", "&7Use Holy Ice")
            }
            twinclawsActive = claws

            val canStake = isStakeable(boss)
            if (canStake && ! stakeable && stakeAlert.value) notify("&4Steak Stake!", "&7The boss can be staked")
            stakeable = canStake

            val height = boss.y - player.y
            if (debug.value && height.toInt() != lastDebugHeight) {
                lastDebugHeight = height.toInt()
                ChatUtils.modMessage("&7[Vampire] boss height: &f${"%.1f".format(height)}")
            }

            val isFlying = height >= flyHeight.value
            if (isFlying && ! flying) onFlyUp()
            flying = isFlying
        }

        register<ChatMessageEvent> {
            if (LocationUtils.world != WorldType.Rift || bossId == null) return@register
            if (event.unformattedText.trim() !in slainMessages) return@register
            bossId = null
            resetState()
            if (deathAlert.value) notify("&aBoss Dead!")
        }
    }

    private fun onFlyUp() {
        val id = ++ flyId
        if (stonesAlert.value) notify("&6Stonewrath!")
        if (leaveStonesAlert.value) scheduledTask(leaveDelay.value) {
            if (id == flyId && flying) notify("&eReady!")
        }
    }

    private fun notify(title: String, subtitle: String = "") {
        ChatUtils.showTitle(title, subtitle)
        if (playSound.value) SoundUtils.playSound(sound.value)
    }

    private fun resetState() {
        twinclawsActive = false
        stakeable = false
        flying = false
        lastDebugHeight = Int.MIN_VALUE
    }
}
