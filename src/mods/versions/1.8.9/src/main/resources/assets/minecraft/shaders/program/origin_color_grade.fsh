#version 110

// The same grade as 1.17.1+'s origin_color_grade / rendertype_origin_grade,
// written in GLSL 110 (texture2D + gl_FragColor) for the 1.8.9 / 1.16.5 post pipeline.
uniform sampler2D DiffuseSampler;

uniform float Saturation;
uniform float Brightness;
uniform float Contrast;

varying vec2 texCoord;

void main() {
    vec4 c = texture2D(DiffuseSampler, texCoord);
    vec3 col = c.rgb;

    // Brightness: scale about black. 1.0 = normal, <1 darker, >1 brighter.
    col *= Brightness;

    // Contrast: scale about mid-grey. 1.0 = normal.
    col = (col - 0.5) * Contrast + 0.5;

    // Saturation: mix toward luma. 1.0 = normal, 0 = greyscale, >1 more vivid.
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, Saturation);

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), c.a);
}
