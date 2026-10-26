#version 450

layout(binding = 0) uniform sampler2D tex;
layout(location = 0) in vec2 texCoord;
layout(location = 1) in flat uint metadata;
layout(location = 0) out vec4 colour;
layout(location = 1) out uvec4 metaOut;

void main() {
    float mipbias = -16.0f;//Always use the top level (metadata-driven bias removed)
    colour = texture(tex, texCoord, mipbias);
    if (colour.a < 0.001f && ((metadata&1u)!=0)) {
        discard;
    }
    metaOut = uvec4((metadata>>2)&1u);//Write if it is or isnt tinted
}
