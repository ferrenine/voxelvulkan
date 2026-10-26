#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(binding = 1, std140) uniform BlitUniforms {
    mat4 invProjMat;
    mat4 projMat;
    vec4 endParams;
    vec4 fogColour;
    vec4 uBerylLightDir;     // xyz = lightDirWS, w = berylActive
    vec4 uBerylLightColor;   // rgb = LightColor, a = LightIntensity
    vec4 uBerylAmbientLight; // rgb = AmbientLight, a = LightVisibility
    vec4 uBerylSkyColor;     // rgba = SkyColor
    vec4 uBerylParams;       // x = AmbientLightFactor, y = NightMultiplier, z = MinAmbientLight, w = FogFactor
};

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#endif

layout(location = 0) out vec4 colour;
layout(location = 0) in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip.x * 2.0f - 1.0f, 1.0f - clip.y * 2.0f, clip.z, 1.0f);
    return view.xyz / view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z / view.w;
}

vec3 berylGetMieScattering(float RdotL) {
    float f = 10.0f + 200.0f * uBerylAmbientLight.w;
    return uBerylLightColor.rgb * 0.2f * min((1.0f / ((1.0f - RdotL) * f + 1.0f)), 1.0f);
}

vec3 berylGetSkyColor(float RdotL, float RdotUp, float SoUp) {
    float f1 = 1.0f - 0.25f / (40.0f * RdotUp * RdotUp + 0.25f);
    vec3 col = mix(fogColour.rgb, uBerylSkyColor.rgb, f1);

    float m1 = min((1.0f - SoUp) * 1.15f, 1.0f);
    m1 = m1 * m1;
    m1 = m1 * m1;

    float m2 = min((1.0f - RdotUp) * 1.15f, 1.0f);
    m2 = m2 * m2 * m1;
    m2 *= min(RdotL * 0.9f + 0.1f, 1.0f);
    m2 *= uBerylAmbientLight.w * (1.0f - uBerylParams.y);

    vec3 sunsetColor = vec3(0.75f, 0.27f, 0.15f);
    col = mix(col, sunsetColor, m2);
    col += berylGetMieScattering(RdotL);
    return col;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    float worldDist = length(point.xyz);
    #ifdef TRANSLUCENT_COMPOSITE
    float nearBias = (worldDist < 384.0f) ? (0.08f / max(worldDist, 1.0f)) : 0.0f;
    depth = projDepth(point * (1.0f + nearBias));
    #else
    depth = projDepth(point);
    #endif
    depth = clamp(depth, 0.0f, 1.0f - (2.0f / ((1 << 24) - 1)));
    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0f) {
        discard;
    }
    #ifdef TRANSLUCENT_COMPOSITE
    colour.rgb /= max(colour.a, 1e-4f);
    #endif
    if (uBerylLightDir.w > 0.5f) {
        vec3 fragDir = point.xyz / max(worldDist, 1e-4f);
        float RoUp = max(fragDir.y, 0.0f);
        float SoUp = max(uBerylLightDir.y, 0.0f);
        float RdotL = max(dot(fragDir, uBerylLightDir.xyz), 0.0f);
        vec3 skyFogCol = berylGetSkyColor(RdotL, RoUp, SoUp);

        // Beryl exponential atmospheric fog
        float fogAmount = 1.0f - exp(-worldDist * uBerylParams.w);
        vec3 atmFogCol = skyFogCol;
        if (uBerylLightColor.a > 0.0f) {
            atmFogCol = mix(atmFogCol, uBerylLightColor.rgb * 0.5f * atmFogCol, pow(RdotL, 8.0f));
        }
        colour.rgb = mix(colour.rgb, atmFogCol, fogAmount);

        #ifdef USE_ENV_FOG
        float horizonFog = clamp(fma(worldDist, endParams.x, endParams.y), 0.0f, endParams.z);
        horizonFog = horizonFog * horizonFog * (3.0f - 2.0f * horizonFog);
        colour.rgb = mix(colour.rgb, skyFogCol, horizonFog);
        #endif
    } else {
        #ifdef USE_ENV_FOG
        if (fogColour.a > 0.0f) {
            float fog = clamp(fma(worldDist, endParams.x, endParams.y), 0.0f, endParams.z);
            colour.rgb = mix(colour.rgb, fogColour.rgb, fog * fogColour.a);
        }
        #endif
    }
    #else
    colour = vec4(0.0f);
    #endif
}
