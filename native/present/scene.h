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

/* A glyph: an integer-aligned destination box, its coverage mask at an integer atlas origin
 * (the box's size), a clip and a premultiplied colour multiplied by the mask's coverage. */
typedef struct {
    float x0, y0, x1, y1;
    float u, v, unused0, unused1;
    float cx0, cy0, cx1, cy1;
    float r, g, b, a;
} ps_glyph;

/* info receives "device|api|driver" for diagnostics. Returns 0, or a negative VkResult. */
int ps_open(ps_gpu **, char *info, size_t capacity);
/* Clears to transparent black, draws rects in order, copies the result to rgba (w*h*4 bytes,
 * rows top to bottom). gpu_ms: submit-to-fence time. */
int ps_render(ps_gpu *, const ps_rect *, size_t count, uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms);
/* Replaces the R8 coverage atlas (w*h bytes, rows top to bottom). */
int ps_atlas(ps_gpu *, const uint8_t *coverage, uint32_t w, uint32_t h);
/* Rectangles first, then glyphs over them, in one pass; otherwise as ps_render. */
int ps_render_text(ps_gpu *, const ps_rect *, size_t rects, const ps_glyph *, size_t glyphs,
                   uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms);
/* Presenting. ps_open_surface creates the instance with VK_KHR_surface plus the platform's
 * surface extension (e.g. "VK_KHR_android_surface") and the device with VK_KHR_swapchain;
 * the caller creates the platform surface from ps_instance and hands it to ps_swapchain, which
 * (re)builds a FIFO swapchain at the given extent and takes ownership of the surface. */
int ps_open_surface(ps_gpu **, const char *platform_extension, char *info, size_t capacity);
void *ps_instance(ps_gpu *);   /* VkInstance */
int ps_swapchain(ps_gpu *, uint64_t surface, uint32_t w, uint32_t h);
/* Draws one frame (rects, then glyphs) into the next swapchain image and presents it. If rgba
 * is non-null the presented image is also copied there (w*h*4). Returns 0, 1 if the swapchain
 * is out of date or suboptimal (call ps_swapchain again), or a negative VkResult. */
/* The swapchain's extent (0 x 0 without one): the size of ps_present's rgba. */
void ps_extent(ps_gpu *, uint32_t *w, uint32_t *h);
int ps_present(ps_gpu *, const ps_rect *, size_t rects, const ps_glyph *, size_t glyphs, uint8_t *rgba, double *cpu_ms);
/* Frames in flight, offscreen (docs/ATLAS.md). ps_frames_init creates a persistent w x h R8
 * atlas (cleared, kept in GENERAL layout so a frame can upload into some regions while earlier
 * frames still sample others) and a ring of PS_FRAMES targets. A frame uploads its rectangles
 * into the atlas, then draws, then copies its target for readback, and is submitted without
 * waiting; the caller's residency (core/atlas) guarantees no frame in flight samples a region
 * a later frame overwrites, which is why no barrier orders uploads after earlier frames. */
#define PS_FRAMES 3
typedef struct { uint16_t x, y, w, h; uint32_t offset; } ps_upload;   /* offset into pixels; rows of w bytes */
int ps_frames_init(ps_gpu *, uint32_t w, uint32_t h, uint32_t atlas_w, uint32_t atlas_h);
/* Waits only when the ring slot's previous frame is still pending. *number = this frame (1, 2, ...). */
int ps_frame_submit(ps_gpu *, const ps_upload *, size_t uploads, const uint8_t *pixels, size_t pixel_bytes,
                    const ps_rect *, size_t rects, const ps_glyph *, size_t glyphs, uint64_t *number);
/* The same frame drawn into the next swapchain image (ps_swapchain) and presented; with shot,
 * the presented image is also kept for ps_frame_pixels (the swapchain extent must equal the
 * ring's w x h). Returns 0, 1 if the swapchain must be rebuilt, or a negative VkResult. */
int ps_frame_present(ps_gpu *, const ps_upload *, size_t uploads, const uint8_t *pixels, size_t pixel_bytes,
                     const ps_rect *, size_t rects, const ps_glyph *, size_t glyphs, int shot, uint64_t *number);
/* Presentation timing (VK_GOOGLE_display_timing): frames presented since the last call, with
 * the time each reached the display (CLOCK_MONOTONIC ns) and the refresh period. -1 when the
 * device or swapchain has no display timing. */
typedef struct { uint64_t frame, desired_ns, actual_ns, earliest_ns, margin_ns; } ps_present_time;
int ps_present_timing(ps_gpu *, ps_present_time *, size_t capacity, uint64_t *refresh_ns);
/* Non-blocking: the newest frame number whose fence (and all earlier ones) has signalled. */
uint64_t ps_frames_retired(ps_gpu *);
/* A retired frame's pixels (w*h*4), while its ring slot has not been reused. */
int ps_frame_pixels(ps_gpu *, uint64_t number, uint8_t *rgba);
/* Blocks until frame `number` (and all before it) has retired. */
int ps_frames_wait(ps_gpu *, uint64_t number);
/* How many submitted frames are not yet retired. */
int ps_frames_pending(ps_gpu *);
void ps_close(ps_gpu *);
#endif
