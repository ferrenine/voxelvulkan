package voxel.network;

import voxel.client.network.ClientLodStreamManager;
import voxel.common.Logger;
import voxel.network.payload.VoxelHelloPayload;
import voxel.network.payload.VoxelLodDataPayload;

/**
 * Dispatches and processes incoming Voxel network payloads on client.
 */
public class VoxelNetworkHandler {

    public static void handleHello(VoxelHelloPayload payload) {
        Logger.info("Received Voxel Server Hello: protocol=" + payload.protocolVersion() +
                ", streamingRadius=" + payload.maxStreamingRadiusChunks() +
                ", enabled=" + payload.serverStreamingEnabled());
        ClientLodStreamManager.onServerHello(payload);
    }

    public static void handleLodData(VoxelLodDataPayload payload) {
        ClientLodStreamManager.onLodDataReceived(payload);
    }
}
