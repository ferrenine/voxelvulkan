package voxel.client.core.model;

/**
 * The subset of {@link ModelFactory} that the mesher needs.
 *
 * <p>Keeping this as an interface lets {@code RenderDataFactory} run against a
 * deterministic, GPU-free lookup in tests. The production implementation is
 * still {@link ModelFactory}; no baking or texture state is exposed here.</p>
 */
public interface ModelLookup {
    int getModelIdAcquire(int blockId);

    int getModelId(int blockId);

    long getModelMetadataFromClientId(int clientId);

    int getStairStepModelId(int clientModelId);

    int getLambdabettergrassVariant(int clientModelId);

    int getFluidClientStateId(int clientBlockStateId);

    int lavaFlowModelId();
}
