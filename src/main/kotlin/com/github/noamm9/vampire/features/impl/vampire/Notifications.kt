package com.github.noamm9.vampire.features.impl.vampire

import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SoundSetting
import com.github.noamm9.config.types.TextInputSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
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
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import java.util.UUID

/**
 * On-screen titles (plus a sound) for the Riftstalker Bloodfiend fight.
 *
 * Stonewrath has no timer on the boss nametag, so it is detected by the boss name stand rising above you.
 */
object Notifications: Feature(
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

    private val stonesAlert by ToggleSetting("Stonewrath", true).section("Stonewrath")
        .withDescription("Warns when the boss flies up and you need to go to the stones.")
    private val leaveStonesAlert by ToggleSetting("Ready", true)
        .withDescription("Warns if the boss is locked on to your position and you need to leave the Stones")
    private val readyTrigger by DropdownSetting("Ready Trigger", 0, listOf("Delay", "Sound"))
        .withDescription("Delay: warns 3s after the boss flew up. Sound: warns on a sound you pick while the boss is up.")
        .showIf { leaveStonesAlert.value }
    private val lockOnSound by TextInputSetting("Sound ID", "")
        .withDescription("Part of the sound ID to react to. Turn on Debug to see every sound played while the boss is up.")
        .showIf { leaveStonesAlert.value && readyTrigger.value == 1 }

    private val stakeAlert by ToggleSetting("Steak Stake", true).section("Steak Stake")
        .withDescription("Warns once the boss is low enough to be staked.")

    private val playSound by ToggleSetting("Play Sound", true).section("General")
    private val sound by SoundSetting("Sound", SoundEvents.NOTE_BLOCK_PLING).showIf { playSound.value }
    private val onlyOwnBoss by ToggleSetting("Only Own Boss", true)
    private val debug by ToggleSetting("Debug", false)
        .withDescription("Prints every sound played while the boss is up, and how long it stayed up.")

    private val slainMessages = setOf("NICE! SLAYER BOSS SLAIN!", "SLAYER QUEST COMPLETE!")

    private var bossId: UUID? = null
    private var twinclawsActive = false
    private var stakeable = false
    private var flying = false
    /** Bumped on every fly-up, so a scheduled leave warning from an earlier one is ignored. */
    private var flyId = 0
    /** Normal hops reach about 6 blocks above you, Stonewrath goes up 16 to 20. */
    private const val FLY_HEIGHT = 10.0
    /** Ticks from flying up to the Ready warning; the slam comes about 115 ticks after flying up. */
    private const val LEAVE_DELAY = 60
    private var readyShown = false
    private var flyTicks = 0

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

            val isFlying = boss.y - player.y >= FLY_HEIGHT
            if (isFlying && ! flying) onFlyUp()
            if (isFlying) flyTicks ++
            else if (flying && debug.value) ChatUtils.modMessage("&7[Vampire] boss came down &f${flyTicks}t &7after flying up")
            flying = isFlying
        }

        register<MainThreadPacketReceivedEvent.Pre> {
            if (! flying) return@register
            val (soundId, where) = when (val packet = event.packet) {
                is ClientboundSoundPacket -> packet.sound.value().location.path to
                    "%.1f from you".format(player.distanceToSqr(packet.x, packet.y, packet.z).let(Math::sqrt))
                is ClientboundSoundEntityPacket -> packet.sound.value().location.path to
                    "on ${level.getEntity(packet.id)?.name?.string ?: "entity ${packet.id}"}"
                else -> return@register
            }

            if (debug.value) ChatUtils.modMessage("&7[Vampire] sound: &f$soundId &7($where, ${flyTicks}t after fly-up)")
            if (! leaveStonesAlert.value || readyTrigger.value != 1 || readyShown) return@register
            if (lockOnSound.value.isBlank() || lockOnSound.value.trim() !in soundId) return@register
            readyShown = true
            notify("&eReady!")
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
        readyShown = false
        flyTicks = 0
        if (stonesAlert.value) notify("&6Stonewrath!")
        if (leaveStonesAlert.value && readyTrigger.value == 0) scheduledTask(LEAVE_DELAY) {
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
    }
}
