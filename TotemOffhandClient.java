package com.totemoffhand;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side initializer.
 *
 * ── How Minecraft inventory slot interaction works ──────────────────────────
 *
 * Every open screen has a ScreenHandler which holds a flat list of Slot objects.
 * Slot indices in that list are NOT the same as PlayerInventory indices.
 *
 * For a generic HandledScreen the typical layout is:
 *   [0]          = container/craft output (if any)
 *   [1..N]       = container slots
 *   [N+1..N+27]  = player main inventory (rows 1-3, left-to-right)
 *   [N+28..N+35] = player hotbar (slots 0-8)
 *
 * For the player inventory screen (no container) the layout is:
 *   [0]     = craft output
 *   [1..4]  = craft input
 *   [5]     = offhand  ← this is what we target
 *   [6..9]  = armor
 *   [10..36]= main inventory (rows 1-3)
 *   [37..45]= hotbar
 *
 * ClientPlayerInteractionManager.clickSlot(syncId, slotIndex, button, actionType, player)
 * mirrors what the client sends to the server when the player clicks a slot.
 * SlotActionType.SWAP with button = 40 is the "F-key offhand swap" action.
 * SlotActionType.SWAP with button = 0..8 swaps with hotbar slot N.
 * SlotActionType.PICKUP (button 0 = left, 1 = right) picks up / places items.
 *
 * To move a hovered slot → offhand WITHOUT simulating F:
 *   1. Pick up the item with PICKUP (left-click the slot → cursor now holds it).
 *   2. Left-click the offhand slot → item lands in offhand.
 *   If offhand is occupied we must swap or use an intermediate step.
 *
 * Simpler approach used here: SlotActionType.SWAP with button=40
 *   This is exactly what Minecraft does internally for the offhand key.
 *   It does NOT simulate a KeyPress — it directly calls the packet handler.
 * ────────────────────────────────────────────────────────────────────────────
 */
