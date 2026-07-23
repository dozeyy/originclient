#version 150

in vec3 Position;
in vec2 UV0;   // local coordinate in pixels, measured from the rect's centre

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 localPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    localPos = UV0;
}
