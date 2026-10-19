package dev.dyslexic.cinematic.emu.mixin;

import dev.dyslexic.cinematic.emu.Emu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla crafting-table / furnace GUI that the server opens for an emulated scene. */
@Mixin(value = Minecraft.class, priority = 500)
public abstract class MinecraftMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void cinematic$emuHide(Screen screen, CallbackInfo ci) {
        if (Emu.hidesScreen(screen)) {
            ci.cancel();
        }
    }
}
