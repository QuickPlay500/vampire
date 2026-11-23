package com.github.noamm9.vampire.features.impl.vampire

import com.github.noamm9.features.Feature

/** Toggling this feature turns the Vampire debug chat messages on or off; the other features read [enabled]. */
object Debug: Feature(
    description = "Prints titles, boss nametags and what Auto Slayer does to chat, to check the detection strings.",
    name = "Debug",
    toggled = false
)
