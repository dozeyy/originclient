#version 330

// Origin's mod-icon atlas (mod_icons_sdf.png): a single-channel signed distance
// field per cell, baked by tools/mod-menu/generate_icons.py. Coverage is
// reconstructed in SCREEN space (via fwidth) so every glyph edge is one screen
// pixel soft at any GUI scale -- same maths as 1.21.1's rendertype_origin_icon,
// and as the MSDF text shader minus the 3-channel median.
//
// PER-VERSION DELTA (1.21.6-1.21.8): pipeline shaders read ColorModulator from the
// std140 DynamicTransforms block, and on this era that block ends with a fifth
// member, LineWidth. A std140 block must be declared IDENTICALLY in both stages or
// the program fails to link and the icons silently never draw (the exact bug the
// colour grade hit). Matches this module's rendertype_origin_msdf.fsh.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

uniform sampler2D Sampler0;

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
