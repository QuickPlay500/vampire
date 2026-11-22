package com.github.noamm9.vampire

import com.github.noamm9.NoammAddons
import net.fabricmc.api.ClientModInitializer

object Vampire: ClientModInitializer {
    override fun onInitializeClient() {
        NoammAddons.logger.info("Hi from ${this.javaClass.simpleName}!")
    }
}