#version 330
#extension GL_ARB_separate_shader_objects : require
// 26.3: varyings bind by explicit location, like vanilla post/blit.fsh.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform BlendConfig {
    float Blend;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 curr = texture(InSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    // prev.a gates the mix: on the first frame (or if the pipeline ever clears
    // the prev target) alpha is 0, so we show the plain frame instead of
    // darkening toward black - fail-soft.
    fragColor = vec4(mix(curr.rgb, prev.rgb, Blend * prev.a), 1.0);
}
