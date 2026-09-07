#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// Must match SDF_RANGE in tools/mod-menu/generate_icons.py.
const float DISTANCE_RANGE = 16.0;

void main() {
    float signedDistance = texture(Sampler0, texCoord0).r;
    vec2 unitRange = vec2(DISTANCE_RANGE) / vec2(textureSize(Sampler0, 0));
    vec2 screenTextureSize = vec2(1.0) / fwidth(texCoord0);
    float screenPixelRange = max(0.5 * dot(unitRange, screenTextureSize), 1.0);
    float screenPixelDistance = screenPixelRange * (signedDistance - 0.5);
    float alpha = clamp(screenPixelDistance + 0.5, 0.0, 1.0);
    if (alpha < 0.001) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}
