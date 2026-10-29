#version 450
// One instance per glyph: an integer-aligned destination box and the glyph's coverage mask at
// an integer atlas origin. Coordinates are pixels, y down (docs/PRESENT.md).
layout(location = 0) in vec4 dest;    // x0 y0 x1 y1 (integers)
layout(location = 1) in vec4 src;     // atlas x y, kind, unused
layout(location = 2) in vec4 clip;
layout(location = 3) in vec4 color;   // premultiplied
layout(push_constant) uniform Viewport { vec2 size; } viewport;
layout(location = 0) flat out vec4 v_dest;
layout(location = 1) flat out vec4 v_src;
layout(location = 2) flat out vec4 v_clip;
layout(location = 3) flat out vec4 v_color;
void main() {
    const vec2 corner[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(0, 1), vec2(1, 0), vec2(1, 1), vec2(0, 1));
    vec2 p = mix(dest.xy, dest.zw, corner[gl_VertexIndex]);
    gl_Position = vec4(p / viewport.size * 2.0 - 1.0, 0.0, 1.0);
    v_dest = dest; v_src = src; v_clip = clip; v_color = color;
}
