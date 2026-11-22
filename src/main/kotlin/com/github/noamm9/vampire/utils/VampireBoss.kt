package com.github.noamm9.vampire.utils

import com.github.noamm9.features.Shortcuts
import com.github.noamm9.utils.ChatUtils.unformattedText
import net.minecraft.world.entity.decoration.ArmorStand

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

    fun findBossStand(ownOnly: Boolean): ArmorStand? {
        val username = mc.user.name
        val nameStands = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(25.0)) {
            it.hasCustomName() && BOSS_NAME in it.cleanName
        }.sortedBy { it.distanceToSqr(player) }

        if (! ownOnly) return nameStands.firstOrNull()
        return nameStands.firstOrNull { stand -> standsAround(stand).any { username in it.name.unformattedText } }
    }

    fun standsAround(stand: ArmorStand) = level.getEntitiesOfClass(
        ArmorStand::class.java, stand.boundingBox.inflate(0.1, 2.5, 0.1)
    ) { it != stand && it.hasCustomName() }

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
