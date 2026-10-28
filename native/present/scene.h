#ifndef TROUGH_SCENE_H
#define TROUGH_SCENE_H
#include <stddef.h>
#include <stdint.h>

/* GPU primitive renderer, the first scene operator of the presenter (docs/PRESENT.md).
 * Portable C over Vulkan 1.1: the same source runs on the phone's GPU (bionic build) and on
 * Mesa's lavapipe in the VM and CI (glibc build). Headless: renders into an offscreen RGBA8
 * image and reads it back, which is what the pixel oracle compares.
 *
 * Coverage: a pixel is covered when its centre (x + 0.5, y + 0.5) lies in [x0, x1) x [y0, y1)
 * of the rectangle and of its clip. Colour is premultiplied; blending is src-over in UNORM8. */
typedef struct {
    float x0, y0, x1, y1;          /* pixels, y down */
    float cx0, cy0, cx1, cy1;      /* clip, same rule */
    float r, g, b, a;              /* premultiplied, 0..1 */
} ps_rect;
typedef struct ps_gpu ps_gpu;

/* info receives "device|api|driver" for diagnostics. Returns 0, or a negative VkResult. */
int ps_open(ps_gpu **, char *info, size_t capacity);
/* Clears to transparent black, draws rects in order, copies the result to rgba (w*h*4 bytes,
 * rows top to bottom). gpu_ms: submit-to-fence time. */
int ps_render(ps_gpu *, const ps_rect *, size_t count, uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms);
void ps_close(ps_gpu *);
#endif
