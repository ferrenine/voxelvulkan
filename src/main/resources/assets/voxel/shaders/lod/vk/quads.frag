#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
//AUDIT-DEAD: USE_SINGLE_TRI / USE_NV_BARRY are unreachable — the Java define is
// commented out (MDICSectionRenderer) and nvBarryCoords is hardcoded false.
//AUDIT-DEAD: every PATCHED_SHADER region in this file is unreachable —
// patchOpaqueShader/patchTranslucentShader return null on the Java side until
// a resource-pack patch mechanism lands.
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 6) uniform sampler2D blockModelAtlas;
layout(binding = 7) uniform sampler2D lightSampler;
layout(binding = 8) uniform sampler2D depthTex;
layout(binding = 11) uniform sampler2D berylShadowMap;
#ifdef TRANSLUCENT
layout(binding = 9) uniform sampler2D ssrColourTex;
layout(binding = 10) uniform sampler2D ssrDepthTex;
#endif

// #define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif
layout(location = 2) in vec3 vRelPos;
layout(location = 3) in float vSky;
layout(location = 4) in float vBlock;
layout(location = 5) in float vAO;

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxel:lod/block_model.glsl>

#endif

#import <voxel:lod/vk/bindings.glsl>

vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}
//AUDIT-NOTE: binding literals 6..11 here vs the *_BINDING defines used in
// quads3.vert — two conventions coexist; keep them aligned when adding a
// binding (there is no single source of truth yet).

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}

#ifdef PATCHED_SHADER
vec2 getLightmap() {
    return clamp((vec2((interData.y>>4)&0xFu, interData.y&0xFu) * 16.0 + 8.0) / 256.0, vec2(8.0f/256.0), vec2(248.0f/256.0));
}
#endif

uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


// 4x4 Bayer Dithering Matrix for smooth LOD boundary transitions (inspired by Distant Horizons)
float bayerMatrix4x4(vec2 st) {
    int x = int(mod(st.x, 4.0));
    int y = int(mod(st.y, 4.0));
    const float bayer4x4[16] = float[16](
         0.0/16.0,  8.0/16.0,  2.0/16.0, 10.0/16.0,
        12.0/16.0,  4.0/16.0, 14.0/16.0,  6.0/16.0,
         3.0/16.0, 11.0/16.0,  1.0/16.0,  9.0/16.0,
        15.0/16.0,  7.0/16.0, 13.0/16.0,  5.0/16.0
    );
    return bayer4x4[y * 4 + x];
}

// Natural surface micro-variation (breaks up flat plastic look on distant terrain)
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void applyNaturalMicroVariation(inout vec3 rgb, vec2 screenPos) {
    float noise = hash21(floor(screenPos * 0.5)) * 0.05 - 0.025; // +/- 2.5% subtle organic surface noise
    rgb = clamp(rgb + vec3(noise), 0.0, 1.0);
}

#ifdef PATCHED_SHADER
// Voxel fragment output parameters
struct VoxelFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId; // Corresponds to Beryl materialId
};

void voxel_emitFragment(VoxelFragmentParameters parameters);
#else

vec3 berylWaterSkyColor(float RdotL, float RdotUp, float SoUp) {
    float f1 = 1.0f - 0.25f / (40.0f * RdotUp * RdotUp + 0.25f);
    // Beryl's exact FogColor. Reconstructing it from NightFactor gave the
    // encoded colour, not the gamma-decoded value VulkanMod uploads under the
    // HDR pipeline; at night that was ~7x too bright and turned grazing water
    // into a white sheet.
    vec3 col = mix(uBerylFogColor.rgb, uBerylSkyColor.rgb, f1);

    float m1 = min((1.0f - SoUp) * 1.15f, 1.0f);
    m1 = m1 * m1;
    m1 = m1 * m1;

    float m2 = min((1.0f - RdotUp) * 1.15f, 1.0f);
    m2 = m2 * m2 * m1;
    m2 *= min(RdotL * 0.9f + 0.1f, 1.0f);
    m2 *= uBerylAmbientLight.w * (1.0f - uBerylParams.y);

    col = mix(col, vec3(0.75f, 0.27f, 0.15f), m2);
    float mieF = 10.0f + 200.0f * uBerylAmbientLight.w;
    col += uBerylLightColor.rgb * 0.2f * min(1.0f / ((1.0f - RdotL) * mieF + 1.0f), 1.0f);
    return col;
}

