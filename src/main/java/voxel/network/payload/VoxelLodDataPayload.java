package voxel.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import voxel.common.world.SectionSerializationUtil;
import net.minecraft.resources.Identifier;

/**
 * Payload sent from Server to Client containing compressed Voxel DAG LOD data for a section.
 */
public record VoxelLodDataPayload(long sectionKey, int detailLevel, byte[] compressedVoxelData) implements CustomPacketPayload {
    public static final Type<VoxelLodDataPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("voxel", "lod_data"));
    public static final StreamCodec<FriendlyByteBuf, VoxelLodDataPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeLong(payload.sectionKey);
                buf.writeVarInt(payload.detailLevel);
                buf.writeByteArray(payload.compressedVoxelData);
            },
            buf -> new VoxelLodDataPayload(buf.readLong(), buf.readVarInt(),
                    buf.readByteArray(SectionSerializationUtil.BIGGEST_SERIALIZED_SECTION_SIZE))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
