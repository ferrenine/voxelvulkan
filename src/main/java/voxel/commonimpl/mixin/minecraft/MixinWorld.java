package voxel.commonimpl.mixin.minecraft;

import voxel.commonimpl.IWorldGetIdentifier;
import voxel.commonimpl.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public class MixinWorld implements IWorldGetIdentifier {
    @Unique
    private WorldIdentifier identifier;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxel$injectIdentifier(WritableLevelData levelData,
                                       ResourceKey<Level> dimension,
                                       RegistryAccess registryAccess,
                                       Holder<DimensionType> dimensionTypeRegistration,
                                       boolean isClientSide,
                                       boolean isDebug,
                                       long biomeZoomSeed,
                                       int maxChainedNeighborUpdates,
                                       CallbackInfo ci) {
        if (dimension != null) {
            this.identifier = new WorldIdentifier(dimension, biomeZoomSeed, dimensionTypeRegistration == null ? null : dimensionTypeRegistration.unwrapKey().orElse(null));
        } else {
            this.identifier = null;
        }
    }

    @Override
    public WorldIdentifier voxel$getIdentifier() {
        return this.identifier;
    }
}