#ifdef TRANSLUCENT
vec3 waterWaveNormal(vec2 worldXZ, float dist) {
    float fade = 1.0f - smoothstep(48.0f, 320.0f, dist);
    if (fade <= 0.001f) {
        return vec3(0.0f, 1.0f, 0.0f);
    }
    vec2 p1 = worldXZ * 1.35f;
    vec2 p2 = worldXZ * 3.10f + vec2(1.7f, -2.3f);
    float wx = (sin(p1.x + cos(p1.y * 0.8f)) * 0.6f + cos(p2.x * 1.1f - p2.y) * 0.4f) * 0.012f * fade;
    float wz = (cos(p1.y + sin(p1.x * 0.8f)) * 0.6f + sin(p2.y * 1.1f + p2.x) * 0.4f) * 0.012f * fade;
    return normalize(vec3(wx, 1.0f, wz));
}

vec3 traceWaterSSR(vec3 worldPos, vec3 R, vec3 skyColor) {
    vec4 startClip = MVP * vec4(worldPos, 1.0f);
    if (startClip.w <= 0.1f) {
        return skyColor;
    }
    vec3 startNDC = startClip.xyz / startClip.w;
    vec4 clipR = MVP * vec4(R, 0.0f);
    float waterDist = length(vRelPos);

    // Exact perspective-correct clip-space raymarching with fine near-shore steps (no Bayer stipple blur).
    // The old trace stopped at 140 blocks, so water inside the vanilla ring reflected
    // Beryl's distant mountains while the same water outside it fell back to sky.
    // Beryl's adaptive SSR reaches far beyond that; use a wider, denser trace so the
    // far LOD sees the same terrain reflection as the near chunks.
    const int STEPS = 48;
    const float MAX_TRACE_DIST = 1024.0f;
    float prevDist = 0.0f;
    float prevRayZ = startNDC.z;
    vec2 hitUV = vec2(0.0f);
    bool hit = false;

    for (int i = 1; i <= STEPS; i++) {
        float t = float(i) / float(STEPS);
        float dist = 0.35f + MAX_TRACE_DIST * t * t;
        vec4 clip = startClip + clipR * dist;
        if (clip.w <= 0.1f) {
            break;
        }
        vec3 rayNDC = clip.xyz / clip.w;
        vec2 rayUV = rayNDC.xy * vec2(0.5f, -0.5f) + 0.5f;
        if (any(lessThan(rayUV, vec2(0.002f))) || any(greaterThan(rayUV, vec2(0.998f))) || rayNDC.z >= 1.0f || rayNDC.z <= 0.0f) {
            break;
        }
        float sceneDepth = textureLod(ssrDepthTex, rayUV, 0.0f).r;
        if (sceneDepth > startNDC.z + 0.00003f && sceneDepth < 1.0f && rayNDC.z >= sceneDepth) {
            if (prevRayZ <= sceneDepth + 0.00020f) {
                float lo = prevDist;
                float hi = dist;
                for (int j = 0; j < 4; j++) {
                    float mid = (lo + hi) * 0.5f;
                    vec4 mClip = startClip + clipR * mid;
                    vec3 mNDC = mClip.xyz / max(mClip.w, 1e-4f);
                    vec2 mUV = mNDC.xy * vec2(0.5f, -0.5f) + 0.5f;
                    float bd = textureLod(ssrDepthTex, mUV, 0.0f).r;
                    if (bd > startNDC.z + 0.00003f && bd < 1.0f && mNDC.z >= bd) {
                        hi = mid;
                        rayUV = mUV;
                        rayNDC.z = mNDC.z;
                        sceneDepth = bd;
                    } else {
                        lo = mid;
                    }
                }
                float thicknessBlocks = waterDist * ((1.0f - sceneDepth) / max(1.0f - rayNDC.z, 1e-7f) - 1.0f);
                if (thicknessBlocks >= -0.6f && thicknessBlocks < 4.0f) {
                    hitUV = rayUV;
                    hit = true;
                }
                break;
            }
        }
        prevDist = dist;
        prevRayZ = rayNDC.z;
    }

    if (!hit) {
        return skyColor;
    }
    vec2 dCoords = smoothstep(0.45f, 0.50f, abs(vec2(0.5f, 0.5f) - hitUV));
    float edgeFade = clamp(1.0f - (dCoords.x + dCoords.y), 0.0f, 1.0f);
    vec4 hitCol = textureLod(ssrColourTex, hitUV, 0.0f);
    if (dot(hitCol.rgb, hitCol.rgb) < 1e-5f) {
        return skyColor;
    }
    return mix(skyColor, hitCol.rgb, edgeFade);
}
#endif

