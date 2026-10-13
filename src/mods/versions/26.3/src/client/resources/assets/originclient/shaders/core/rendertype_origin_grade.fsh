#version 330
#extension GL_ARB_separate_shader_objects : require
// 26.3: vanilla shaders bind varyings by explicit location (separate shader
// objects); these match core/position_tex_color.vsh (texCoord0 = 0, vertexColor = 1).

// Color Saturation: a full-screen grade (saturation / brightness / contrast)
// applied to the rendered WORLD, sampled from a copy of the main colour target.
//
// PER-VERSION DELTA (1.21.11): on 1.21.1 the three amounts arrived as real
// per-draw uniforms (Saturation/Brightness/Contrast) set right before an
// immediate-mode draw. This era has no such hook — a GuiElementRenderState names
// a pipeline and nothing else, and VertexConsumer only exposes
// Position/Color/UV0/UV1/UV2/Normal/LineWidth. So the three amounts ride the
// per-vertex COLOR channel instead: r = saturation, g = brightness,
// b = contrast, each stored as value/2 so the full 0..2 slider range fits in
// 0..1. One byte per channel is ~0.008 of slider range; the sliders step by
// 0.05, so the quantisation is invisible.

// 26.3 reordered this block (TextureMat moved up) -- it must match vanilla's
// include/dynamictransforms.glsl member for member or the program won't link.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 1) in vec4 vertexColor;
layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

// Rec. 709 luma — the same weights vanilla and every grading tool use, so
// desaturating leaves perceived brightness alone.
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec3 c = texture(Sampler0, texCoord0).rgb;

    float saturation = vertexColor.r * 2.0;
    float brightness = vertexColor.g * 2.0;
    float contrast   = vertexColor.b * 2.0;

    // saturation: blend between the greyscale luma and the original colour.
    // 0 = fully grey, 1 = untouched, >1 = pushed past the original.
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, saturation);

    // brightness: straight multiply.
    c *= brightness;

    // contrast: expand around mid grey.
    c = (c - 0.5) * contrast + 0.5;

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
