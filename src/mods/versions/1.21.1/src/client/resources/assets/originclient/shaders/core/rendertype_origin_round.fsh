#version 150

// Rounded rectangle drawn as a true signed-distance field: the fragment shader
// computes the exact distance to a rounded box and resolves the edge (and the
// 1px border) with a screen-space smoothstep (fwidth). No baked mask, no
// per-pixel CPU coverage — the curve is mathematically perfect and stays smooth
// at any scale, which is the "vector rounded rects" the redesign asked for.

uniform vec2 RectHalf;      // half width/height in pixels
uniform float Radius;       // corner radius in pixels
uniform float Border;       // border thickness in pixels (0 = no border)
uniform vec4 FillColor;
uniform vec4 BorderColor;

in vec2 localPos;
out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}

void main() {
    float d = sdRoundBox(localPos, RectHalf, Radius);
    float aa = max(fwidth(d), 0.0001);
    float outer = 1.0 - smoothstep(-aa, aa, d);          // whole shape coverage
    float inner = 1.0 - smoothstep(-aa, aa, d + Border); // shape minus the border ring
    vec4 base = mix(BorderColor, FillColor, inner);
    float a = base.a * outer;
    if (a < 0.001) {
        discard;
    }
    fragColor = vec4(base.rgb, a);
}
