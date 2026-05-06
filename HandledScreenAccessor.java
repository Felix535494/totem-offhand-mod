package com.totemoffhand;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor that exposes the protected {@code focusedSlot} field
 * of {@link HandledScreen} so our client code can read which slot the
 * cursor is currently hovering over — without reflection or key simulation.
 *
 * Mixins are the standard Fabric way to access internals of Minecraft classes.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    /**
     * Returns the slot currently under the mouse cursor, or {@code null}
     * if no slot is focused.
     */
    @Accessor("focusedSlot")
    Slot getFocusedSlot();
}
