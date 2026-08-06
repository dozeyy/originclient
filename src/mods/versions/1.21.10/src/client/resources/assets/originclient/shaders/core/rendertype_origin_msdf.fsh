#version 330

// MSDF text: the glyph atlas stores a multi-channel signed distance field; the
// median of the three channels reconstructs the true distance to the glyph
// edge, and we convert that distance to coverage in SCREEN space (via fwidth)
// so the edge is one screen-pixel soft no matter how large or small the text is
// drawn — vector-like scaling instead of a bitmap.
//
// PER-VERSION DELTA (1.21.11): the render-pipeline rewrite moved shader
// uniforms into std140 blocks (ColorModulator now lives in DynamicTransforms,
// not a bare `uniform vec4`) — mirrors vanilla's own core/position_tex_color.fsh.
// The MSDF math itself is unchanged from 1.21.1/1.21.4.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// distanceRange the atlas was baked with (msdf-bmfont -r 4)
const float DISTANCE_RANGE = 4.0;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 msd = texture(Sampler0, texCoord0).rgb;
    float sd = median(msd.r, msd.g, msd.b);

    // pixels of distance field per screen pixel — resolution independent AA
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