// Beryl's shadow map contains the near-field casters (including the hill that
// shades the LOD boundary). Sample it for far LOD receivers within the shadow
// map's coverage so the near and far shadows are continuous. Outside the map,
// or when Beryl shadows are off, fall back to the per-voxel sunShadow bit.
float sampleBerylShadow(vec3 worldPos, vec3 N, vec3 L) {
    if (uShadowParams.w < 0.5f) {
        return -1.0f;
    }
    // Match Beryl's terrain.vsh receiver transform. LOD geometry now writes
    // the same shadow map; voxel-sized receiver offsets skip canopy blockers.
    vec3 pos = worldPos - uLightSpaceOffset * max(N.y, 0.0f);
    vec4 clip = uLightSpaceMat * vec4(pos, 1.0f);
    // Casters only exist inside the light-space box (Beryl's chunks and the
    // LOD caster pass); the distortion maps farther points into the map's
    // corners, where they would read unrelated depths.
    if (max(abs(clip.x), abs(clip.y)) > 1.0f) {
        return -1.0f;
    }
    clip.z -= uShadowParams.x;

    float radial = length(clip.xy);
    float distortion = radial * uShadowParams.y + (1.0f - uShadowParams.y);
    clip.xy /= max(distortion, 1e-5f);
    clip.z += 0.5f;
    clip.z *= 0.5f;

    vec3 proj = clip.xyz / clip.w;
    float borderDist = max(abs(proj.x), abs(proj.y));
    proj.y = -proj.y;
    proj.xy = proj.xy * 0.5f + 0.5f;
    if (proj.x < 0.0f || proj.x > 1.0f || proj.y < 0.0f || proj.y > 1.0f
            || proj.z < 0.0f || proj.z > 1.0f) {
        return -1.0f;
    }

    // Same slope and border bias as Beryl's shadowSampling.glsl. An extra
    // full-voxel depth tolerance erases shadows between adjacent leaf layers.
    float bias = (1.0f - max(dot(N, L), 0.0f)) * 0.001f;
    bias += 0.002f * borderDist * borderDist;
    float currentDepth = proj.z - bias;
    vec2 texel = max(clip.w, 0.0f) * uShadowParams.z * vec2(1.0f);

    const vec2 poisson[8] = vec2[](
        vec2(-0.94201624, -0.39906216),
        vec2( 0.94558609, -0.76890725),
        vec2(-0.09418410, -0.92938870),
        vec2( 0.34495938,  0.29387760),
        vec2(-0.91588581,  0.45771432),
        vec2(-0.81544232, -0.87912464),
        vec2(-0.38277543,  0.27676845),
        vec2( 0.97484398,  0.75648379)
    );
    float lit = 0.0f;
    for (int i = 0; i < 8; i++) {
        float stored = textureLod(berylShadowMap, proj.xy + poisson[i] * texel, 0.0f).r;
        lit += (stored >= currentDepth) ? 1.0f : 0.0f;
    }
    lit *= 0.125f;

    // Mirror Beryl's shadow-map border fade.
    float intensity = 1.0f - clamp((borderDist - 0.9f) / 0.1f, 0.0f, 1.0f);
    return 1.0f - (1.0f - lit) * intensity;
}

