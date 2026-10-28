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

## Not yet built

The presenter proper:
- swapchain recreation on resize and rotation (`VK_ERROR_OUT_OF_DATE_KHR`, `SUBOPTIMAL`);
- pipelines and shaders for the retained scene; glyph atlas and text;
- damage, so an unchanged scene presents nothing;
- presentation timing via `VK_GOOGLE_display_timing` or Choreographer and fences;
- surface lifetime tied to `SurfaceHolder` callbacks;
- protected and HDR formats.

The probe proves the substrate on the device; it is not a renderer.
