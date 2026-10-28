#version 450
// One instance per rectangle: six vertices make its two triangles. Coordinates are pixels,
// y down, mapped to clip space by the push-constant viewport size (docs/PRESENT.md).
layout(location = 0) in vec4 rect;    // x0 y0 x1 y1
layout(location = 1) in vec4 clip;    // x0 y0 x1 y1, half-open on pixel centres
layout(location = 2) in vec4 color;   // premultiplied rgba
layout(push_constant) uniform Viewport { vec2 size; } viewport;
layout(location = 0) flat out vec4 v_clip;
layout(location = 1) flat out vec4 v_color;
void main() {
    const vec2 corner[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(0, 1), vec2(1, 0), vec2(1, 1), vec2(0, 1));
    vec2 c = corner[gl_VertexIndex];
    vec2 p = mix(rect.xy, rect.zw, c);
    gl_Position = vec4(p / viewport.size * 2.0 - 1.0, 0.0, 1.0);
    v_clip = clip;
    v_color = color;
}
