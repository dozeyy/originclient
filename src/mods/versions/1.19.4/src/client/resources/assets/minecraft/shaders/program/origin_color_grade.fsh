#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

uniform float Saturation;
uniform float Brightness;
uniform float Contrast;

out vec4 fragColor;

void main() {
    vec4 c = texture(DiffuseSampler, texCoord);
    vec3 col = c.rgb;

    // Brightness: scale about black. 1.0 = normal, <1 darker, >1 brighter.
    col *= Brightness;

    // Contrast: scale about mid-grey. 1.0 = normal.
    col = (col - 0.5) * Contrast + 0.5;

    // Saturation: mix toward luma. 1.0 = normal, 0 = greyscale, >1 more vivid.
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, Saturation);

    fragColor = vec4(clamp(col, 0.0, 1.0), c.a);
}
