#version 450
layout(set = 0, binding = 0) uniform sampler2D atlas;    // R8 coverage
layout(set = 0, binding = 1) uniform sampler2D colors;   // RGBA8 colour glyphs, premultiplied
layout(location = 0) flat in vec4 v_dest;
layout(location = 1) flat in vec4 v_src;                 // atlas x y, kind (0 coverage, 1 colour)
layout(location = 2) flat in vec4 v_clip;
layout(location = 3) flat in vec4 v_color;
layout(location = 0) out vec4 out_color;
void main() {
    vec2 f = gl_FragCoord.xy;
    if (f.x < v_clip.x || f.x >= v_clip.z || f.y < v_clip.y || f.y >= v_clip.w) discard;
    ivec2 t = ivec2(v_src.xy) + ivec2(floor(f - v_dest.xy));    // exact texel, no filtering
    if (v_src.z > 0.5) out_color = texelFetch(colors, t, 0) * v_color.a;
    else out_color = v_color * texelFetch(atlas, t, 0).r;
}
