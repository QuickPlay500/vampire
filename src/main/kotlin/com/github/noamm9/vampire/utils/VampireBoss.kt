package com.github.noamm9.vampire.utils

import com.github.noamm9.features.Shortcuts
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Shared Riftstalker Bloodfiend detection, used by the helper and the notifications.
 * Mirrors Skyblocker's vampire indicators: the boss name stand plus the custom-named stands within 2.5 blocks of it.
 */
object VampireBoss: Shortcuts {
    private const val BOSS_NAME = "Bloodfiend"
    /** Added to the boss name once it can be staked, same check as Skyblocker's StakeIndicator. */
    const val STAKE_MARKER = "҉"
    private val bossHealthRegex = Regex("Bloodfiend (V|IV|III|II|I)\\b.*?([\\d,]+(?:\\.\\d+)?[kM]?)❤")
    /** Health at which the Steak Stake's +1000 Rift Damage kicks in, per tier (20% of max health). */
    private val stakeThresholds = mapOf("I" to 125, "II" to 220, "III" to 360, "IV" to 480, "V" to 600)

    /** The stake marker is a combining character that can sit between the letters, which breaks "Bloodfiend" matching. */
    val ArmorStand.cleanName get() = name.unformattedText.replace(STAKE_MARKER, "")

    /** Your boss once found. Kept until it despawns, since the stand with your name can drop out of [standsAround] for a tick. */
    private var ownBoss: ArmorStand? = null

    /** Only the boss you spawned: the one with your name on its "SLAYER BOSS" stand. */
    fun findBossStand(): ArmorStand? {
        ownBoss?.let { if (! it.isRemoved && it.level() == level && BOSS_NAME in it.cleanName) return it }

        val username = mc.user.name
        val nameStands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(25.0)) {
            it.hasCustomName() && BOSS_NAME in it.cleanName
        }.sortedBy { it.distanceToSqr(player) }

        ownBoss = nameStands.firstOrNull { stand -> standsAround(stand).any { username in it.name.unformattedText } }
        return ownBoss
    }

    /** The timer and owner stands lag behind the boss name stand when it moves, so the box is wider than Skyblocker's 0.1. */
    fun standsAround(stand: ArmorStand) = level.getEntitiesOfClass(
        ArmorStand::class.java, stand.boundingBox.inflate(0.5, 2.5, 0.5)
    ) { it != stand && it.hasCustomName() }

    /** Countdown on the Blood Ichor's name stand, from 25s down; damage numbers like "-7s" don't match. */
    private val ichorTimerRegex = Regex("^(\\d+(?:\\.\\d+)?)s$")

    /** A Blood Ichor (Tier V) and the seconds left before it deals 100❤. [pos] is about where the heads render. */
    class Ichor(val stand: ArmorStand, val seconds: Double) {
        val pos: Vec3 get() = stand.position().add(0.0, - 1.0, 0.0)
    }

    /**
     * Blood Ichors near your boss. Each one is an invisible stand named "25s" (counting down) with two Player Head
     * stands stacked right below it, spawned when the "ICHOR" timer on the boss runs out.
     */
    fun findIchors(boss: ArmorStand): List<Ichor> = level.getEntitiesOfClass(
        ArmorStand::class.java, boss.boundingBox.inflate(30.0)
    ) { it.hasCustomName() }.mapNotNull { stand ->
        val seconds = ichorTimerRegex.find(stand.name.unformattedText.trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val hasHeads = level.getEntitiesOfClass(ArmorStand::class.java, stand.boundingBox.inflate(0.5, 0.0, 0.5).expandTowards(0.0, - 4.0, 0.0)) {
            it != stand && it.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.PLAYER_HEAD)
        }.isNotEmpty()
        if (hasHeads) Ichor(stand, seconds) else null
    }

    /**
     * Steak Stake or its upgrade, the Super Sharp 'n Stabby Steak Stake.
     * Matched by ID and by name, since the upgrade's ID isn't confirmed.
     */
    fun isSteakStake(stack: ItemStack) = "STAKE" in stack.skyblockId || "Steak Stake" in stack.hoverName.string

    /** The marker Skyblocker checks for, or the boss' health at/below the stake threshold of its tier (20%). */
    fun isStakeable(boss: ArmorStand): Boolean {
        if (STAKE_MARKER in boss.name.string) return true
        val match = bossHealthRegex.find(boss.cleanName) ?: return false
        val threshold = stakeThresholds[match.groupValues[1]] ?: return false
        return parseHealth(match.groupValues[2]) <= threshold
    }

    private fun parseHealth(text: String): Double {
        val multiplier = when (text.last()) {
            'k' -> 1_000.0
            'M' -> 1_000_000.0
            else -> 1.0
        }
        return (text.trimEnd('k', 'M').replace(",", "").toDoubleOrNull() ?: Double.MAX_VALUE) * multiplier
    }
}
