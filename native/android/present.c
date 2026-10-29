/* Native presentation bring-up (docs/PRESENT.md): Vulkan on the device GPU, presenting to an
 * Android Surface through VK_KHR_android_surface. One probe call creates the instance, picks a
 * device with a graphics+present queue, builds a FIFO swapchain on the window, clears and
 * presents a number of frames, and reports what the hardware offered and how presentation
 * paced. This is the substrate the presenter will own; no scene or text rendering yet. */
#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <vulkan/vulkan.h>

#define CLASS(name) Java_com_trough_hot_NativeCore_##name
enum { MAX_IMAGES = 8, MAX_FRAMES = 2000 };

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}
static int cmp(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}

JNIEXPORT jstring JNICALL CLASS(presentProbe)(JNIEnv *env, jclass c, jobject surface, jint frames) {
    (void)c;
    char out[2048], *step = "window";
    VkResult r = VK_SUCCESS;
    ANativeWindow *window = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR vs = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkSemaphore acquired[2] = {0}, rendered[MAX_IMAGES] = {0};
    VkFence fences[2] = {0};
    VkPhysicalDeviceProperties props;
    memset(&props, 0, sizeof props);
    uint32_t extensions = 0, image_count = 0, presented = 0, family = UINT32_MAX;
    VkExtent2D extent = {0, 0};
    VkFormat format = VK_FORMAT_UNDEFINED;
    double *intervals = NULL, first = 0, setup = now_ms();
    if (frames < 1 || frames > MAX_FRAMES) frames = 120;

    if (!(window = ANativeWindow_fromSurface(env, surface))) goto done;
    step = "instance";
    const char *instance_ext[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "trough", 1, "trough", 1, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 2, instance_ext};
    if ((r = vkCreateInstance(&ici, NULL, &instance))) goto done;
    step = "surface";
    VkAndroidSurfaceCreateInfoKHR sci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, NULL, 0, window};
    if ((r = vkCreateAndroidSurfaceKHR(instance, &sci, NULL, &vs))) goto done;

    step = "device";
    uint32_t n = 1;
    VkPhysicalDevice gpu;
    r = vkEnumeratePhysicalDevices(instance, &n, &gpu);
    if (r < 0 || !n) { if (!r) r = VK_ERROR_INITIALIZATION_FAILED; goto done; }
    r = VK_SUCCESS;
    vkGetPhysicalDeviceProperties(gpu, &props);
    vkEnumerateDeviceExtensionProperties(gpu, NULL, &extensions, NULL);
    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &families, NULL);
    VkQueueFamilyProperties qf[16];
    if (families > 16) families = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &families, qf);
    for (uint32_t i = 0; i < families && family == UINT32_MAX; i++) {
        VkBool32 present = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, vs, &present);
        if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) family = i;
    }
    if (family == UINT32_MAX) { r = VK_ERROR_FEATURE_NOT_PRESENT; goto done; }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo dq = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, family, 1, &priority};
    const char *device_ext[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &dq, 0, NULL, 1, device_ext, NULL};
    if ((r = vkCreateDevice(gpu, &dci, NULL, &device))) goto done;
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);

    step = "swapchain";
    VkSurfaceCapabilitiesKHR caps;
    if ((r = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(gpu, vs, &caps))) goto done;
    uint32_t nf = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, vs, &nf, NULL);
    VkSurfaceFormatKHR formats[32];
    if (nf > 32) nf = 32;
    vkGetPhysicalDeviceSurfaceFormatsKHR(gpu, vs, &nf, formats);
    if (!nf) { r = VK_ERROR_FORMAT_NOT_SUPPORTED; goto done; }
    VkSurfaceFormatKHR chosen = formats[0];
    for (uint32_t i = 0; i < nf; i++) if (formats[i].format == VK_FORMAT_R8G8B8A8_UNORM) chosen = formats[i];
    format = chosen.format;
    extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = (uint32_t)ANativeWindow_getWidth(window);
        extent.height = (uint32_t)ANativeWindow_getHeight(window);
    }
    uint32_t want = caps.minImageCount + 1;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) { r = VK_ERROR_FEATURE_NOT_PRESENT; goto done; }
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & alpha)) alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    VkSwapchainCreateInfoKHR swi = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, NULL, 0, vs, want, chosen.format,
        chosen.colorSpace, extent, 1, VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL,
        caps.currentTransform, alpha, VK_PRESENT_MODE_FIFO_KHR, VK_TRUE, VK_NULL_HANDLE};
    if ((r = vkCreateSwapchainKHR(device, &swi, NULL, &swapchain))) goto done;
    VkImage images[MAX_IMAGES];
    image_count = MAX_IMAGES;
    if ((r = vkGetSwapchainImagesKHR(device, swapchain, &image_count, images)) < 0) goto done;
    r = VK_SUCCESS;

    step = "commands";
    VkCommandPoolCreateInfo cpi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL,
        VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, family};
    if ((r = vkCreateCommandPool(device, &cpi, NULL, &pool))) goto done;
    VkCommandBuffer cmd[2];
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 2};
    if ((r = vkAllocateCommandBuffers(device, &cai, cmd))) goto done;
    VkSemaphoreCreateInfo semi = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, NULL, 0};
    VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT};
    for (int i = 0; i < 2; i++)
        if ((r = vkCreateSemaphore(device, &semi, NULL, &acquired[i])) || (r = vkCreateFence(device, &fi, NULL, &fences[i]))) goto done;
    /* One render semaphore per image: present may still be waiting on the previous use. */
    for (uint32_t i = 0; i < image_count; i++)
        if ((r = vkCreateSemaphore(device, &semi, NULL, &rendered[i]))) goto done;

    step = "present";
    setup = now_ms() - setup;
    intervals = calloc((size_t)frames, sizeof *intervals);
    if (!intervals) { r = VK_ERROR_OUT_OF_HOST_MEMORY; goto done; }
    double last = 0;
    for (int f = 0; f < frames; f++) {
        int slot = f % 2;
        if ((r = vkWaitForFences(device, 1, &fences[slot], VK_TRUE, 1000000000ull))) goto done;
        uint32_t index;
        r = vkAcquireNextImageKHR(device, swapchain, 1000000000ull, acquired[slot], VK_NULL_HANDLE, &index);
        if (r < 0) goto done;
        vkResetFences(device, 1, &fences[slot]);
        vkResetCommandBuffer(cmd[slot], 0);
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
        vkBeginCommandBuffer(cmd[slot], &bi);
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageMemoryBarrier to_dst = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
            images[index], range};
        vkCmdPipelineBarrier(cmd[slot], VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_dst);
        float t = (float)f / (float)frames;
        VkClearColorValue color = {{0.05f + 0.2f * t, 0.08f, 0.12f + 0.3f * (1 - t), 1.0f}};
        vkCmdClearColorImage(cmd[slot], images[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);
        VkImageMemoryBarrier to_present = to_dst;
        to_present.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; to_present.dstAccessMask = 0;
        to_present.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; to_present.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        vkCmdPipelineBarrier(cmd[slot], VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &to_present);
        vkEndCommandBuffer(cmd[slot]);
        VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 1, &acquired[slot], &wait, 1, &cmd[slot], 1, &rendered[index]};
        if ((r = vkQueueSubmit(queue, 1, &si, fences[slot]))) goto done;
        VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 1, &rendered[index], 1, &swapchain, &index, NULL};
        r = vkQueuePresentKHR(queue, &pi);
        if (r < 0) goto done;
        double at = now_ms();
        if (f) intervals[presented - 1] = at - last; else first = at;
        last = at;
        presented++;
    }
    r = VK_SUCCESS;
    step = "done";

