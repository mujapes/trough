/* core-scene: the rectangle renderer's pixel oracle on whatever Vulkan device is present
 * (lavapipe in the VM and CI). Prints the oracle's JSON; exit status is its verdict. */
#include <stdio.h>
int ps_oracle(char *out, size_t capacity);
int main(void) {
    static char out[4096];
    int ok = ps_oracle(out, sizeof out);
    puts(out);
    return ok ? 0 : 1;
}
