package dev.dyslexic.cinematic.emu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client entrypoint of the add-on: drives the server-less emulation of Cinematic Interactions. */
public final class EmuInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(Emu::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Emu.reset());
    }
}
