#version 120
// Shadow pass: every shadow caster (terrain, entities, cosmetics) is drawn into the shadow map.
varying vec2 texcoord;
varying vec4 color;

void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;
	color = gl_Color;
}