done:;
    double p50 = 0, p95 = 0, max = 0, total = presented > 1 ? (now_ms() - first) : 0;
    if (presented > 2) {
        size_t m = presented - 1;
        qsort(intervals, m, sizeof *intervals, cmp);
        p50 = intervals[m / 2]; p95 = intervals[m * 95 / 100]; max = intervals[m - 1];
    }
    if (device) vkDeviceWaitIdle(device);
    for (int i = 0; i < 2; i++) {
        if (acquired[i]) vkDestroySemaphore(device, acquired[i], NULL);
        if (fences[i]) vkDestroyFence(device, fences[i], NULL);
    }
    for (uint32_t i = 0; i < MAX_IMAGES; i++) if (rendered[i]) vkDestroySemaphore(device, rendered[i], NULL);
    if (pool) vkDestroyCommandPool(device, pool, NULL);
    if (swapchain) vkDestroySwapchainKHR(device, swapchain, NULL);
    if (device) vkDestroyDevice(device, NULL);
    if (vs) vkDestroySurfaceKHR(instance, vs, NULL);
    if (instance) vkDestroyInstance(instance, NULL);
    if (window) ANativeWindow_release(window);
    free(intervals);
    snprintf(out, sizeof out,
        "{\"ok\":%s,\"step\":\"%s\",\"vk_result\":%d,\"device\":\"%s\",\"api\":\"%u.%u.%u\",\"driver\":%u,"
        "\"vendor\":%u,\"device_extensions\":%u,\"format\":%d,\"width\":%u,\"height\":%u,\"images\":%u,"
        "\"setup_ms\":%.1f,\"frames\":%u,\"interval_p50_ms\":%.2f,\"interval_p95_ms\":%.2f,"
        "\"interval_max_ms\":%.2f,\"elapsed_ms\":%.1f}",
        r == VK_SUCCESS && !strcmp(step, "done") ? "true" : "false", step, (int)r, props.deviceName,
        VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion),
        props.driverVersion, props.vendorID, extensions, (int)format, extent.width, extent.height, image_count,
        setup, presented, p50, p95, max, total);
    return (*env)->NewStringUTF(env, out);
}

