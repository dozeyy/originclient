#version 110

// Color Saturation's post pass (1.16.5). GLSL 110 like vanilla 1.16.5's own
// post-effect programs and this module's motion blur: attribute/varying, not
// the in/out of 150. PostPass draws a quad from (0,0) to OutSize under ProjMat
// (y up), and render-target textures are bottom-up, so no flip is needed.
attribute vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

varying vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
}
