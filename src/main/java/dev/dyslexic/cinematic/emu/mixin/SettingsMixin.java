package dev.dyslexic.cinematic.emu.mixin;

import dev.dyslexic.cinematic.emu.Emu;
import dev.dyslexic.cinematic.station.StationKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The campfire scene needs server-side storage, so it is off on vanilla servers. */
@Mixin(targets = "dev.dyslexic.cinematic.client.Settings", remap = false)
public abstract class SettingsMixin {
    @Inject(method = "scene", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cinematic$noCampfire(StationKind kind, CallbackInfoReturnable<Boolean> cir) {
        if (kind == StationKind.CAMPFIRE && Emu.emulating()) {
            cir.setReturnValue(false);
        }
    }
}
