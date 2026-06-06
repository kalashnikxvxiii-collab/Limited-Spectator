package com.karashi.limitedspectator.network;

import com.karashi.limitedspectator.SpectatorMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkHandler.class);
    public static final Identifier HUD_PACKET_ID =
            Identifier.fromNamespaceAndPath(SpectatorMod.MODID, "hud_toggle");

    // Packet Registration.
    // The channel is registered as optional() so vanilla clients (and clients without
    // the mod installed) can still connect — the server is the authoritative side and
    // the HUD-sync packet is a UX enhancement, not a hard requirement.
    public static void register(RegisterPayloadHandlersEvent event) {
        LOGGER.info("Registering network packets...");

        PayloadRegistrar registrar = event.registrar(SpectatorMod.MODID).optional();

        registrar.playToClient(
                SpectatorHudPacket.TYPE,
                SpectatorHudPacket.STREAM_CODEC,
                SpectatorHudPacket::handle
        );

        LOGGER.info("SpectatorHudPacket registered successfully (optional channel).");
    }

    // Customized packet
    public static class SpectatorHudPacket implements CustomPacketPayload {
        public static final Type<SpectatorHudPacket> TYPE = new Type<>(HUD_PACKET_ID);

        // Fixed StreamCodec for NeoForge 1.21+
        public static final StreamCodec<FriendlyByteBuf, SpectatorHudPacket> STREAM_CODEC =
                StreamCodec.ofMember(SpectatorHudPacket::write, SpectatorHudPacket::new);

        private final boolean hideHud;

        public SpectatorHudPacket(boolean hideHud) {
            this.hideHud = hideHud;
        }

        // Constructor used by the codec
        public SpectatorHudPacket(FriendlyByteBuf buf) {
            this.hideHud = buf.readBoolean();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(hideHud);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        // Fixed handler for NeoForge 1.21+
        public static void handle(SpectatorHudPacket msg, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                // Delegate to ClientEventHandler for proper HUD management
                com.karashi.limitedspectator.client.ClientEventHandler.setHudHidden(msg.hideHud);
            });

            // FOR DEBUGGING
            // LOGGER.debug("HUD packet received → HUD {}", msg.hideHud ? "hidden" : "visible");
        }
    }

    // Sending the packet from the server to the client.
    // Channel is optional() — vanilla clients won't advertise support; skip them silently
    // instead of triggering a "channel not registered" error mid-game.
    public static void sendHudState(ServerPlayer player, boolean hideHud) {
        if (player == null) return;
        if (!player.connection.hasChannel(SpectatorHudPacket.TYPE)) return;
        PacketDistributor.sendToPlayer(player, new SpectatorHudPacket(hideHud));
    }
}