/* The presenter on an Android Surface (docs/PRESENT.md): scene.c's swapchain path. */
#include "scene.h"
typedef struct { ps_gpu *g; ANativeWindow *window; VkSurfaceKHR surface; } presenter;
static int upload(JNIEnv *env, ps_gpu *g, jbyteArray atlas, jint aw, jint ah) {
    if (!atlas) return 0;
    if (aw <= 0 || ah <= 0 || (*env)->GetArrayLength(env, atlas) != aw * ah) return -1;
    jbyte *a = (*env)->GetByteArrayElements(env, atlas, NULL);
    int st = a ? ps_atlas(g, (const uint8_t *)a, (uint32_t)aw, (uint32_t)ah) : -1;
    if (a) (*env)->ReleaseByteArrayElements(env, atlas, a, JNI_ABORT);
    return st;
}
/* A presenting GPU on an Android Surface, for other modules (derive.c): ps_close releases the
 * Vulkan surface; the caller releases *window. Returns 0 or a status. */
int trough_surface_gpu(JNIEnv *env, jobject surface, uint32_t w, uint32_t h, ps_gpu **out, ANativeWindow **window) {
    VkSurfaceKHR vs = VK_NULL_HANDLE;
    *out = NULL; *window = NULL;
    int st = ps_open_surface(out, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME, NULL, 0);
    if (!st && !(*window = ANativeWindow_fromSurface(env, surface))) st = -1;
    if (!st) {
        VkAndroidSurfaceCreateInfoKHR sci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, NULL, 0, *window};
        VkResult r = vkCreateAndroidSurfaceKHR((VkInstance)ps_instance(*out), &sci, NULL, &vs);
        st = r ? (int)r : 0;
    }
    if (!st) st = ps_swapchain(*out, (uint64_t)(uintptr_t)vs, w, h);
    if (st) {
        if (*out) ps_close(*out);
        if (*window) ANativeWindow_release(*window);
        *out = NULL; *window = NULL;
    }
    return st;
}
/* Returns a handle (> 0) or -status. */
JNIEXPORT jlong JNICALL CLASS(surfaceOpen)(JNIEnv *env, jclass c, jobject surface, jint w, jint h) {
    (void)c;
    presenter *p = calloc(1, sizeof *p);
    if (!p) return -1;
    int st = ps_open_surface(&p->g, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME, NULL, 0);
    if (!st && !(p->window = ANativeWindow_fromSurface(env, surface))) st = -1;
    if (!st) {
        VkAndroidSurfaceCreateInfoKHR sci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, NULL, 0, p->window};
        VkResult r = vkCreateAndroidSurfaceKHR((VkInstance)ps_instance(p->g), &sci, NULL, &p->surface);
        st = r ? (int)r : 0;
    }
    if (!st) st = ps_swapchain(p->g, (uint64_t)(uintptr_t)p->surface, (uint32_t)w, (uint32_t)h);
    if (st) {
        if (p->g) ps_close(p->g);   /* owns the surface once handed to ps_swapchain */
        if (p->window) ANativeWindow_release(p->window);
        free(p);
        return st < 0 ? st : -st;
    }
    return (jlong)(intptr_t)p;
}
JNIEXPORT jint JNICALL CLASS(surfaceResize)(JNIEnv *env, jclass c, jlong handle, jint w, jint h) {
    (void)env; (void)c;
    presenter *p = (presenter *)(intptr_t)handle;
    return ps_swapchain(p->g, (uint64_t)(uintptr_t)p->surface, (uint32_t)w, (uint32_t)h);
}
/* Presents rects (12 floats each) then glyphs (16 floats each); the atlas, if given, replaces
 * the previous one. result[0] = status (0, 1 = rebuild the swapchain, < 0 error), result[1] =
 * CPU ms. With shot, returns the presented pixels (RGBA), else null. */
