#version 330
#extension GL_ARB_separate_shader_objects : require
// 26.3: vanilla shaders bind varyings by explicit location (separate shader
// objects); these match core/position_tex_color.vsh (texCoord0 = 0, vertexColor = 1).

// Single-channel SDF icon glyphs (close X, chevrons, edit pencil) — baked
// offline (tools/.../gen_icon_sdf.py) from the exact same capsule-stroke
// geometry OriginUi used to draw these with at runtime via aaLine/ROUND.
// Same math as the MSDF text shader, minus the multi-channel median() step
// (one channel is directly the signed distance, no need to reconstruct it
// from 3 wedge directions the way MSDF does for sharp text corners).
//
// PER-VERSION DELTA (1.21.11): std140 DynamicTransforms UBO instead of a bare
// `uniform vec4 ColorModulator` — mirrors vanilla's own core/position_tex_color.fsh.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

layout(location = 1) in vec4 vertexColor;
layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

// Must match ICON_DISTANCE_RANGE in gen_icon_sdf.py (baked-texture pixels).
const float DISTANCE_RANGE = 16.0;

void main() {
    float sd = texture(Sampler0, texCoord0).r;

    vec2 unitRange = vec2(DISTANCE_RANGE) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(texCoord0);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    float screenPxDist = screenPxRange * (sd - 0.5);
    float alpha = clamp(screenPxDist + 0.5, 0.0, 1.0);
    if (alpha < 0.001) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
