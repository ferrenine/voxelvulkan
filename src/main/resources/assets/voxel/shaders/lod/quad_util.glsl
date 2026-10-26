// Voxel Quad Decoding and Geometric Utilities

// Swizzle coordinate components based on face normal axis:
// axis 0 = Y axis normal (X-Z plane), axis 1 = Z axis normal (X-Y plane), axis 2 = X axis normal (Y-Z plane)
vec3 swizzleQuadAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy, data.xzy, bvec3(axis == 0u)), data, bvec3(axis == 1u));
}
#define swizzelDataAxis swizzleQuadAxis

uint extractDetail(uvec2 encPos) {
    return encPos.x>>28;
}

ivec3 extractLoDPosition(uvec2 encPos) {
    int y = ((int(encPos.x)<<4)>>24);
    int x = (int(encPos.y)<<4)>>8;
    int z = int((encPos.x&((1u<<20)-1))<<4);
    z |= int(encPos.y>>28);
    z <<= 8;
    z >>= 8;
    return ivec3(x,y,z);
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.00015f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    //Expand the quads by a very small amount (because of the subtraction after this also becomes an implicit add)
    faceOffsetsSizes.xz -= vec2(EPSILON);

    //Make the end relative to the start
    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}


//AUDIT-DEAD: TAA is dormant — taaFunction() returns null so this offset is
// always (0,0). It is mutable global state: the vert main writes it and
// getQuadCornerPos reads it implicitly.
vec2 taaOffset = vec2(0);//TODO: compute this

struct QuadData {
    uvec4 attributeData;

    float lodScale;
    uint axis;
    bool culled;
    //Used for computing the 4 corners of the quad
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
    //Change of the face axis coordinate per in-plane block (slanted faces, 0 otherwise)
    vec2 depthSlope;
    // Lava surface quads: the lightAO word carries per-corner signed Y offsets.
    bool fluidFlow;
    // Flow-top lava model: UP-face UVs are re-rotated by the surface gradient.
    bool lavaFlowTile;
    // The quad's face index (0..5, DOWN UP NORTH SOUTH WEST EAST order).
    uint face;
    // Outward direction along the face axis (positive/negative face).
    float faceSign;
};

uint makeQuadFlags(uint faceData, uint modelId, ivec2 quadSize, const in BlockModel model, uint face) {
    //bit: 0-use cuttout, 1-dont use mipmaps, 2|3-tint state, 4|6-face, 8|11-width, 12|15-height, 16|31-model id
    uint flags = 0;

    flags |= modelId<<16;//Model id
    flags |= (uint(quadSize.x-1)<<8)|(uint(quadSize.y-1)<<12);//quad size

    {//Cuttout
        flags |= faceHasAlphaCuttout(faceData);
        flags |= uint(any(greaterThan(quadSize, ivec2(1)))) & faceHasAlphaCuttoutOverride(faceData);
    }

    //TODO: remove, there is no non mip code path anymore
    //flags |= uint(!modelHasMipmaps(model))<<1;//Not mipmaps

    flags |= faceTintState(faceData)<<2;
    flags |= face<<4;//Face

    return flags;
}

uint packVec4(vec4 vec) {
    uvec4 vec_=uvec4(vec*255)<<uvec4(24,16,8,0);
    return vec_.x|vec_.y|vec_.z|vec_.w;
}


#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face);
#endif

uvec3 makeRemainingAttributes(const in BlockModel model, const in Quad quad, uint lodLevel, uint face) {
    uvec3 attributes = uvec3(0);

    uint lighting = extractLightId(quad);

    //Apply model colour tinting
    uint tintColour = model.colourTint;

    if (modelHasBiomeLUT(model)) {
        uint baseOffset = tintColour;
        uint bA = extractBiomeId(quad);
        uint blendW = extractBiomeBlendWeight(quad);
        if (blendW == 0u) {
            tintColour = colourData[baseOffset + bA];
        } else {
            uint bB = extractSecondBiomeId(quad);
            vec4 cA = unpackUnorm4x8(colourData[baseOffset + (bA & 63u)]);
            vec4 cB = unpackUnorm4x8(colourData[baseOffset + bB]);
            tintColour = packUnorm4x8(mix(cA, cB, float(blendW) * 0.25f));
        }
    }

    #ifdef PATCHED_SHADER
    attributes.x = lighting;
    attributes.y = tintColour;
    #else
    bool isTranslucent = modelIsTranslucent(model);

    //afak, these are the same variable in vanilla, (i.e. shaded == ao)
    bool isShaded = modelIsShaded(model);
    bool hasAO = isShaded;

    vec4 tinting = getLighting(lighting);

    uint conditionalTinting = 0;
    if (tintColour != uint(-1)) {
        conditionalTinting = tintColour;
    }

    uint addin = 0;
    if (!isTranslucent) {
        tinting.w = 0.0;
        //Encode the face, the lod level, AO flag, and 3D world-space sun shadow bit (bit 7)
        uint encodedData = 0;
        encodedData |= face;
        encodedData |= (lodLevel<<3);
        encodedData |= uint(hasAO)<<6;
        encodedData |= extractSunShadowBit(quad)<<7;
        encodedData |= uint(modelIsEmissive(model))<<25;
        addin = encodedData;
    } else {
        addin = uint(modelIsWater(model)) | (extractSunShadowBit(quad)<<7)
              | (uint(modelIsEmissive(model))<<25);
    }

    // Vanilla getShade only (UP 1.0, DOWN 0.5, N/S 0.8, E/W 0.6). Contact
    // darkening comes from the per-vertex AO, not an extra side-face factor.
    float faceTint = computeDirectionalFaceTint(isShaded, face);
    tinting.rgb *= faceTint;

    attributes.x = packVec4(tinting);
    attributes.y = conditionalTinting;
    attributes.z = addin | (face << 8) | (lighting << 16) | (extractSunShadowBit(quad) << 24);
    #endif

    return attributes;
}