// Inverse of the hardware sRGB decode.
vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92f, 1.055f * pow(c, vec3(1.0f / 2.4f)) - 0.055f, greaterThan(c, vec3(0.0031308f)));
}

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, AUDIT-FIXME: better but still not great — try encoding
    // data into the top bit of alpha so it's per pixel

    // Under Beryl the atlas is bound through an sRGB view (ModelStore.bind),
    // like Beryl's own block atlas, so the samples are linear there.
    bool beryl = uBerylLightDir.w > 0.5f;

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec3 tintTest = textureLod(blockModelAtlas, texturePos, 0).rgb;
        // The grey test is tuned on the stored (sRGB) texel values.
        if (beryl) {
            tintTest = linearToSrgb(tintTest);
        }
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    // Keep the raw atlas sample and the vertex-colour factors separate: the
    // Beryl path below must reproduce Beryl's exact albedo formula.
    vec3 rawTex = colour.rgb;
    vec4 tintRGBA = uint2vec4RGBA(interData.z).yzwx;
    vec3 tintFactor = doTint ? tintRGBA.rgb : vec3(1.0f);

    // Models without ambient occlusion (cross plants: grass, ferns, flowers)
    // go through vanilla's flat block renderer: no AO, no directional shade,
    // and the light of the block itself instead of the smooth corner light.
    // The translucent encoding has no AO flag, keep the shaded path there.
    #ifdef TRANSLUCENT
    const bool hasAO = true;
    #else
    bool hasAO = (interData.w & 64u) != 0u;
    #endif
    uint lightingFlat = (interData.w >> 16) & 0xFFu;

    // Per-vertex AO written by the mesher (vAO = (3 - occluders) / 3).
    // Vanilla corner shade = (side1 + side2 + corner + centre) / 4 with 0.2
    // for a full block and 1.0 otherwise, i.e. {1.0, 0.8, 0.6, 0.4}. The
    // mesher's smooth light now uses vanilla's blend (solid samples replaced
    // by the centre), so the exact vanilla ladder applies.
    float aoFactor = hasAO ? mix(0.4f, 1.0f, vAO) : 1.0f;
    colour = vec4(rawTex * tintFactor * aoFactor, colour.a);

    if (beryl) {
        uint face = getFace();
        vec3 N = vec3(float((face>>1)==2u), float((face>>1)==0u), float((face>>1)==1u)) * (float(int(face)&1)*2.0f - 1.0f);
        vec3 V = -normalize(vRelPos);

        // Beryl's foliage material (lighting type 1) covers the cross plants.
        // Vanilla draws each plane as two opposite quads, the LOD as one
        // double-sided quad, so face the normal towards the viewer.
        bool isFoliage = !hasAO;
        if (isFoliage && dot(N, V) < 0.0f) {
            N = -N;
        }

        // VulkanMod bakes ClientLevel.getShade into the near-field vertex
        // colour, and Beryl forces that to 1.0 for every face while its
        // pipeline is active. Use the same live values, not the vanilla table.
        float faceShade = !hasAO ? uNoShadeFaceTint
                        : (face == 1u) ? uFaceShade.x
                        : (face == 0u) ? uFaceShade.y
                        : (((face >> 1) == 1u) ? uFaceShade.z : uFaceShade.w);

        // Beryl terrain.fsh: texColor = texture(atlas) * vertexColor, and
        // vertexColor = pow(tint * faceShade * AO, 2.2) in terrain.vsh. The
        // atlas sample is already linear (hardware sRGB decode, see above).
        vec3 vertexColour = tintFactor * aoFactor * faceShade;
        vec3 albedo = rawTex * pow(max(vertexColour, vec3(0.0f)), vec3(2.2f));

        // Beryl's terrain.vsh computes lightY = (UV2.y / 256)^2 where UV2.y is
        // skyLight * 16, i.e. (sky / 16)^2. Using /15 here made the LOD direct
        // sun term 32% brighter than the near field at full sky light
        // (lightZ 1.0 vs Beryl's 0.758) and the ambient 14% brighter.
        float skyL = hasAO ? clamp(vSky / 16.0f, 0.0f, 1.0f) : float(lightingFlat & 0xFu) / 16.0f;
        float blockL = hasAO ? clamp(vBlock / 16.0f, 0.0f, 1.0f) : float((lightingFlat >> 4) & 0xFu) / 16.0f;
        float sunShadow = ((interData.w >> 24) & 1u) != 0u ? 0.0f : 1.0f;
        float sunShadowBit = sunShadow;

        vec3 L = normalize(uBerylLightDir.xyz);
        vec3 worldWPos = vRelPos + cameraSubPos;
        // Beryl's terrain shader builds its light-space position from the
        // camera-relative chunk position (VulkanMod's ChunkOffset), so the
        // LOD must pass vRelPos here as well. Passing worldWPos shifts the
        // projection by the camera translation and puts the shadow in the
        // wrong place.
        // Receive Beryl's shadow map so far LOD terrain sees the same casters
        // as the near chunks (the wall/ground shadow at the LOD boundary).
        // Outside the map keep the baked visibility unchanged: ambient and
        // block light already illuminate shadowed surfaces, as in Beryl.
        float berylShadow = sampleBerylShadow(vRelPos, N, L);
        if (berylShadow >= 0.0f) {
            sunShadow = berylShadow;
        }

        float lightY = skyL * skyL;
        float lightYM = max(lightY * uBerylParams.x - uBerylParams.y, uBerylParams.z);
        float lightX = blockL * blockL * (1.0f - lightYM);

        #ifdef TRANSLUCENT
        float lightZ = max((lightY - 0.5f) * 2.0f, 0.0f) * sunShadow;
        vec3 light = vec3(lightX * 2.0f, lightYM, lightZ);
        if ((interData.w & 1u) != 0u) {
            // Exact Beryl water shading with procedural micro-wave normal perturbation and shoreline contact contrast
            float waterDist = length(vRelPos);
            vec3 waterN = (face <= 1u) ? waterWaveNormal(worldWPos.xz, waterDist) : N;
            vec3 fragDir = vRelPos / max(waterDist, 1e-4f);
            vec3 V = -fragDir;
            if (dot(waterN, V) < 0.0f) {
                waterN = -waterN;
            }
            vec3 R = reflect(fragDir, waterN);

            float RoUp = (face <= 1u) ? max(R.y, 0.0f) : max(R.y, 0.45f);
            float SoUp = max(L.y, 0.0f);
            float RdotL = max(dot(R, L), 0.0f);
            vec3 skyColor = berylWaterSkyColor(RdotL, RoUp, SoUp);
            vec3 reflectedColor = skyColor;
            #ifdef ENABLE_WATER_SSR
            if (face <= 1u) {
                reflectedColor = traceWaterSSR(worldWPos, R, skyColor);
            }
            #endif

            vec3 waterAlbedo = albedo * 0.1f;
            float NdotV = max(dot(waterN, V), 0.0f);
            vec3 F0 = vec3(0.04f);
            vec3 F_refl = F0 + (vec3(1.0f) - F0) * pow(clamp(1.0f - NdotV, 0.0f, 1.0f), 5.0f);
            if (face > 1u) {
                F_refl *= 0.22f;
            }
            vec3 color1 = (vec3(1.0f) - F_refl) * waterAlbedo * 0.31830988618f * reflectedColor * NdotV + reflectedColor * F_refl;

            vec3 sunRadiance = uBerylLightColor.rgb * 0.6f * uBerylAmbientLight.w;
            vec3 centerToRay = R - L;
            float d = max(length(centerToRay), 1e-4f);
            vec3 Ls = normalize(L + centerToRay * clamp(0.042f / d, 0.0f, 1.0f));
            vec3 H = normalize(V + Ls);
            float NdotL_w = max(dot(waterN, Ls), 0.0f);
            float NdotH = max(dot(waterN, H), 0.0f);
            vec3 F_sun = F0 + (vec3(1.0f) - F0) * pow(clamp(1.0f - max(dot(H, V), 0.0f), 0.0f, 1.0f), 5.0f);
            vec3 specular = NdotL_w * NdotV * F_sun / (1.01f - (NdotH * NdotH)) * sunRadiance * 0.2f * length(albedo);
            vec3 diffuse = (vec3(1.0f) - F_sun) * waterAlbedo * 0.31830988618f * sunRadiance * NdotL_w;

            float skyVis = (face <= 1u) ? max((lightY - 0.5f) * 2.0f, 0.0f) : smoothstep(0.08f, 0.85f, lightY);
            vec3 waterRgb = color1 * skyVis + (diffuse + specular) * light.z
                          + albedo * 0.3f * (light.y + light.x * vec3(1.0f, 0.7f, 0.5f));
            if (face <= 1u) {
                float baseAlpha = min((1.0f - NdotV) * 0.8f + colour.a * 0.3f, 1.0f);
                vec4 fragClip = MVP * vec4(worldWPos, 1.0f);
                vec2 fragUV = (fragClip.xy / max(fragClip.w, 1e-4f)) * vec2(0.5f, -0.5f) + 0.5f;
                float floorDepth = textureLod(ssrDepthTex, fragUV, 0.0f).r;
                float waterRayDist = 16.0f;
                if (floorDepth > gl_FragCoord.z && floorDepth < 0.999999f) {
                    waterRayDist = max(0.0f, waterDist * ((1.0f - gl_FragCoord.z) / max(1.0f - floorDepth, 1e-7f) - 1.0f));
                }
                // Use vertical water column depth (invariant to grazing view angle and camera distance)
                // so shallow underwater stone steps (1..4 blocks deep) remain visible like near-field Beryl water,
                // while deep ocean water (> 8 blocks deep) absorbs light naturally.
                float verticalWaterDepth = waterRayDist * max(abs(fragDir.y), 0.22f);
                float shoreShade = mix(0.82f, 1.0f, smoothstep(0.15f, 2.2f, verticalWaterDepth));
                float transmittance = exp(-verticalWaterDepth * 0.26f);
                float absorb = 1.0f - transmittance;
                vec3 deepScatter = albedo * 0.14f * (max(uBerylParams.z, light.y * 0.25f) * uBerylAmbientLight.rgb);
                float effAlpha = clamp(1.0f - (1.0f - baseAlpha) * transmittance, baseAlpha, 0.97f);
                vec3 effRgb = ((waterRgb * shoreShade) * baseAlpha + deepScatter * (1.0f - baseAlpha) * absorb) / max(effAlpha, 1e-4f);
                return vec4(effRgb, effAlpha);
            }
            float alpha = clamp(colour.a * 0.55f, 0.38f, 0.68f);
            return vec4(waterRgb, alpha);
        }
        #else
        // Apply 3D world-space sunShadow directly to lightZ in quads.frag so both horizontal and vertical faces
        // (including GGX specular and spherical sun Ls) are accurately shadowed just like Beryl's terrain.fsh.
        float lightZ = max((lightY - 0.5f) * 2.0f, 0.0f) * sunShadow;
        vec3 light = vec3(lightX * 2.0f, lightYM, lightZ);
        #endif

        // Exact Beryl LightingSphereGGX2 (from Beryl lighting.glsl + terrain.fsh with TEXTURED_SUN radius = 0.105)
        vec3 R = reflect(-V, N);
        vec3 centerToRay = R - L;
        float d = max(length(centerToRay), 1e-4f);
        vec3 Ls = normalize(L + centerToRay * clamp(0.105f / d, 0.0f, 1.0f));
        vec3 H = normalize(V + Ls);

        // Beryl's material table: default 0.9 (stone/wood/dirt-ish), foliage
        // 0.85, grass 0.85. The LOD has no block-material id, so use the
        // default for solids and the foliage value for the cross plants.
        float roughness = isFoliage ? 0.85f : 0.9f;
        float a = roughness * roughness;
        const float a2 = a * a;
        float NdotH = max(dot(N, H), 0.0f);
        float denom = (NdotH * NdotH * (a2 - 1.0f) + 1.0f);
        float NDF = a2 / (3.14159265359f * denom * denom);

        float NdotV = max(dot(N, V), 0.0f);
        // Beryl foliage: NdotL = dot(Up, L) * 0.8 (clamped here, it only goes
        // negative with the sun at the horizon where radiance is ~0).
        float NdotL = isFoliage ? max(Ls.y, 0.0f) * 0.8f : max(dot(N, Ls), 0.0f);
        const float k = ((roughness + 1.0f) * (roughness + 1.0f)) * 0.125f;
        float ggx2 = NdotV / (NdotV * (1.0f - k) + k);
        float ggx1 = NdotL / (NdotL * (1.0f - k) + k);
        float G = ggx1 * ggx2;

        vec3 F0 = vec3(0.04f);
        vec3 F = F0 + (vec3(1.0f) - F0) * pow(clamp(1.0f - max(dot(H, V), 0.0f), 0.0f, 1.0f), 5.0f);
        vec3 kD = vec3(1.0f) - F;

        vec3 radiance = uBerylLightColor.rgb * 0.6f * clamp((L.y - 0.04f) * 50.0f, 0.0f, 1.0f);
        vec3 specular = min(((NDF * G * F) / (4.0f * NdotV * NdotL + 0.0001f)) * NdotL, vec3(1.0f)) * radiance;
        vec3 color1 = (kD * albedo * 0.31830988618f) * radiance * NdotL + specular;

        float NdotU = isFoliage ? 0.8f : max(N.y, 0.0f);
        float ambientDir = 1.0f;
        vec3 hdrRgb = color1 * light.z
                    + albedo * ambientDir * (light.y * uBerylAmbientLight.rgb + light.x * vec3(1.0f, 0.7f, 0.5f))
                    + radiance * 0.1f * NdotU * albedo * light.z;

        if ((interData.w & (1u << 25)) != 0u) {
            hdrRgb += max(albedo - vec3(0.05f), vec3(0.0f)) * 5.0f;
        }

        #ifdef TRANSLUCENT
        return vec4(hdrRgb, colour.a);
        #else
        if (uDebugShadow > 0.5f) {
            // R = shadow factor, G = 1 when it came from Beryl's shadow map, B = the baked per-voxel bit
            return vec4(sunShadow, berylShadow >= 0.0f ? 1.0f : 0.0f, sunShadowBit, 1.0f);
        }
        return vec4(hdrRgb, float(interData.w & 0xFFu) / 255.0f);
        #endif
    }

    vec4 lightTint = uint2vec4RGBA(interData.y);
    // Compose the light as baked-anchor x ratio: baked = lm(record byte) x
    // faceTint, ratio = lm(vertex)/lm(record byte). The ratio is applied at
    // full weight so the record-byte anchor cancels algebraically and the
    // final value is exactly lm(per-vertex smooth light) x faceTint - the
    // vanilla form. The byte value itself becomes irrelevant (it only serves
    // as the ratio denominator), which removes the whole class of
    // anchor-mismatch artefacts: the old 0.55 partial blend kept a fraction of
    // the record anchor in every pixel, so whichever byte each face carried
    // (masked 0 for opaque cells, column-max 15 for merged LOD cells) shifted
    // the face's brightness by +-15% depending on data provenance.
    // Non-AO models keep the record byte (the block's own light): vanilla's
    // flat renderer lights them uniformly.
    if (hasAO) {
        vec2 flatLightUV = (vec2(float((lightingFlat >> 4) & 0xFu), float(lightingFlat & 0xFu)) * 16.0f + 8.0f) / 256.0f;
        vec2 smoothLightUV = (vec2(vBlock, vSky) * 16.0f + 8.0f) / 256.0f;
        vec3 flatLight = texture(lightSampler, flatLightUV).rgb;
        vec3 smoothLight = texture(lightSampler, smoothLightUV).rgb;
        lightTint.rgb *= smoothLight / max(flatLight, vec3(0.02f));
    }
    return (colour * lightTint) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


