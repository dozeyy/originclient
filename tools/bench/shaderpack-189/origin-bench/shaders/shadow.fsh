#version 120
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 color;

void main() {
	vec4 c = texture2D(texture, texcoord) * color;
	if (c.a < 0.1) discard;
	gl_FragData[0] = c;
}