// Vanilla BlockBehaviour model offset (grass, ferns, flowers, ...): seeded by
// Mth.getSeed(x, 0, z); XZ is clamped to the max horizontal offset, XYZ also
// sinks the model by up to the max vertical offset (0 for the XZ type).
vec3 computeModelOffset(ivec2 blockXZ, vec2 maxOffset) {
    #ifdef GL_ARB_gpu_shader_int64
    int64_t seed = int64_t(blockXZ.x * 3129871) ^ (int64_t(blockXZ.y) * 116129781l);
    seed = seed * seed * 42317861l + seed * 11l;
    seed >>= 16;
    float ox = (float(int(seed & 15l)) / 15.0f - 0.5f) * 0.5f;
    float oy = (float(int((seed >> 4) & 15l)) / 15.0f - 1.0f) * maxOffset.y;
    float oz = (float(int((seed >> 8) & 15l)) / 15.0f - 0.5f) * 0.5f;
    return vec3(clamp(ox, -maxOffset.x, maxOffset.x), oy, clamp(oz, -maxOffset.x, maxOffset.x));
    #else
    return vec3(0.0f);
    #endif
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos, bool generateAttributes) {
    uint lodLevel = extractDetail(sPos);
    float lodScale = 1<<lodLevel;
    ivec3 baseSection = (extractLoDPosition(sPos)<<lodLevel) - LOD_BASE_SECTION_POS;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];
    ivec2 quadSize = extractSize(rawQuad);

    if (generateAttributes) {
        quad.attributeData.x = makeQuadFlags(faceData, modelId, quadSize, model, face);
        quad.attributeData.yzw = makeRemainingAttributes(model, rawQuad, lodLevel, face);
    }

    vec4 faceSize = getFaceSize(faceData);
    #ifdef USE_SINGLE_TRI
    faceSize *= 2;
    #endif
    vec3 cellStart = extractPos(rawQuad);
    float depthOffset = extractFaceIndentation(faceData);
    uint faceAxis = face >> 1u;
    bool positiveFace = (face & 1u) != 0u;

    // Cell coordinates scale with the LOD cell size, but the face indentation
    // is a constant world-space offset (water sits 1/8 block below the cell
    // top; slabs/fences have fixed insets). Scaling the indentation as well
    // would sink the far LOD water below the near LOD0 surface by
    // depthOffset * (lodScale - 1) blocks and leave a visible step at the
    // LOD boundary.
    vec3 inPlaneOffset = swizzelDataAxis(faceAxis, vec3(faceSize.xz, 0.0));
    float scaledCellAxis = cellStart[faceAxis] * lodScale;
    float axisPos = scaledCellAxis + (positiveFace ? lodScale - depthOffset : depthOffset);
    vec3 axisOffset = swizzelDataAxis(faceAxis, vec3(0.0, 0.0, axisPos - scaledCellAxis));

    quad.lodScale = lodScale;
    quad.axis = faceAxis;
    quad.basePoint = cellStart * lodScale + inPlaneOffset * lodScale
            + axisOffset + vec3(baseSection << 5);
    if (model.offsetInfo != 0u) {
        // Offset models are meshed one quad per block, so the quad's cell is
        // the block (the cell origin at coarser LODs, scaled with the cell).
        ivec2 cellXZ = (extractLoDPosition(sPos).xz << 5) + ivec2(extractPos(rawQuad).xz);
        quad.basePoint += computeModelOffset(cellXZ << lodLevel, unpackHalf2x16(model.offsetInfo)) * lodScale;
    }
    #ifdef USE_SINGLE_TRI
    quad.quadSizeAddin = (faceSize.yw + (quadSize - 1)*2);
    #else
    quad.quadSizeAddin = faceSize.yw + quadSize - 1;
    #endif
    quad.uvCorner = faceSize.xz;

    // Slanted faces (wall torches, ascending rails, ...): the face depth grows
    // along the face; that moves negative faces towards +axis, positive ones
    // towards -axis. Slanted models are meshed one quad per block.
    vec2 faceSlope = extractFaceSlope(model, face);
    quad.depthSlope = ((face & 1u) == 0u) ? faceSlope : -faceSlope;
    quad.fluidFlow = modelIsLava(model);
    quad.lavaFlowTile = modelIsLavaFlow(model);
    quad.face = face;
    quad.faceSign = ((face & 1u) == 1u) ? 1.0f : -1.0f;

    if (!modelIsDoubleSided(model) && !modelIsTranslucent(model) && !quad.fluidFlow) {
        // Which side of the (possibly slanted) face plane the camera is on
        vec3 camRel = LOD_CAMERA_SUB_POS - quad.basePoint;
        vec2 camInPlane = (quad.axis == 0u) ? camRel.xz : ((quad.axis == 1u) ? camRel.xy : camRel.yz);
        float axisRel = dot(quad.depthSlope, camInPlane)
                      - ((quad.axis == 0u) ? camRel.y : ((quad.axis == 1u) ? camRel.z : camRel.x));
        quad.culled = ((face & 1u) == 0u) ? (axisRel <= 0.0f) : (axisRel >= 0.0f);
    } else {
        quad.culled = false;
    }
}

