#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;
uniform float Amount;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 cur = texture(DiffuseSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    fragColor = vec4(mix(cur.rgb, prev.rgb, Amount), 1.0);
}
