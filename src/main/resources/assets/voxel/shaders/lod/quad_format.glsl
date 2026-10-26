// Packed quad format (one uint64 per emitted face; see RenderDataFactory):
//   bits  0.. 2 : face          (3, 0=-Y 1=+Y 2=-Z 3=+Z 4=-X 5=+X; quad type is
//                consumed CPU-side into the buffer index, not stored here)
//   bits  3.. 6 : size0         (4, merged quad "length" -1; RenderDataFactory
//                packs it as width-1 — axis semantics live in the scan mesher)
//   bits  7..10 : size1         (4, merged quad "height" -1)
//   bits 11..15 : z             (5)
//   bits 16..20 : y             (5)
//   bits 21..25 : x             (5)
//   bits 26..41 : stateId       (16, model id)
//   bits 42..43 : biomeBlendWt  (2)
//   bits 44..45 : secondBiomeId lo (2)
//   bits 46..54 : biomeId       (9)
//   bits 52..54 : secondBiomeId hi (3, shared tail bits with biomeId)
//   bits 55..62 : light byte    (8, hi nibble = block light, lo = sky)
//   bit  63     : sunShadow     (1, baked ray-traced shadow flag)
#ifdef GL_ARB_gpu_shader_int64
#define Quad uint64_t

#define Eu32(data, amountBits, shift) (uint((data)>>(shift))&((1u<<(amountBits))-1))

vec3 extractPos(uint64_t quad) {
    //TODO: pull out the majic constants into #defines (specifically the shift amount)
    return vec3(Eu32(quad, 5, 21), Eu32(quad, 5, 16), Eu32(quad, 5, 11));
}

ivec2 extractSize(uint64_t quad) {
    return ivec2(Eu32(quad, 4, 3), Eu32(quad, 4, 7)) + ivec2(1);//the + 1 is cause you cant actually have a 0 size quad
}

uint extractFace(uint64_t quad) {
    return Eu32(quad, 3, 0);
}

uint extractStateId(uint64_t quad) {
    return Eu32(quad, 16, 26);
}

//AUDIT-NOTE: this 9-bit read (46..54) ALIASES extractSecondBiomeId's high
// bits (52..54) — blend callers must mask accordingly (quad_util uses &63);
// a bare 9-bit biomeId is only valid for non-blended quads.
uint extractBiomeId(uint64_t quad) {
    return Eu32(quad, 9, 46);
}

uint extractBiomeBlendWeight(uint64_t quad) {
    return Eu32(quad, 2, 42);
}

uint extractSecondBiomeId(uint64_t quad) {
    return Eu32(quad, 2, 44) | (Eu32(quad, 3, 52) << 2);
}

uint extractSunShadowBit(uint64_t quad) {
    return Eu32(quad, 1, 63);
}

uint extractLightId(uint64_t quad) {
    return Eu32(quad, 8, 55);
}

bool isQuadEmpty(uint64_t quad) {
    return quad == uint64_t(0);
}

#endif
// AUDIT-DEAD: the non-int64 ivec2 fallback was removed — it decoded stateId
// with 20 bits (correct: 16), lacked extractBiomeBlendWeight/SecondBiomeId,
// and could never compile anyway since all entry shaders require
// GL_ARB_gpu_shader_int64.