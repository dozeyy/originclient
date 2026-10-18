#version 150

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;
uniform float Blend;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 curr = texture(InSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    // prev.a gates the mix: a freshly made prev target is cleared to alpha 0, so
    // the first frame (and the first after a resize) shows the plain frame
    // instead of darkening toward black - fail-soft.
    fragColor = vec4(mix(curr.rgb, prev.rgb, Blend * prev.a), 1.0);
}
