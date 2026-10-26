#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 7
#define LIGHT_AO_BINDING 12 // keep in sync: Java binds lightBuffer via ssbo(12, ...)

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxel:lod/quad_format.glsl>
#import <voxel:lod/block_model.glsl>
#import <voxel:lod/vk/bindings.glsl>
#import <voxel:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif
layout(location = 2) out vec3 vRelPos;
layout(location = 3) out float vSky;
layout(location = 4) out float vBlock;
layout(location = 5) out float vAO;

layout(std430, binding = LIGHT_AO_BINDING) readonly buffer LightAOData {
    //per corner: sky(4)|block(4)|AO(2)|pad(6); for lava quads the high byte of
    // each corner carries a signed Y flow-height offset instead (Java side:
    // RenderDataFactory.applyLavaCornerY).
    uvec2 lightAO[];
};

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

#ifdef SHADOW_PASS
// Beryl shadow.vsh: distortShadowClipPos, then z clamped (orthographic, w = 1).
vec4 shadowCasterDistort(vec4 clip) {
    clip.xy /= length(clip.xy) * shadowCasterParams.x + (1.0f - shadowCasterParams.x);
    clip.z = clamp((clip.z + 0.5f) * 0.5f, 0.0f, 1.0f);
    return clip;
}

// Receivers only read the map inside Beryl's light-space box, so whole LOD
// sections outside it are skipped.
bool shadowCasterSectionOutside(uvec2 sPos) {
    uint lodLevel = extractDetail(sPos);
    float halfSize = 16.0f * float(1u << lodLevel);
    vec3 centre = vec3(((extractLoDPosition(sPos) << lodLevel) - LOD_BASE_SECTION_POS) << 5) + halfSize;
    vec2 lightXY = (LOD_MVP * vec4(centre, 1.0f)).xy;
    return any(greaterThan(abs(lightXY) - halfSize * 1.7321f * shadowCasterParams.y, vec2(1.0f)));
}
#endif

vec2 taaShift();

//TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

    #ifdef SHADOW_PASS
    if (shadowCasterSectionOutside(positionBuffer[gl_BaseInstance])) {
        gl_Position = vec4(-2.0f, -2.0f, -2.0f, 1.0f);
        return;
    }
    #endif

    uint quadIndex = uint(gl_VertexID) >> 2;
    // Coarse sea-level water is placed at the cell top by the mesher; lower it
    // onto the real block surface. Non-water quads carry 0 in this pad nibble.
    uint waterTopOffset = (lightAO[quadIndex].x >> 11) & 0x1Fu;
    QuadData quad;
    setupQuad(quad, quadData[quadIndex], positionBuffer[gl_BaseInstance], (gl_VertexID&3) == 1);
    // Lava uses the same pad bytes for its per-corner flow heights; only water
    // consumes this world-space offset.
    if (!quad.fluidFlow) {
        quad.basePoint.y -= float(waterTopOffset);
    }

    #ifdef SHADOW_PASS
    // Every face casts, not only the ones facing the camera.
    quad.culled = false;
    #endif

    uint cornerId = gl_VertexID&3;

    uint lightAOPacked = (cornerId < 2u) ? lightAO[uint(gl_VertexID)>>2].x : lightAO[uint(gl_VertexID)>>2].y;
    uint lightAOBits = (lightAOPacked >> ((cornerId & 1u) * 16u)) & 0xFFFFu;

    float lavaYOffset = 0.0f;
    if (quad.fluidFlow) {
        uint bits = (lightAOBits >> 8) & 0xFFu;
        float off = (bits >= 128u) ? (float(bits) - 256.0f) : float(bits);
        lavaYOffset = off * (1.0f / 64.0f);
    }

    gl_Position = getQuadCornerPos(quad, cornerId, lavaYOffset, vRelPos);
    #ifdef SHADOW_PASS
    gl_Position = shadowCasterDistort(gl_Position);
    #endif

    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);

    if (quad.lavaFlowTile && quad.face == 1u) {
        uvec2 lao = lightAO[uint(gl_VertexID)>>2];
        float h0 = float((int(lao.x >> 8)  << 24) >> 24);
        float h1 = float((int(lao.x >> 24) << 24) >> 24);
        float h2 = float((int(lao.y >> 8)  << 24) >> 24);
        float h3 = float((int(lao.y >> 24) << 24) >> 24);
        vec2 flow = vec2(h0 + h1 - h2 - h3, h0 + h2 - h1 - h3);
        if (dot(flow, flow) > 0.0f) {
            float angle = atan(flow.y, flow.x) - 1.57079633f;
            float sinA = sin(angle) * 0.25f;
            float cosA = cos(angle) * 0.25f;
            vec2 windowUv;
            switch (cornerId) {
                case 0u: windowUv = vec2(0.5f - cosA - sinA, 0.5f - cosA + sinA); break;
                case 1u: windowUv = vec2(0.5f - cosA + sinA, 0.5f + cosA + sinA); break;
                case 2u: windowUv = vec2(0.5f + cosA - sinA, 0.5f - cosA - sinA); break;
                default: windowUv = vec2(0.5f + cosA + sinA, 0.5f + cosA - sinA); break;
            }
            uv = quad.uvCorner + windowUv * quad.quadSizeAddin;
        }
    }
    #endif

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;

    vSky = float(lightAOBits & 0xFu);
    vBlock = quad.fluidFlow ? 15.0f : float((lightAOBits >> 4) & 0xFu);
    vAO = quad.fluidFlow ? 1.0f : float((lightAOBits >> 8) & 3u) / 3.0f;


    #ifdef DEBUG_RENDER
    quadDebug = uint(gl_VertexID)>>(2+5);
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif