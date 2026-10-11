#version 150

// Fullscreen color-grade pass. Position is given directly in clip space (an NDC
// -1..1 quad), so the pass is INDEPENDENT of whatever ModelView/Proj the game had
// set — it always covers the whole screen. UV0 carries the 0..1 sample coordinate
// into the source scene texture.
in vec3 Position;
in vec2 UV0;

out vec2 uv;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    uv = UV0;
}
