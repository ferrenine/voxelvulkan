#version 450

layout(location = 0) out vec2 UV;
void main() {
    gl_Position = vec4(vec2(gl_VertexID&1, (gl_VertexID>>1)&1) * 2 - 1, 1.0f, 1);
    UV = gl_Position.xy * vec2(0.5, -0.5) + 0.5;
}
