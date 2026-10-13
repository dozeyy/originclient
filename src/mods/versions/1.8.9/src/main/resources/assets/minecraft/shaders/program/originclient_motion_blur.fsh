#version 110

// GLSL 110 to match vanilla 1.8.9 / 1.16.5 post-effect shaders (texture2D +
// gl_FragColor, not texture()/named out of 150).
uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;
uniform float Amount;

varying vec2 texCoord;

void main() {
    vec4 cur = texture2D(DiffuseSampler, texCoord);
    vec4 prev = texture2D(PrevSampler, texCoord);
    gl_FragColor = vec4(mix(cur.rgb, prev.rgb, Amount), 1.0);
}
