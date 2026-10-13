#version 110

// GLSL 110 to match vanilla 1.8.9 / 1.16.5 post-effect shaders (this era's post
// programs use attribute/varying/gl_FragColor, not the in/out/texture() of
// 150). A 150 program is not guaranteed to link in the 1.8.9 post pipeline.
attribute vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

varying vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
}