void main() {
    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    // The vertex UVs are in LOD-cell units, but the quad spans
    // quadSize * lodScale blocks. Scale to block units so the block texture
    // tiles once per block like the near field instead of once per LOD cell
    // (which stretched one 16x16 tile over 2^lod blocks and made the far
    // terrain look flat/light against the near terrain).
    float lodScale = float(1u << ((interData.w >> 3) & 7u));
    vec2 uvBlocks = uv * lodScale;
    // interData.x stores quadSize-1. The quad spans quadSize * lodScale
    // blocks, so the UV range has to reach the END of the last block, not its
    // start: the old +1.002 stopped at the last block's leading edge and
    // discarded all but 1/lodScale of every quad (dashed far terrain).
    vec2 maxTile = vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu) * lodScale;
    vec2 maxUv = maxTile + vec2(lodScale + 0.9999f);
    vec2 uvClamped = clamp(uvBlocks, vec2(0.0f), maxUv);
    vec2 uv2 = modf(uvClamped, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
        vec2 uvSmol = uvBlocks * (1.0f / (vec2(3.0f, 2.0f) * 256.0f));
        vec2 rawDx = dFdx(uvSmol);
        vec2 rawDy = dFdy(uvSmol);
        float lx = length(rawDx);
        float ly = length(rawDy);
        float majorD = max(lx, ly);
        float minorD = min(lx, ly);
        // The mip level is driven by the LARGER derivative. The old test used
        // the smaller one, so a grazing-angle far-field plain (huge major
        // derivative, tiny minor one) was classified as "magnified" and the
        // gradients were sharpened by 0.26-0.55x. That undersampled the grass
        // texture and produced the moire rings that only the LOD showed.
        float detailWeight = 1.0f - smoothstep(0.9f / 12288.0f, 2.2f / 12288.0f, majorD);
        // Everything that is minified (the whole far field) uses the true
        // derivatives, so the hardware selects the same mip the near field
        // would and the mip chain does the anti-aliasing.
        float gradScale = mix(1.0f, 0.26f, detailWeight);
        vec2 dx = rawDx * gradScale;
        vec2 dy = rawDy * gradScale;
        if (detailWeight > 0.01f && minorD > 1e-7f) {
            // Only in the magnified transition band cap the anisotropy so
            // zoomed-in 16x16 block detail stays crisp.
            float maxRatio = 8.0f;
            float maxD = (minorD * gradScale) * maxRatio;
            float slx = lx * gradScale;
            float sly = ly * gradScale;
            if (slx > maxD) dx *= maxD / slx;
            if (sly > maxD) dy *= maxD / sly;
        }
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }// else {
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    // NOTE: no synthetic texture-tile border here. Vanilla's block separation
    // comes from smooth lighting/AO at inside corners, not from a dark UV edge.
    // The mesher supplies the per-vertex light/AO for those corners.

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    if (any(lessThan(uvBlocks, vec2(-0.002f))) || any(greaterThan(uvBlocks, maxUv + vec2(0.002f)))) {
        discard;
        return;
    }

    // Cut the *far* LOD where the near chunks' bounding depth is closer. Do NOT
    // apply this to LOD0: its own near-field mesh sits at the same surface as
    // the vanilla chunks and is already masked precisely by the stencil test
    // (stencil 0 only where actual vanilla geometry exists). The AABB is 16
    // blocks tall while the vanilla mesh usually fills only its lower part, so
    // its far face sits up to 16 blocks behind the real surface; cutting LOD0
    // against it discarded visible LOD0 terrain along the vanilla seam and
    // exposed the caves/air behind the near chunks.
    #ifndef SHADOW_PASS
    #ifndef TRANSLUCENT
    if (((interData.w >> 3) & 7u) != 0u
            && gl_FragCoord.z < texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r) {
        discard;
        return;
    }
    #else
    if (gl_FragCoord.z < texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r) {
        discard;
        return;
    }
    #endif
    #endif

    //Also, small quad is really fking over the mipping level somehow
    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    //if (useDiscard() && (colour.a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        //AUDIT-FIXME: samples the exact pixel (no lod) for discarding — bad for
        // divergence, but it stops mipmapping from producing wrong alpha.
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }

    #ifdef SHADOW_PASS
    // Beryl shadow.fsh: alpha tested texture colour (only read for coloured shadows)
    outColour = vec4(colour.rgb, 1.0f);
    return;
    #endif

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
    colour = computeColour(texPos, colour);

    #ifndef TRANSLUCENT
    // Per-vertex vanilla AO and smooth light are now part of the mesh data.
    // The old fragment-space B-lite is intentionally disabled so the far LOD
    // uses the same per-block contact shading as the near chunks.
    #endif

    outColour = colour;

    #ifdef DEBUG_RENDER
    float shadowRawDbg = ((interData.w >> 24) & 1u) != 0u ? 0.0f : 1.0f;
    if (uBerylLightDir.w > 0.5f) {
        uint faceDbg = getFace();
        vec3 Nd = vec3(float((faceDbg>>1)==2u), float((faceDbg>>1)==0u), float((faceDbg>>1)==1u)) * (float(int(faceDbg)&1)*2.0f - 1.0f);
        vec3 Ld = normalize(uBerylLightDir.xyz);
        float bs = sampleBerylShadow(vRelPos, Nd, Ld);
        if (bs >= 0.0f) {
            shadowRawDbg = bs;
        }
    }
    // Absolute decode probe: R = vSky/15 (pool sky), G = vBlock/15, B = vAO.
    // Pure-black patch with all channels dead -> the lightAO slot reads 0;
    // red/green alive but the normal render is dark -> the texture/record or a
    // discard path is the culprit, not the vertex light data.
    outColour = vec4(vSky / 15.0f, vBlock / 15.0f, vAO, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));

    uint customId = model.customId;
    #ifdef TRANSLUCENT
    if (modelIsWater(model) || modelIsTranslucent(model)) {
        // Tag with MATERIAL_WATER custom ID (10001) for Iris and Photon shaders
        if (customId < 10001u) {
            customId = 10001u;
        }
    }
    #endif

    voxel_emitFragment(VoxelFragmentParameters(colour, tile, texPos, face, modelId, getLightmap(), tint, customId));

    #endif
}



//AUDIT-DEAD: two commented subgroup-quad mip experiments lived here — removed;
// see git history (pre-0a7b149) if the quad-shuffle mip idea is ever revived.

