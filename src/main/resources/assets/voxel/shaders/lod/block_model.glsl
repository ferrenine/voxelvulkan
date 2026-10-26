struct BlockModel {
    uint faceData[6];
    uint flagsA;
    uint colourTint;
    uint customId;
    uint offsetInfo; // packHalf2x16(max horizontal, max vertical) vanilla model offset, 0 = none
    uint faceSlope[3]; // 16 bits per face (2 faces per uint): int8 depth slope along u, v in 1/64
    uint _pad[3];
};

// Depth slope (block units per block along u, v) of a slanted face, 0 for flat faces.
vec2 extractFaceSlope(const in BlockModel model, uint face) {
    uint packed = model.faceSlope[face >> 1] >> ((face & 1u) * 16u);
    return vec2(int(packed << 24) >> 24, int(packed << 16) >> 24) / 64.0;
}

//AUDIT-FIXME: not actually correct — depending on the face this can be 1/64 of
// a position off; accepted as invisible at LOD distances.
float extractFaceIndentation(uint faceData) {
    uint enc = (faceData>>16)&63u;
    enc += uint(enc==63u);//convert 63 to 64 cause of pain reasons
    return float(enc)/64.0;
}

vec4 extractFaceSizes(uint faceData) {
    return (vec4(faceData&0xFu, (faceData>>4)&0xFu, (faceData>>8)&0xFu, (faceData>>12)&0xFu)/16.0)+vec4(0.0,1.0/16.0,0.0,1.0/16.0);
}

uint faceHasAlphaCuttout(uint faceData) {
    return (faceData>>22)&1u;
}

//TODO: try and get rid of
uint faceHasAlphaCuttoutOverride(uint faceData) {
    return (faceData>>23)&1u;
}

uint faceTintState(uint faceData) {
    return (faceData>>24)&3u;
}

bool modelHasBiomeLUT(BlockModel model) {
    return ((model.flagsA)&2u) != 0;
}

bool modelIsTranslucent(BlockModel model) {
    return ((model.flagsA)&4u) != 0;
}

bool modelIsShaded(BlockModel model) {
    return ((model.flagsA)&8u) != 0;
}

bool modelIsDoubleSided(BlockModel model) {
    return ((model.flagsA)&16u) != 0;
}

// Beryl material flag: lava/fire emissive (materials.glsl lightEmission = 5.0).
bool modelIsEmissive(BlockModel model) {
    return ((model.flagsA)&32u) != 0;
}

// Lava surface quads carry per-corner heights in the lightAO buffer.
bool modelIsLava(BlockModel model) {
    return ((model.flagsA)&64u) != 0;
}

// Flow-top sibling of the lava model: the UP tile holds the whole flowing
// sprite (captured at rotation 0); quads3.vert rotates the UVs by the surface gradient.
bool modelIsLavaFlow(BlockModel model) {
    return ((model.flagsA)&128u) != 0;
}

bool modelIsWater(BlockModel model) {
    return modelIsTranslucent(model) && (model.customId == 10001u || modelHasBiomeLUT(model));
}