#version 450

layout(binding = 0) uniform sampler2D depthTex;
layout(push_constant) uniform PushConstants {
    vec2 scaleFactor;
    float depthScale;
    float depthBias;
};

layout(location = 0) in vec2 UV;
void main() {
    float d = texture(depthTex, UV * scaleFactor).r;
    if (d >= 1.0f) {
        discard;
    }
    if (depthScale != 0.0f) {
        gl_FragDepth = clamp(d * depthScale + depthBias - 0.00015f, 0.00005f, 0.9999f);
    } else {
        gl_FragDepth = 0.0f;
    }
}