JNIEXPORT jbyteArray JNICALL CLASS(surfacePresent)(JNIEnv *env, jclass c, jlong handle, jfloatArray rects, jfloatArray glyphs,
                                                    jbyteArray atlas, jint aw, jint ah, jint w, jint h, jboolean shot, jdoubleArray result) {
    (void)c;
    presenter *p = (presenter *)(intptr_t)handle;
    double out[2] = {-1, 0};
    jbyteArray pixels = NULL;
    jsize nr = rects ? (*env)->GetArrayLength(env, rects) : 0, ng = glyphs ? (*env)->GetArrayLength(env, glyphs) : 0;
    uint32_t ew, eh;
    ps_extent(p->g, &ew, &eh);
    uint8_t *rgba = shot ? malloc((size_t)w * h * 4) : NULL;
    /* A shot is only taken at the swapchain's own size (-2: the caller's size disagrees). */
    int st = nr % 12 || ng % 16 || (shot && !rgba) ? -1 : shot && (ew != (uint32_t)w || eh != (uint32_t)h) ? -2
        : upload(env, p->g, atlas, aw, ah);
    jfloat *r = !st && nr ? (*env)->GetFloatArrayElements(env, rects, NULL) : NULL;
    jfloat *g = !st && ng ? (*env)->GetFloatArrayElements(env, glyphs, NULL) : NULL;
    if (!st) st = ps_present(p->g, (const ps_rect *)r, (size_t)nr / 12, (const ps_glyph *)g, (size_t)ng / 16, rgba, &out[1]);
    if (r) (*env)->ReleaseFloatArrayElements(env, rects, r, JNI_ABORT);
    if (g) (*env)->ReleaseFloatArrayElements(env, glyphs, g, JNI_ABORT);
    out[0] = st;
    if (!st && rgba && (pixels = (*env)->NewByteArray(env, w * h * 4)))
        (*env)->SetByteArrayRegion(env, pixels, 0, w * h * 4, (const jbyte *)rgba);
    free(rgba);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 2, out);
    return pixels;
}
JNIEXPORT void JNICALL CLASS(surfaceClose)(JNIEnv *env, jclass c, jlong handle) {
    (void)env; (void)c;
    presenter *p = (presenter *)(intptr_t)handle;
    ps_close(p->g);
    ANativeWindow_release(p->window);
    free(p);
}
/* Offscreen reference for the same scene. */
JNIEXPORT jbyteArray JNICALL CLASS(sceneRender)(JNIEnv *env, jclass c, jint w, jint h, jfloatArray rects, jfloatArray glyphs,
                                                 jbyteArray atlas, jint aw, jint ah) {
    (void)c;
    ps_gpu *g;
    if (ps_open(&g, NULL, 0)) return NULL;
    jsize nr = rects ? (*env)->GetArrayLength(env, rects) : 0, ng = glyphs ? (*env)->GetArrayLength(env, glyphs) : 0;
    uint8_t *rgba = malloc((size_t)w * h * 4);
    int st = nr % 12 || ng % 16 || !rgba ? -1 : upload(env, g, atlas, aw, ah);
    jfloat *r = !st && nr ? (*env)->GetFloatArrayElements(env, rects, NULL) : NULL;
    jfloat *q = !st && ng ? (*env)->GetFloatArrayElements(env, glyphs, NULL) : NULL;
    if (!st) st = ps_render_text(g, (const ps_rect *)r, (size_t)nr / 12, (const ps_glyph *)q, (size_t)ng / 16, (uint32_t)w, (uint32_t)h, rgba, NULL);
    if (r) (*env)->ReleaseFloatArrayElements(env, rects, r, JNI_ABORT);
    if (q) (*env)->ReleaseFloatArrayElements(env, glyphs, q, JNI_ABORT);
    ps_close(g);
    jbyteArray out = NULL;
    if (!st && (out = (*env)->NewByteArray(env, w * h * 4))) (*env)->SetByteArrayRegion(env, out, 0, w * h * 4, (const jbyte *)rgba);
    free(rgba);
    return out;
}
