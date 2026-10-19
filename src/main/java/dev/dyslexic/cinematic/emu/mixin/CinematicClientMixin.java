package dev.dyslexic.cinematic.emu.mixin;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the "must be installed on both the client and the server" kick (the JOIN handler lambda). */
@Mixin(targets = "dev.dyslexic.cinematic.client.CinematicClient", remap = false)
public abstract class CinematicClientMixin {
    @Inject(method = "lambda$onInitializeClient$17", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cinematic$noKick(ClientPacketListener handler, PacketSender sender, Minecraft client, CallbackInfo ci) {
        ci.cancel();
    }
}
