/* core-scene: the renderer's pixel oracles (rectangles, then glyph composition) on whatever
 * Vulkan device is present (lavapipe in the VM and CI). Exit status is their verdict. */
#include <stdio.h>
int ps_oracle(char *out, size_t capacity);
int ps_glyph_oracle(char *out, size_t capacity);
int ps_frames_oracle(char *out, size_t capacity);
int main(void) {
    static char out[4096];
    int ok = ps_oracle(out, sizeof out);
    puts(out);
    ok &= ps_glyph_oracle(out, sizeof out);
    puts(out);
    ok &= ps_frames_oracle(out, sizeof out);
    puts(out);
    return ok ? 0 : 1;
}
