layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec3 baseSectionPos;
    uint frameId;
    vec3 cameraSubPos;
    float uEarthRadius; // 0.0 = disabled, otherwise radius in blocks for world curvature
    vec4 uBerylLightDir;     // xyz = lightDirWS, w = berylActive (1.0 or 0.0)
    vec4 uBerylLightColor;   // rgb = LightColor, a = LightIntensity
    vec4 uBerylAmbientLight; // rgb = AmbientLight, a = LightVisibility
    vec4 uBerylSkyColor;     // rgba = SkyColor
    vec4 uBerylParams;       // x = AmbientLightFactor, y = NightMultiplier, z = MinAmbientLight, w = FogFactor
    mat4 uLightSpaceMat;     // Beryl shadow-map light-space matrix (camera-relative)
    vec4 uShadowParams;      // x = bias, y = distortion, z = texel size, w = active
    vec3 uLightSpaceOffset;  // Beryl LightSpaceOffset
    // Live ClientLevel.getShade values, i.e. the face shade of the near-field
    // chunk builder (Beryl forces 1.0 while its pipeline is active).
    float uNoShadeFaceTint;  // getShade(UP, false)
    vec4 uFaceShade;         // getShade(face, true): x = up, y = down, z = north/south, w = east/west
    float uDebugShadow;      // 1 = show the shadow factor on the terrain (debug)
};

#ifdef SHADOW_PASS
// LOD casters in Beryl's shadow pass (MDICSectionRenderer.renderShadowCasters).
// This frame's camera and Beryl's light-space transform come as push constants;
// the scene UBO still holds the previous main pass and is only used for
// position independent values.
layout(push_constant) uniform ShadowCasterPush {
    mat4 shadowCasterMVP;     // Beryl light-space matrix * translate(-shadowCameraSubPos)
    ivec4 shadowBaseSectionPos;
    vec4 shadowCameraSubPos;
    vec4 shadowCasterParams;  // x = Beryl shadow distortion, y = 1 / light-space half extent in blocks
};
#define LOD_MVP shadowCasterMVP
#define LOD_BASE_SECTION_POS (shadowBaseSectionPos.xyz)
#define LOD_CAMERA_SUB_POS (shadowCameraSubPos.xyz)
#else
#define LOD_MVP MVP
#define LOD_BASE_SECTION_POS baseSectionPos
#define LOD_CAMERA_SUB_POS cameraSubPos
#endif

//TODO: see if making the stride 2*4*4 bytes or something cause you get that 16 byte write
struct DrawCommand {
    uint  count;
    uint  instanceCount;
    uint  firstIndex;
    int  baseVertex;
    uint  baseInstance;
};


#ifdef BLOCK_MODEL_TEXTURE_BINDING
layout(binding = BLOCK_MODEL_TEXTURE_BINDING) uniform sampler2D blockModelAtlas;
#endif


//AUDIT-NOTE: the std140 field order above is hand-aligned to the bare-writes
// in MDICSectionRenderer.uploadUniformBuffer (offsets 176/240/256/268/288) —
// reordering a field silently corrupts data; update both sides together.
#ifdef QUAD_BUFFER_BINDING
#ifndef Quad
//AUDIT-FIXME: Quad used to default to ivec2 — a shader that defines
// QUAD_BUFFER_BINDING but lacks int64 would read 8-byte quads with the wrong
// stride. Fail loudly instead of a silent wrong-stride read.
#error "Quad type undefined — consumer must define it (uint64_t requires GL_ARB_gpu_shader_int64)"
#endif
layout(binding = QUAD_BUFFER_BINDING, std430) readonly restrict buffer QuadBuffer {
    Quad quadData[];
};
#endif

#ifdef DRAW_BUFFER_BINDING
layout(binding = DRAW_BUFFER_BINDING, std430) writeonly restrict buffer DrawBuffer {
    DrawCommand cmdBuffer[];
};
#endif

#ifdef DRAW_COUNT_BUFFER_BINDING
layout(binding = DRAW_COUNT_BUFFER_BINDING, std430) restrict buffer DrawCommandCountBuffer {
    uint cmdGenDispatchX;
    uint cmdGenDispatchY;
    uint cmdGenDispatchZ;

    uint opaqueDrawCount;
    uint translucentDrawCount;
    uint temporalOpaqueDrawCount;

    DrawCommand cullDrawIndirectCommand;
};
#endif

#ifdef SECTION_METADATA_BUFFER_BINDING
layout(binding = SECTION_METADATA_BUFFER_BINDING, std430) readonly restrict buffer SectionBuffer {
    SectionMeta sectionData[];
};
#endif

#ifdef INDIRECT_SECTION_LOOKUP_BINDING
layout(binding = INDIRECT_SECTION_LOOKUP_BINDING, std430) readonly restrict buffer IndirectSectionLookupBuffer {
    uint sectionCount;
    uint indirectLookup[];
};
#endif

#ifndef VISIBILITY_ACCESS
#define VISIBILITY_ACCESS readonly
#endif
#ifdef VISIBILITY_BUFFER_BINDING
layout(binding = VISIBILITY_BUFFER_BINDING, std430) VISIBILITY_ACCESS restrict buffer VisibilityBuffer {
    uint visibilityData[];
};
#endif

#ifdef MODEL_BUFFER_BINDING
layout(binding = MODEL_BUFFER_BINDING, std430) readonly restrict buffer ModelBuffer {
    BlockModel modelData[];
};
#endif

#ifdef MODEL_COLOUR_BUFFER_BINDING
layout(binding = MODEL_COLOUR_BUFFER_BINDING, std430) readonly restrict buffer ModelColourBuffer {
    uint colourData[];
};
#endif

#ifdef POSITION_SCRATCH_BINDING
#ifndef POSITION_SCRATCH_ACCESS
#define POSITION_SCRATCH_ACCESS readonly
#endif
layout(binding = POSITION_SCRATCH_BINDING, std430) POSITION_SCRATCH_ACCESS restrict buffer PositionScratchBuffer {
    uvec2 positionBuffer[];
};
#endif

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;

vec4 getLighting(uint index) {
    int i2 = int(index);
    vec2 lightCoords = (vec2((i2>>4)&0xF, i2&0xF) * 16.0 + 8.0) / 256.0;
    return texture(lightSampler, clamp(lightCoords, vec2(8.0f/256.0), vec2(248.0f/256.0)));
}
#endif

