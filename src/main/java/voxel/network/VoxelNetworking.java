package voxel.network;

import voxel.network.payload.VoxelHelloPayload;
import voxel.network.payload.VoxelLodDataPayload;
import voxel.network.payload.VoxelLodRequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Registers Voxel network channels and payloads on Fabric.
 */
public class VoxelNetworking {
    public static final int PROTOCOL_VERSION = 1;

    public static void register() {
        // Payload types must be registered on both ends of each direction.
        PayloadTypeRegistry.playS2C().register(VoxelHelloPayload.TYPE, VoxelHelloPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(VoxelLodDataPayload.TYPE, VoxelLodDataPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(VoxelLodRequestPayload.TYPE, VoxelLodRequestPayload.STREAM_CODEC);

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientPlayNetworking.registerGlobalReceiver(VoxelHelloPayload.TYPE, (payload, context) ->
                    context.client().execute(() -> VoxelNetworkHandler.handleHello(payload)));

            ClientPlayNetworking.registerGlobalReceiver(VoxelLodDataPayload.TYPE, (payload, context) ->
                    context.client().execute(() -> VoxelNetworkHandler.handleLodData(payload)));
        }
    }
}
