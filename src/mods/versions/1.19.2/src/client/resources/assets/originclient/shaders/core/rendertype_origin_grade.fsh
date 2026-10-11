#version 150

// Samples the rendered scene (Sampler0) and applies Saturation / Brightness /
// Contrast — the same math the old post-effect used, now driven from a core
// shader we invoke ourselves with full GL-state control (see ColorGrade), so it
// can't corrupt the vanilla main target the way the self-managed PostChain did.
uniform sampler2D Sampler0;

uniform float Saturation;
uniform float Brightness;
uniform float Contrast;

in vec2 uv;
out vec4 fragColor;

void main() {
    vec3 col = texture(Sampler0, uv).rgb;

    // Brightness: scale about black.
    col *= Brightness;
    // Contrast: scale about mid-grey.
    col = (col - 0.5) * Contrast + 0.5;
    // Saturation: mix toward luma.
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, Saturation);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
