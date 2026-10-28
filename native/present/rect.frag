#version 450
layout(location = 0) flat in vec4 v_clip;
layout(location = 1) flat in vec4 v_color;
layout(location = 0) out vec4 out_color;
void main() {
    vec2 f = gl_FragCoord.xy;   // pixel centre
    if (f.x < v_clip.x || f.x >= v_clip.z || f.y < v_clip.y || f.y >= v_clip.w) discard;
    out_color = v_color;
}
