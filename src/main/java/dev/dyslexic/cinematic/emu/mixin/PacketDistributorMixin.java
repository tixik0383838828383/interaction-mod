package dev.dyslexic.cinematic.emu.mixin;

import dev.dyslexic.cinematic.emu.Emu;
import dev.dyslexic.cinematic.net.StationActionPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client->server action of the mod goes through PacketDistributor.sendToServer; answer them locally on vanilla servers. */
@Mixin(targets = "dev.dyslexic.cinematic.net.PacketDistributor", remap = false)
public abstract class PacketDistributorMixin {
    @Inject(method = "sendToServer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cinematic$emulate(CustomPacketPayload payload, CallbackInfo ci) {
        if (payload instanceof StationActionPayload action && Emu.emulating()) {
            Emu.handle(action);
            ci.cancel();
        }
    }
}
