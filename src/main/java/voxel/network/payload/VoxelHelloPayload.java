package voxel.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Handshake payload sent when player connects to a server.
 */
public record VoxelHelloPayload(int protocolVersion, int maxStreamingRadiusChunks, boolean serverStreamingEnabled) implements CustomPacketPayload {
    public static final Type<VoxelHelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxel", "hello"));
    public static final StreamCodec<FriendlyByteBuf, VoxelHelloPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.protocolVersion);
                buf.writeVarInt(payload.maxStreamingRadiusChunks);
                buf.writeBoolean(payload.serverStreamingEnabled);
            },
            buf -> new VoxelHelloPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
