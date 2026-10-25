#version 120
// Reads the shadow map so OptiFine keeps the shadow pass (an unused shadow map can be skipped).
uniform sampler2D gcolor;
uniform sampler2D shadow;
const int shadowMapResolution = 1024;
const float shadowDistance = 64.0;
varying vec2 texcoord;

void main() {
	vec3 c = texture2D(gcolor, texcoord).rgb;
	float s = texture2D(shadow, texcoord).r;
	gl_FragColor = vec4(c * (0.95 + 0.05 * s), 1.0);
}
