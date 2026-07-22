#version 150

// MSDF text: the whole point of the redesign's "smooth at any size" ask. The
// glyph atlas stores a multi-channel signed distance field; the median of the
// three channels reconstructs the true distance to the glyph edge, and we
// convert that distance to coverage in SCREEN space (via fwidth) — so the edge
// is one screen-pixel soft no matter how large or small the text is drawn.
// That is what makes it scale like vector text on the web instead of a bitmap.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

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
