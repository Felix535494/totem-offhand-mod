package com.totemoffhand;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod initializer (server-side entry point).
 * Since this mod is client-side only, most logic lives in TotemOffhandClient.
 */
public class TotemOffhandMod implements ModInitializer {

    public static final String MOD_ID = "totem_offhand";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[TotemOffhand] Mod initialized.");
    }
}
