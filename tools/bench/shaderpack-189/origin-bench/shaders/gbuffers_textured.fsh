#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 color;

void main() {
	gl_FragData[0] = texture2D(texture, texcoord) * texture2D(lightmap, lmcoord) * color;
}