// Apply spherical world curvature effect (inspired by Distant Horizons)
// Makes distant terrain curve downward as if standing on a spherical planet
vec3 applyWorldCurvature(vec3 worldPos) {
    if (uEarthRadius <= 0.0) {
        return worldPos;
    }

    // Calculate local radius at vertex height
    float localRadius = uEarthRadius + worldPos.y;

    // Calculate the arc angle based on horizontal distance
    float horizontalDist = length(worldPos.xz);
    float phi = horizontalDist / localRadius;

    // Apply spherical projection
    // Y displacement: terrain curves down at distance
    worldPos.y += (cos(phi) - 1.0) * localRadius;

    // XZ scaling: slight convergence at extreme distances (prevents horizon stretching)
    if (phi > 0.0001) {
        worldPos.xz = worldPos.xz * sin(phi) / phi;
    }

    return worldPos;
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId, float yOffset, out vec3 outRelPos) {
    if (quad.culled) {
        outRelPos = vec3(0.0f);
        return vec4(-2.0f, -2.0f, -2.0f, 1.0f);
    }
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec2 inPlane = quad.quadSizeAddin*cornerMask;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis,vec3(inPlane, dot(quad.depthSlope, inPlane)));
    point.y += yOffset;
    if (quad.fluidFlow) {
        point += swizzelDataAxis(quad.axis, vec3(0.0f, 0.0f, 0.002f * quad.faceSign));
    }

    // Micro-Offset Z-Fighting Elimination (inspired by Distant Horizons)
    // Applies sub-millimeter normal displacement and clip-space depth bias
    // to ensure adjacent LOD sections and coplanar cliff faces render with zero flickering
    vec3 microOffset = swizzelDataAxis(quad.axis, vec3(0.0, 0.0, 0.0003 * quad.lodScale));
    point += microOffset;

    // Apply world curvature before MVP transformation
    point = applyWorldCurvature(point);
    outRelPos = point - LOD_CAMERA_SUB_POS;

    vec4 pos = LOD_MVP * vec4(point, 1.0f);
    // Perspective depth bias (eliminates float depth-buffer rounding z-fight at long range)
    pos.z -= 0.000003f * pos.w;

    pos.xy += taaOffset*pos.w;
    return pos;
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId, float yOffset) {
    vec3 dummy;
    return getQuadCornerPos(quad, cornerId, yOffset, dummy);
}

#ifndef USE_NV_BARRY
vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.quadSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u);
}
#endif

#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face) {
    //Apply face tint (live ClientLevel.getShade values, see bindings.glsl)
    if (isShaded) {
        if ((face>>1) == 1) {//NORTH, SOUTH
            return uFaceShade.z;
        } else if ((face>>1) == 2) {//EAST, WEST
            return uFaceShade.w;
        } else if (face == 1) {//UP
            return uFaceShade.x;
        }
        //DOWN
        return uFaceShade.y;
    } else {
        return uNoShadeFaceTint;
    }
}
#endif