public class TotemOffhandClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TotemOffhandMod.MOD_ID);

    /**
     * Cooldown in milliseconds between automatic moves.
     * Prevents repeated moves while the cursor stays on the same slot.
     */
    private static final long COOLDOWN_MS = 800;

    /** Tracks the last slot index we acted on to prevent re-triggering. */
    private int lastActedSlotIndex = -1;

    /** Timestamp of the last action. */
    private long lastActionTime = 0L;

    @Override
    public void onInitializeClient() {
        // Hook into every tick of any HandledScreen (inventory, chest, etc.)
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof HandledScreen<?>) {
                ScreenEvents.afterTick(screen).register(s -> onInventoryTick(client, (HandledScreen<?>) s));
            }
        });

        LOGGER.info("[TotemOffhand] Client initialized – totem auto-offhand active.");
    }

    /**
     * Called every tick while a HandledScreen is open.
     * Checks if the cursor is hovering over a Totem of Undying and moves it
     * to the offhand (or hotbar slot 1) as appropriate.
     */
    private void onInventoryTick(MinecraftClient client, HandledScreen<?> screen) {
        // ── Null-safety guards ───────────────────────────────────────────────
        if (client.player == null) return;
        if (client.interactionManager == null) return;

        ClientPlayerEntity player = client.player;
        ScreenHandler handler = screen.getScreenHandler();

        // ── Find the slot the cursor is currently hovering over ──────────────
        Slot hoveredSlot = getFocusedSlot(screen);
        if (hoveredSlot == null) return;
        if (!hoveredSlot.hasStack()) return;

        ItemStack hoveredStack = hoveredSlot.getStack();

        // ── Only care about Totems of Undying ────────────────────────────────
        if (!hoveredStack.isOf(Items.TOTEM_OF_UNDYING)) {
            // Reset last-acted tracking when cursor moves off a totem
            lastActedSlotIndex = -1;
            return;
        }

        int hoveredSyncSlot = hoveredSlot.id; // slot index in the ScreenHandler

        // ── Cooldown + deduplicate: don't fire twice for the same slot ────────
        long now = System.currentTimeMillis();
        if (hoveredSyncSlot == lastActedSlotIndex && (now - lastActionTime) < COOLDOWN_MS) {
            return;
        }

        // ── Determine what is currently in the offhand ───────────────────────
        ItemStack offhandStack = player.getOffHandStack();
        boolean offhandHasTotem = offhandStack.isOf(Items.TOTEM_OF_UNDYING);

        if (!offhandHasTotem) {
            // ── Case 1: offhand is free (or holds a non-totem) ───────────────
            // Use SWAP action with button=40 (vanilla offhand swap — no key simulation)
            moveToOffhand(client, player, handler, hoveredSyncSlot);
        } else {
            // ── Case 2: offhand already has a totem → send to hotbar slot 2 ──
            moveToHotbarSlot(client, player, handler, hoveredSyncSlot, 1 /* hotbar index 1 = slot 2 */);
        }

        // Record action to prevent immediate re-trigger
        lastActedSlotIndex = hoveredSyncSlot;
        lastActionTime = now;
    }

    /**
     * Moves the item in {@code slotIndex} to the offhand slot using
     * {@link SlotActionType#SWAP} with button 40.
     *
     * Internally this is the same packet Minecraft sends when you press F over
     * a slot — it does NOT involve any keyboard state.
     *
     * @param slotIndex the ScreenHandler slot index of the item to move
     */
    private void moveToOffhand(MinecraftClient client,
                               ClientPlayerEntity player,
                               ScreenHandler handler,
                               int slotIndex) {
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null) return;

        LOGGER.info("[TotemOffhand] Moving totem from slot {} to offhand.", slotIndex);

        // button=40 is the Minecraft-internal constant for the offhand swap action.
        // SlotActionType.SWAP tells the server to swap slot[slotIndex] with the offhand.
        im.clickSlot(
                handler.syncId,   // sync ID that ties client screen to server container
                slotIndex,        // which slot to act on
                40,               // 40 = offhand swap button constant
                SlotActionType.SWAP,
                player
        );
    }

    /**
     * Moves the item in {@code slotIndex} to a hotbar slot using
     * {@link SlotActionType#SWAP} with button = hotbar index (0-8).
     *
     * @param slotIndex    the ScreenHandler slot index of the item to move
     * @param hotbarIndex  the hotbar slot to target (0 = slot 1, 1 = slot 2, …)
     */
    private void moveToHotbarSlot(MinecraftClient client,
                                   ClientPlayerEntity player,
                                   ScreenHandler handler,
                                   int slotIndex,
                                   int hotbarIndex) {
        ClientPlayerInteractionManager im = client.interactionManager;
        if (im == null) return;

        // Validate hotbar index range
        if (hotbarIndex < 0 || hotbarIndex > 8) {
            LOGGER.warn("[TotemOffhand] Invalid hotbar index {}, aborting.", hotbarIndex);
            return;
        }

        // Check the target hotbar slot: only move if it is empty to avoid
        // accidentally overwriting a useful item the player already has there.
        ItemStack hotbarStack = player.getInventory().getStack(hotbarIndex);
        if (!hotbarStack.isEmpty()) {
            LOGGER.info("[TotemOffhand] Hotbar slot {} is occupied ({}), skipping extra totem.",
                    hotbarIndex, hotbarStack.getItem());
            return;
        }

        LOGGER.info("[TotemOffhand] Offhand already has totem – moving hovered totem to hotbar slot {}.",
                hotbarIndex + 1);

        // button = hotbarIndex: SlotActionType.SWAP swaps the hovered slot with that hotbar slot.
        im.clickSlot(
                handler.syncId,
                slotIndex,
                hotbarIndex,
                SlotActionType.SWAP,
                player
        );
    }

    /**
     * Retrieves the {@link Slot} that the mouse cursor is currently hovering
     * over by accessing the protected {@code focusedSlot} field via the mixin
     * accessor interface defined in {@link HandledScreenAccessor}.
     *
     * Returns {@code null} if no slot is focused or if the accessor is unavailable.
     */
    private Slot getFocusedSlot(HandledScreen<?> screen) {
        if (screen instanceof HandledScreenAccessor accessor) {
            return accessor.getFocusedSlot();
        }
        return null;
    }
}
