#version 150

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform BlendConfig {
    float Blend;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 curr = texture(InSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    // prev.a gates the mix: on the first frame (or if the pipeline ever clears
    // the prev target) alpha is 0, so we show the plain frame instead of
    // darkening toward black - fail-soft.
    fragColor = vec4(mix(curr.rgb, prev.rgb, Blend * prev.a), 1.0);
}
