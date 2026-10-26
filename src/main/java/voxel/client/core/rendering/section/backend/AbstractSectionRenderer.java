package voxel.client.core.rendering.section.backend;


import voxel.client.core.AbstractRenderPipeline;
import voxel.client.core.vk.shader.Shader;
import voxel.client.core.vk.shader.ShaderType;
import voxel.client.core.model.ModelStore;
import voxel.client.core.rendering.Viewport;
import voxel.client.core.rendering.section.geometry.BasicSectionGeometryData;
import voxel.client.core.rendering.section.geometry.IGeometryData;
import voxel.common.Logger;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4fc;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

//Takes in mesh ids from the hierarchical traversal and may perform more culling then renders it
public abstract class AbstractSectionRenderer <T extends Viewport<T>, J extends IGeometryData> {
    public interface FactoryConstructor<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData> {
        AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline pipeline, ModelStore modelStore, GEODATA geometryData);
    }

    public record Factory<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData>(Class<? extends AbstractSectionRenderer<VIEWPORT, GEODATA>> clz, FactoryConstructor<VIEWPORT, GEODATA> constructor) {
        public AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline pipeline, ModelStore store, IGeometryData geometryData) {
            return this.constructor.create(pipeline, store, (GEODATA) geometryData);
        }

        public static <VIEWPORT2 extends Viewport<VIEWPORT2>, GEODATA2 extends IGeometryData> Factory<VIEWPORT2, GEODATA2> create(Class<? extends AbstractSectionRenderer<VIEWPORT2, GEODATA2>> clz) {
            var constructors = clz.getConstructors();
            if (constructors.length != 1) {
                Logger.error("Render backend " + clz.getCanonicalName() + " had more then 1 constructor");
                return null;
            }
            var constructor = constructors[0];
            var params = constructor.getParameterTypes();
            if (params.length != 3 || params[0] != AbstractRenderPipeline.class || params[1] != ModelStore.class || !IGeometryData.class.isAssignableFrom(params[2])) {
                Logger.error("Render backend " + clz.getCanonicalName() + " had invalid constructor");
                return null;
            }
            return new Factory<>(clz, (a,b,c)-> {
                try {
                    return (AbstractSectionRenderer<VIEWPORT2, GEODATA2>) constructor.newInstance(a,b,c);
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }


    protected final J geometryManager;
    protected final ModelStore modelStore;
    protected AbstractSectionRenderer(ModelStore modelStore, J geometryManager) {
        this.geometryManager = geometryManager;
        this.modelStore = modelStore;
    }

    public abstract void renderOpaque(T viewport);
    public abstract void buildDrawCalls(T viewport);
    public abstract void renderTranslucent(T viewport);

    /**
     * Draws the viewport's current opaque draw list into Beryl's shadow map (its render pass is active).
     * {@code lightSpace} is Beryl's camera-relative light-space matrix.
     */
    public void renderShadowCasters(T viewport, Matrix4fc lightSpace,
                                    double cameraX, double cameraY, double cameraZ) {
    }

    public abstract T createViewport();
    public abstract void free();

    public J getGeometryManager() {
        return this.geometryManager;
    }

    public void addDebug(List<String> lines) {}

    protected static <T extends Shader> T tryCompilePatchedOrNormal(Shader.Builder<T> builder, String shader, String original) {
        boolean patched = shader != original;//This is the correct comparison type (reference)
        try {
            return builder.clone()
                    .defineIf("PATCHED_SHADER", patched)
                    .addSource(ShaderType.FRAGMENT, shader)
                    .compile();
        } catch (RuntimeException e) {
            if (patched) {
                Logger.error("Failed to compile shader patch, using normal pipeline to prevent errors", e);
                return tryCompilePatchedOrNormal(builder, original, original);
            } else {
                throw e;
            }
        }
    }
}
