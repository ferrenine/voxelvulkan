#version 450

struct BakeryVertex {
    float px, py, pz, pw;
    float u, v;
};

layout(binding = 1, std430) readonly buffer VertexBuffer {
    BakeryVertex vertices[];
};

layout(push_constant) uniform PushConstants {
    mat4 transform;
};

layout(location = 0) out vec2 texCoord;
layout(location = 1) out flat uint metadata;

void main() {
    BakeryVertex vert = vertices[gl_VertexIndex];
    metadata = floatBitsToUint(vert.pw);

    gl_Position = transform * vec4(vert.px, vert.py, vert.pz, 1.0);
    texCoord = vec2(vert.u, vert.v);
}
