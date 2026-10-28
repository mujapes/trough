# Native presentation substrate

2026-10-28. This is the first piece of the presenter in DESIGN.md: "the native renderer
targets Vulkan with explicit buffer ownership and presentation fences; Android remains the
surface, input, IME, accessibility and compositor host". `native/android/present.c` is in the
bionic library ([BIONIC.md](BIONIC.md)), so it calls the device's own Vulkan driver through
Android's loader (`libvulkan.so`), and the window comes from `ANativeWindow_fromSurface`. No
forwarding layer is involved: unlike the Terminal VM's gfxstream path, every device extension
is available.

## Bring-up probe

`presentProbe(Surface, frames)` runs the whole path once:
- Instance with `VK_KHR_surface` and `VK_KHR_android_surface`, and the Android surface.
- The first physical device, with a queue family supporting graphics and present.
- A device with `VK_KHR_swapchain`.
- A FIFO swapchain at the window's extent. It uses RGBA8 when offered and minimum+1 images,
  with transfer-dst usage and the current transform.
- Two frames in flight, with an acquire semaphore and fence per frame slot and a render
  semaphore per image.
- Each frame: layout barrier, `vkCmdClearColorImage`, barrier to present, submit, present.

It reports the device, API and driver, extension count, format, extent and image count,
setup time and present-interval percentiles. Khronos headers come from Debian's
`libvulkan-dev` 1.4.309.0-1, pinned by `tools/bionic-deps.sh` and shipped in
`bionic-inputs/include`, so the phone rebuilds the library identically.

## Evidence (Pixel, bundle `68936cc115c8...`, `android.vulkan`)

- Device and driver: Mali-G710 (Tensor G2), Vulkan 1.4.343, 165 device extensions.
- Swapchain: 1080x624 RGBA8 surface (a third of the screen), 5 images, setup 17.3 ms.
- Pacing: 240 frames at the display's 60 Hz, intervals p50 16.55 ms, p95 20.38 ms,
  max 43.3 ms.

## Rectangle renderer and pixel oracle

`native/present/scene.{h,c}` is the first scene operator. It is portable C over Vulkan 1.1:
the same source runs on the phone's GPU (bionic library) and on Mesa's lavapipe (VM, CI).
- One instanced pipeline: six vertices per rectangle, with per-instance rectangle, clip and
  premultiplied colour.
- Coverage is pixel centres against half-open rectangles; clips are applied in the fragment
  shader by the same rule.
- Blending is src-over: ONE, ONE_MINUS_SRC_ALPHA.
- Rendering is headless into an offscreen RGBA8 target, with readback.
- GLSL in `rect.vert`/`rect.frag` is compiled by `tools/shaders.sh` (glslang) into the committed
  `shaders.h`, so builds need no shader compiler.

`native/present/oracle.c` compares every pixel against an independent CPU rasteriser:
- 60 random scenes up to 255x255 with up to 200 rectangles each.
- Coordinates on a 1/16-pixel grid, many on exact pixel centres, plus zero-area rectangles,
  clips, and alpha from 0 to 255.
- Tolerance is 2 per channel for UNORM rounding; coverage errors are counted separately.
- It also times clear+readback and a 20000-rectangle stress (heavy overdraw) at 1080x1920.

Evidence:
- lavapipe (VM, `tests/core_scene.sh`, CI): 1047177 pixels, 0 bad, max channel diff 1.
- Mutants on lavapipe: blending with SRC_ALPHA for colour gives 221985 bad pixels; making
  the right clip edge exclusive gives 430 bad pixels, exactly those whose centres lie on clip
  edges.
- Pixel 7, Mali-G710 (`android.scene`, bundle `17eb422716f5...`): 1047177 pixels, 0 bad, max
  channel diff 0. Clear+readback 1.73 ms; 20000-rectangle stress 374 ms. With UI-sized
  rectangles (up to 64 px), 20000 take 9.95 ms at 1080x1920.
- Glyphs and platform text: [TEXT_RENDERING.md](TEXT_RENDERING.md).

## Presenter on a Surface

`ps_swapchain` / `ps_present` (scene.c) render the rectangle and glyph scene into a FIFO
swapchain. `ps_present` reports 1 when the swapchain is out of date or suboptimal, and the
caller rebuilds it. `android.surface` presents rectangles and shaped text (Latin, Arabic,
Devanagari, CJK, Greek) to a SurfaceView. It copies each presented image before present, and
the copy must equal the offscreen render of the same scene byte for byte, both before and
after the view grows from 480 to 760 px (the swapchain is rebuilt).

The swapchain extent is the caller's size from `surfaceChanged`, clamped to the surface's
min/max. It is not `currentExtent`. The first Pixel run used `currentExtent`, which still
said 1080x480 after `surfaceChanged(1080x760)`. The swapchain kept the old size, and the
readback differed in exactly the 280 new rows (1209600 bytes). Android's swapchain sizes the
window's buffers from `imageExtent`, so the callback's size is authoritative. On X11 and
lavapipe, min = max = current, so the clamp still gives the current size. The JNI now refuses
a readback whose size disagrees with the swapchain (status -2), so this fault cannot be silent again.
Bundle `231f03730cdb...`: 0 differing bytes at 1080x480 and at 1080x760. Steady FIFO presents
take 14.6 ms of CPU wall time on average, which includes waiting for the next 60 Hz image.

## Frames in flight

Offscreen frame ring with partial atlas uploads and fence retirement: see [ATLAS.md](ATLAS.md).

## Not yet built

The presenter proper:
- rotation (pre-transform) and recreation driven by `SurfaceHolder` callbacks, not by the test;
- pipelines for the retained scene beyond rectangles and glyph masks;
- damage, so an unchanged scene presents nothing;
- presentation timing via `VK_GOOGLE_display_timing` or Choreographer and fences;
- surface lifetime tied to `SurfaceHolder` callbacks (destroy, recreate, background);
- protected and HDR formats.

The probe proves the substrate on the device; it is not a renderer.
