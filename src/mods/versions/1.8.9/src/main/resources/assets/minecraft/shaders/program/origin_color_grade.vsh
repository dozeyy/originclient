#version 110

// Color Saturation's post pass (1.8.9, same as 1.16.5). GLSL 110 like vanilla's own (1.8.9 / 1.16.5)
// post-effect programs and this module's motion blur: attribute/varying, not
// the in/out of 150. PostPass draws a quad from (0,0) to OutSize under ProjMat
// (y up); like vanilla 1.8.9's programs the texture lookup is flipped (below).
attribute vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

varying vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
    // Vanilla 1.8.9's own post programs (blit.vsh, sobel.vsh) flip y here, and
    // the chain ends in vanilla's blit, which flips again: every pass must flip
    // or the picture lands upside down (Will's 2026-10-20 playtest).
    texCoord.y = 1.0 - texCoord.y;
}
