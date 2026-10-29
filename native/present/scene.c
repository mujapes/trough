#define _POSIX_C_SOURCE 200809L
#include "scene.h"
#include "shaders.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <vulkan/vulkan.h>

#define FORMAT VK_FORMAT_R8G8B8A8_UNORM

struct ps_gpu {
    VkInstance instance;
    VkPhysicalDevice gpu;
    VkPhysicalDeviceMemoryProperties memory;
    VkDevice device;
    VkQueue queue;
    VkCommandPool pool;
    VkCommandBuffer cmd;
    VkFence fence;
    VkRenderPass pass;
    VkPipelineLayout layout, glyph_layout;
    VkPipeline pipeline, glyph_pipeline;
    VkDescriptorSetLayout set_layout;
    VkDescriptorPool descriptors;
    VkDescriptorSet set;
    VkSampler sampler;
    VkImage atlas; VkDeviceMemory atlas_mem; VkImageView atlas_view; uint32_t atlas_w, atlas_h;
    VkBuffer glyphs; VkDeviceMemory glyphs_mem; void *glyphs_map; size_t glyph_capacity;
    /* Presentation (ps_open_surface). */
    int presenting;
    uint32_t family;
    VkSurfaceKHR surface; VkSwapchainKHR swapchain; VkExtent2D extent;
    uint32_t images; VkImage image[8]; VkImageView image_view[8]; VkFramebuffer image_fb[8];
    VkSemaphore acquired, rendered[8];
    VkBuffer shot; VkDeviceMemory shot_mem; void *shot_map; VkDeviceSize shot_size;
    /* Sized on demand. */
    uint32_t w, h;
    VkImage target; VkDeviceMemory target_mem; VkImageView view; VkFramebuffer framebuffer;
    VkBuffer readback; VkDeviceMemory readback_mem; void *readback_map;
    VkBuffer vertices; VkDeviceMemory vertices_mem; void *vertices_map; size_t vertex_capacity;
    /* Frames in flight (ps_frames_init): a persistent GENERAL-layout atlas and a ring. */
    struct ps_frame {
        VkCommandBuffer cmd; VkFence fence; VkSemaphore acquired; int pending; uint64_t number;
        VkImage target; VkDeviceMemory target_mem; VkImageView view; VkFramebuffer fb;
        VkBuffer readback; VkDeviceMemory readback_mem; void *readback_map;
        VkBuffer rects; VkDeviceMemory rects_mem; void *rects_map; size_t rects_cap;
        VkBuffer glyphs; VkDeviceMemory glyphs_mem; void *glyphs_map; size_t glyphs_cap;
        VkBuffer staging; VkDeviceMemory staging_mem; void *staging_map; size_t staging_cap;
    } frame[PS_FRAMES];
    int frames; uint32_t fw, fh; uint64_t submitted, retired;
};

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}
static int memory_type(ps_gpu *g, uint32_t bits, VkMemoryPropertyFlags want, VkMemoryPropertyFlags prefer) {
    int fallback = -1;
    for (uint32_t i = 0; i < g->memory.memoryTypeCount; i++) {
        VkMemoryPropertyFlags f = g->memory.memoryTypes[i].propertyFlags;
        if (!(bits & (1u << i)) || (f & want) != want) continue;
        if ((f & prefer) == prefer) return (int)i;
        if (fallback < 0) fallback = (int)i;
    }
    return fallback;
}
static VkResult allocate(ps_gpu *g, VkMemoryRequirements req, VkMemoryPropertyFlags want, VkMemoryPropertyFlags prefer, VkDeviceMemory *mem) {
    int type = memory_type(g, req.memoryTypeBits, want, prefer);
    if (type < 0) return VK_ERROR_FEATURE_NOT_PRESENT;
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, req.size, (uint32_t)type};
    return vkAllocateMemory(g->device, &ai, NULL, mem);
}
static VkResult host_buffer(ps_gpu *g, VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags prefer,
                            VkBuffer *b, VkDeviceMemory *m, void **map) {
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, size, usage, VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkResult r = vkCreateBuffer(g->device, &bi, NULL, b);
    if (r) return r;
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(g->device, *b, &req);
    /* Coherent is required: no explicit flush/invalidate in this path. */
    if ((r = allocate(g, req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, prefer, m))) return r;
    if ((r = vkBindBufferMemory(g->device, *b, *m, 0))) return r;
    return vkMapMemory(g->device, *m, 0, VK_WHOLE_SIZE, 0, map);
}
static VkShaderModule module(ps_gpu *g, const uint32_t *code, size_t size) {
    VkShaderModuleCreateInfo si = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, size, code};
    VkShaderModule m = VK_NULL_HANDLE;
    vkCreateShaderModule(g->device, &si, NULL, &m);
    return m;
}

static VkResult build(ps_gpu *, const uint32_t *, size_t, const uint32_t *, size_t, uint32_t, uint32_t, VkPipelineLayout, VkPipeline *);
static VkResult pipeline(ps_gpu *g) {
    VkAttachmentDescription color = {0, FORMAT, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR,
        VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, NULL, 1, &ref, NULL, NULL, 0, NULL};
    /* Before: the image's layout transition waits for the acquire semaphore's stage (colour
     * output). After: transfer (readback or the present barrier) waits for the pass. */
    VkSubpassDependency deps[2] = {
        {VK_SUBPASS_EXTERNAL, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
         0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0},
        {0, VK_SUBPASS_EXTERNAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, 0}};
    VkRenderPassCreateInfo rp = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, NULL, 0, 1, &color, 1, &sub, 2, deps};
    VkResult r = vkCreateRenderPass(g->device, &rp, NULL, &g->pass);
    if (r) return r;
    VkPushConstantRange push = {VK_SHADER_STAGE_VERTEX_BIT, 0, 8};
    VkPipelineLayoutCreateInfo li = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 0, NULL, 1, &push};
    if ((r = vkCreatePipelineLayout(g->device, &li, NULL, &g->layout))) return r;
    if ((r = build(g, rect_vert, sizeof rect_vert, rect_frag, sizeof rect_frag, sizeof(ps_rect), 3, g->layout, &g->pipeline))) return r;
    /* Glyphs: one combined image sampler (texelFetch ignores its filtering). */
    VkDescriptorSetLayoutBinding binding = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL};
    VkDescriptorSetLayoutCreateInfo sl = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL, 0, 1, &binding};
    if ((r = vkCreateDescriptorSetLayout(g->device, &sl, NULL, &g->set_layout))) return r;
    VkPipelineLayoutCreateInfo gl = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &g->set_layout, 1, &push};
    if ((r = vkCreatePipelineLayout(g->device, &gl, NULL, &g->glyph_layout))) return r;
    if ((r = build(g, glyph_vert, sizeof glyph_vert, glyph_frag, sizeof glyph_frag, sizeof(ps_glyph), 4, g->glyph_layout, &g->glyph_pipeline))) return r;
    VkDescriptorPoolSize size = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo dp = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, 1, 1, &size};
    if ((r = vkCreateDescriptorPool(g->device, &dp, NULL, &g->descriptors))) return r;
    VkDescriptorSetAllocateInfo da = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, g->descriptors, 1, &g->set_layout};
    if ((r = vkAllocateDescriptorSets(g->device, &da, &g->set))) return r;
    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, NULL, 0, VK_FILTER_NEAREST, VK_FILTER_NEAREST,
        VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 0, VK_FALSE, 1, VK_FALSE, VK_COMPARE_OP_ALWAYS, 0, 0,
        VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK, VK_FALSE};
    return vkCreateSampler(g->device, &si, NULL, &g->sampler);
}

/* A pipeline over the shared render pass: per-instance vec4 attributes, src-over blending. */
static VkResult build(ps_gpu *g, const uint32_t *vcode, size_t vsize, const uint32_t *fcode, size_t fsize,
                      uint32_t stride, uint32_t attributes, VkPipelineLayout layout, VkPipeline *out) {
    VkResult r;
    VkShaderModule vs = module(g, vcode, vsize), fs = module(g, fcode, fsize);
    if (!vs || !fs) { r = VK_ERROR_INITIALIZATION_FAILED; goto out; }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, vs, "main", NULL},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", NULL}};
    VkVertexInputBindingDescription bind = {0, stride, VK_VERTEX_INPUT_RATE_INSTANCE};
    VkVertexInputAttributeDescription attrs[4];
    for (uint32_t i = 0; i < attributes; i++) attrs[i] = (VkVertexInputAttributeDescription){i, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 16 * i};
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO, NULL, 0, 1, &bind, attributes, attrs};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0,
        VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_FALSE};
    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1, NULL};
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, NULL, 0,
        VK_FALSE, VK_FALSE, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, VK_FALSE, 0, 0, 0, 1.0f};
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, NULL, 0,
        VK_SAMPLE_COUNT_1_BIT, VK_FALSE, 0, NULL, VK_FALSE, VK_FALSE};
    VkPipelineColorBlendAttachmentState blend = {VK_TRUE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
        VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, 0xf};
    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, NULL, 0,
        VK_FALSE, VK_LOGIC_OP_COPY, 1, &blend, {0, 0, 0, 0}};
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 2, dyn};
    VkGraphicsPipelineCreateInfo pi = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, NULL, 0, 2, stages, &vi, &ia, NULL,
        &vp, &rs, &ms, NULL, &cb, &ds, layout, g->pass, 0, VK_NULL_HANDLE, -1};
    r = vkCreateGraphicsPipelines(g->device, VK_NULL_HANDLE, 1, &pi, NULL, out);
out:
    if (vs) vkDestroyShaderModule(g->device, vs, NULL);
    if (fs) vkDestroyShaderModule(g->device, fs, NULL);
    return r;
}

static int open_gpu(ps_gpu **out, const char *platform, char *info, size_t capacity) {
    *out = NULL;
    ps_gpu *g = calloc(1, sizeof *g);
    if (!g) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "trough", 1, "trough", 1, VK_API_VERSION_1_1};
    const char *ext[2] = {VK_KHR_SURFACE_EXTENSION_NAME, platform};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, platform ? 2 : 0, platform ? ext : NULL};
    g->presenting = platform != NULL;
    VkResult r = vkCreateInstance(&ici, NULL, &g->instance);
    uint32_t n = 1, family = UINT32_MAX;
    if (!r) { r = vkEnumeratePhysicalDevices(g->instance, &n, &g->gpu); if (r == VK_INCOMPLETE) r = VK_SUCCESS; }
    if (!r && !n) r = VK_ERROR_INITIALIZATION_FAILED;
    if (r) { ps_close(g); return -(int)(r < 0 ? -r : r); }
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(g->gpu, &props);
    vkGetPhysicalDeviceMemoryProperties(g->gpu, &g->memory);
    if (info) snprintf(info, capacity, "%s|%u.%u.%u|%u", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
                       VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion), props.driverVersion);
    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g->gpu, &families, NULL);
    VkQueueFamilyProperties qf[16];
    if (families > 16) families = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(g->gpu, &families, qf);
    for (uint32_t i = 0; i < families && family == UINT32_MAX; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) family = i;
    if (family == UINT32_MAX) { ps_close(g); return -(int)-VK_ERROR_FEATURE_NOT_PRESENT; }
    float priority = 1;
    VkDeviceQueueCreateInfo dq = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, family, 1, &priority};
    const char *swap = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    g->family = family;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &dq, 0, NULL, g->presenting ? 1 : 0, g->presenting ? &swap : NULL, NULL};
    if (!(r = vkCreateDevice(g->gpu, &dci, NULL, &g->device))) {
        vkGetDeviceQueue(g->device, family, 0, &g->queue);
        VkCommandPoolCreateInfo cp = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, family};
        r = vkCreateCommandPool(g->device, &cp, NULL, &g->pool);
    }
    if (!r) {
        VkCommandBufferAllocateInfo ca = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, g->pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        r = vkAllocateCommandBuffers(g->device, &ca, &g->cmd);
    }
    if (!r) { VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, 0}; r = vkCreateFence(g->device, &fi, NULL, &g->fence); }
    if (!r) r = pipeline(g);
    if (r) { ps_close(g); return -(int)(r < 0 ? -r : r); }
    *out = g;
    return 0;
}

int ps_open(ps_gpu **out, char *info, size_t capacity) { return open_gpu(out, NULL, info, capacity); }
int ps_open_surface(ps_gpu **out, const char *platform, char *info, size_t capacity) {
    if (!platform) return -(int)-VK_ERROR_INITIALIZATION_FAILED;
    return open_gpu(out, platform, info, capacity);
}
void *ps_instance(ps_gpu *g) { return g ? (void *)g->instance : NULL; }

static void drop_target(ps_gpu *g) {
    if (g->framebuffer) vkDestroyFramebuffer(g->device, g->framebuffer, NULL);
    if (g->view) vkDestroyImageView(g->device, g->view, NULL);
    if (g->target) vkDestroyImage(g->device, g->target, NULL);
    if (g->target_mem) vkFreeMemory(g->device, g->target_mem, NULL);
    if (g->readback) vkDestroyBuffer(g->device, g->readback, NULL);
    if (g->readback_mem) vkFreeMemory(g->device, g->readback_mem, NULL);
    g->framebuffer = VK_NULL_HANDLE; g->view = VK_NULL_HANDLE; g->target = VK_NULL_HANDLE;
    g->target_mem = VK_NULL_HANDLE; g->readback = VK_NULL_HANDLE; g->readback_mem = VK_NULL_HANDLE;
    g->w = g->h = 0;
}
static VkResult ensure_target(ps_gpu *g, uint32_t w, uint32_t h) {
    if (g->w == w && g->h == h) return VK_SUCCESS;
    drop_target(g);
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, FORMAT, {w, h, 1}, 1, 1,
        VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkResult r = vkCreateImage(g->device, &ii, NULL, &g->target);
    if (r) return r;
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(g->device, g->target, &req);
    if ((r = allocate(g, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &g->target_mem)) ||
        (r = vkBindImageMemory(g->device, g->target, g->target_mem, 0))) return r;
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, g->target, VK_IMAGE_VIEW_TYPE_2D, FORMAT,
        {0, 0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if ((r = vkCreateImageView(g->device, &vi, NULL, &g->view))) return r;
    VkFramebufferCreateInfo fi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, g->pass, 1, &g->view, w, h, 1};
    if ((r = vkCreateFramebuffer(g->device, &fi, NULL, &g->framebuffer))) return r;
    if ((r = host_buffer(g, (VkDeviceSize)w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                         &g->readback, &g->readback_mem, &g->readback_map))) return r;
    g->w = w; g->h = h;
    return VK_SUCCESS;
}
static VkResult ensure_vertices(ps_gpu *g, size_t count) {
    if (count <= g->vertex_capacity) return VK_SUCCESS;
    if (g->vertices) vkDestroyBuffer(g->device, g->vertices, NULL);
    if (g->vertices_mem) vkFreeMemory(g->device, g->vertices_mem, NULL);
    g->vertices = VK_NULL_HANDLE; g->vertices_mem = VK_NULL_HANDLE; g->vertex_capacity = 0;
    size_t cap = count < 1024 ? 1024 : count * 2;
    VkResult r = host_buffer(g, cap * sizeof(ps_rect), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 0,
                             &g->vertices, &g->vertices_mem, &g->vertices_map);
    if (!r) g->vertex_capacity = cap;
    return r;
}

static VkResult ensure_glyphs(ps_gpu *g, size_t count) {
    if (count <= g->glyph_capacity) return VK_SUCCESS;
    if (g->glyphs) vkDestroyBuffer(g->device, g->glyphs, NULL);
    if (g->glyphs_mem) vkFreeMemory(g->device, g->glyphs_mem, NULL);
    g->glyphs = VK_NULL_HANDLE; g->glyphs_mem = VK_NULL_HANDLE; g->glyph_capacity = 0;
    size_t cap = count < 1024 ? 1024 : count * 2;
    VkResult r = host_buffer(g, cap * sizeof(ps_glyph), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 0,
                             &g->glyphs, &g->glyphs_mem, &g->glyphs_map);
    if (!r) g->glyph_capacity = cap;
    return r;
}
static void drop_atlas(ps_gpu *g) {
    if (g->atlas_view) vkDestroyImageView(g->device, g->atlas_view, NULL);
    if (g->atlas) vkDestroyImage(g->device, g->atlas, NULL);
    if (g->atlas_mem) vkFreeMemory(g->device, g->atlas_mem, NULL);
    g->atlas_view = VK_NULL_HANDLE; g->atlas = VK_NULL_HANDLE; g->atlas_mem = VK_NULL_HANDLE;
    g->atlas_w = g->atlas_h = 0;
}
/* Fences are waited without a timeout: completion is guaranteed (a hung device reports
 * VK_ERROR_DEVICE_LOST), so no call returns while the GPU still uses its resources. */
static int fail(VkResult r) { return r < 0 ? (int)r : -(int)r; }

int ps_atlas(ps_gpu *g, const uint8_t *coverage, uint32_t w, uint32_t h) {
    if (!g || !coverage || !w || !h) return fail(VK_ERROR_INITIALIZATION_FAILED);
    vkDeviceWaitIdle(g->device);
    drop_atlas(g);
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8_UNORM, {w, h, 1}, 1, 1,
        VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkResult r = vkCreateImage(g->device, &ii, NULL, &g->atlas);
    VkMemoryRequirements req;
    if (!r) { vkGetImageMemoryRequirements(g->device, g->atlas, &req); r = allocate(g, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &g->atlas_mem); }
    if (!r) r = vkBindImageMemory(g->device, g->atlas, g->atlas_mem, 0);
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, g->atlas, VK_IMAGE_VIEW_TYPE_2D,
        VK_FORMAT_R8_UNORM, {0, 0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (!r) r = vkCreateImageView(g->device, &vi, NULL, &g->atlas_view);
    /* Upload through a staging buffer, then leave the atlas shader-readable. */
    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory staging_mem = VK_NULL_HANDLE; void *map = NULL;
    if (!r) r = host_buffer(g, (VkDeviceSize)w * h, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, 0, &staging, &staging_mem, &map);
    if (!r) {
        memcpy(map, coverage, (size_t)w * h);
        vkResetCommandBuffer(g->cmd, 0);
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
        vkBeginCommandBuffer(g->cmd, &bi);
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageMemoryBarrier to_dst = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, g->atlas, range};
        vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_dst);
        VkBufferImageCopy copy = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
        vkCmdCopyBufferToImage(g->cmd, staging, g->atlas, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        VkImageMemoryBarrier to_read = to_dst;
        to_read.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; to_read.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        to_read.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; to_read.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, NULL, 0, NULL, 1, &to_read);
        vkEndCommandBuffer(g->cmd);
        VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &g->cmd, 0, NULL};
        vkResetFences(g->device, 1, &g->fence);
        r = vkQueueSubmit(g->queue, 1, &si, g->fence);
        if (!r) r = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, UINT64_MAX);
    }
    if (staging) vkDestroyBuffer(g->device, staging, NULL);
    if (staging_mem) vkFreeMemory(g->device, staging_mem, NULL);
    if (!r) {
        VkDescriptorImageInfo image = {g->sampler, g->atlas_view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet write = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, g->set, 0, 0, 1,
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &image, NULL, NULL};
        vkUpdateDescriptorSets(g->device, 1, &write, 0, NULL);
        g->atlas_w = w; g->atlas_h = h;
    } else drop_atlas(g);
    return r ? fail(r) : 0;
}

/* The scene pass into a framebuffer of the shared render pass (ends in TRANSFER_SRC layout).
 * Vertex data must already be in the instance buffers. */
static void record(ps_gpu *g, VkCommandBuffer cmd, VkFramebuffer framebuffer, uint32_t w, uint32_t h, size_t count, size_t nglyphs,
                   VkBuffer vertices, VkBuffer glyphs) {
    VkClearValue clear = {{{0, 0, 0, 0}}};
    VkRenderPassBeginInfo rb = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, NULL, g->pass, framebuffer, {{0, 0}, {w, h}}, 1, &clear};
    vkCmdBeginRenderPass(cmd, &rb, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport = {0, 0, (float)w, (float)h, 0, 1};
    VkRect2D scissor = {{0, 0}, {w, h}};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    float size[2] = {(float)w, (float)h};
    VkDeviceSize offset = 0;
    if (count) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g->pipeline);
        vkCmdPushConstants(cmd, g->layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof size, size);
        vkCmdBindVertexBuffers(cmd, 0, 1, &vertices, &offset);
        vkCmdDraw(cmd, 6, (uint32_t)count, 0, 0);
    }
    if (nglyphs) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g->glyph_pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g->glyph_layout, 0, 1, &g->set, 0, NULL);
        vkCmdPushConstants(cmd, g->glyph_layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof size, size);
        vkCmdBindVertexBuffers(cmd, 0, 1, &glyphs, &offset);
        vkCmdDraw(cmd, 6, (uint32_t)nglyphs, 0, 0);
    }
    vkCmdEndRenderPass(cmd);
}

int ps_render(ps_gpu *g, const ps_rect *rects, size_t count, uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms) {
    return ps_render_text(g, rects, count, NULL, 0, w, h, rgba, gpu_ms);
}

int ps_render_text(ps_gpu *g, const ps_rect *rects, size_t count, const ps_glyph *glyphs, size_t nglyphs,
                   uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms) {
    if (!g || !w || !h || (count && !rects) || (nglyphs && (!glyphs || !g->atlas_w)) || !rgba)
        return fail(VK_ERROR_INITIALIZATION_FAILED);
    VkResult r;
    if ((r = ensure_target(g, w, h)) || (r = ensure_vertices(g, count ? count : 1)) ||
        (r = ensure_glyphs(g, nglyphs ? nglyphs : 1))) return fail(r);
    if (count) memcpy(g->vertices_map, rects, count * sizeof *rects);
    if (nglyphs) memcpy(g->glyphs_map, glyphs, nglyphs * sizeof *glyphs);
    vkResetCommandBuffer(g->cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(g->cmd, &bi);
    record(g, g->cmd, g->framebuffer, w, h, count, nglyphs, g->vertices, g->glyphs);
    VkBufferImageCopy copy = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
    vkCmdCopyImageToBuffer(g->cmd, g->target, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g->readback, 1, &copy);
    VkBufferMemoryBarrier host = {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_HOST_READ_BIT, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, g->readback, 0, VK_WHOLE_SIZE};
    vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &host, 0, NULL);
    vkEndCommandBuffer(g->cmd);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &g->cmd, 0, NULL};
    double t = now_ms();
    vkResetFences(g->device, 1, &g->fence);
    if ((r = vkQueueSubmit(g->queue, 1, &si, g->fence)) ||
        (r = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, UINT64_MAX))) return fail(r);
    if (gpu_ms) *gpu_ms = now_ms() - t;
    memcpy(rgba, g->readback_map, (size_t)w * h * 4);
    return 0;
}

static void drop_swapchain(ps_gpu *g) {
    for (uint32_t i = 0; i < g->images; i++) {
        if (g->image_fb[i]) vkDestroyFramebuffer(g->device, g->image_fb[i], NULL);
        if (g->image_view[i]) vkDestroyImageView(g->device, g->image_view[i], NULL);
        if (g->rendered[i]) vkDestroySemaphore(g->device, g->rendered[i], NULL);
        g->image_fb[i] = VK_NULL_HANDLE; g->image_view[i] = VK_NULL_HANDLE; g->rendered[i] = VK_NULL_HANDLE;
    }
    g->images = 0;
    if (g->swapchain) vkDestroySwapchainKHR(g->device, g->swapchain, NULL);
    g->swapchain = VK_NULL_HANDLE;
}

int ps_swapchain(ps_gpu *g, uint64_t handle, uint32_t w, uint32_t h) {
    if (!g || !g->presenting) return fail(VK_ERROR_INITIALIZATION_FAILED);
    VkSurfaceKHR surface = (VkSurfaceKHR)(uintptr_t)handle;
    vkDeviceWaitIdle(g->device);
    if (surface != g->surface) {
        drop_swapchain(g);
        if (g->surface) vkDestroySurfaceKHR(g->instance, g->surface, NULL);
        g->surface = surface;
    }
    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(g->gpu, g->family, g->surface, &supported);
    if (!supported) return fail(VK_ERROR_FEATURE_NOT_PRESENT);
    VkSurfaceCapabilitiesKHR caps;
    VkResult r = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g->gpu, g->surface, &caps);
    if (r) return fail(r);
    uint32_t nf = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g->gpu, g->surface, &nf, NULL);
    VkSurfaceFormatKHR formats[32];
    if (nf > 32) nf = 32;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g->gpu, g->surface, &nf, formats);
    int found = -1;
    for (uint32_t i = 0; i < nf; i++) if (formats[i].format == FORMAT) found = (int)i;
    /* The scene's render pass is RGBA8 UNORM; a surface without it is not supported yet. */
    if (found < 0) return fail(VK_ERROR_FORMAT_NOT_SUPPORTED);
    /* The caller's size (the platform's resize callback) wins within the surface's limits:
     * Android's currentExtent lags a resize, and its swapchain sizes the window's buffers from
     * imageExtent. Where min = max = current (X11), the clamp gives the current size. */
    if (!w || !h) return fail(VK_ERROR_OUT_OF_DATE_KHR);
    VkExtent2D extent = {
        w < caps.minImageExtent.width ? caps.minImageExtent.width : w > caps.maxImageExtent.width ? caps.maxImageExtent.width : w,
        h < caps.minImageExtent.height ? caps.minImageExtent.height : h > caps.maxImageExtent.height ? caps.maxImageExtent.height : h};
    if (!extent.width || !extent.height) return fail(VK_ERROR_OUT_OF_DATE_KHR);
    uint32_t want = caps.minImageCount + 1;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
    if (want > 8) want = 8;
    VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    VkCompositeAlphaFlagBitsKHR alpha = caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR ?
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR : VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    VkSwapchainKHR old = g->swapchain;
    VkSwapchainCreateInfoKHR si = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, NULL, 0, g->surface, want, FORMAT,
        formats[found].colorSpace, extent, 1, usage, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, caps.currentTransform, alpha,
        VK_PRESENT_MODE_FIFO_KHR, VK_TRUE, old};
    VkSwapchainKHR fresh;
    if ((r = vkCreateSwapchainKHR(g->device, &si, NULL, &fresh))) return fail(r);
    drop_swapchain(g);
    g->swapchain = fresh; g->extent = extent;
    uint32_t n = 8;
    if ((r = vkGetSwapchainImagesKHR(g->device, g->swapchain, &n, g->image)) < 0) return fail(r);
    g->images = n;
    VkSemaphoreCreateInfo semi = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, NULL, 0};
    if (!g->acquired && (r = vkCreateSemaphore(g->device, &semi, NULL, &g->acquired))) return fail(r);
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, g->image[i], VK_IMAGE_VIEW_TYPE_2D, FORMAT,
            {0, 0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        if ((r = vkCreateImageView(g->device, &vi, NULL, &g->image_view[i]))) return fail(r);
        VkFramebufferCreateInfo fi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, g->pass, 1, &g->image_view[i], extent.width, extent.height, 1};
        if ((r = vkCreateFramebuffer(g->device, &fi, NULL, &g->image_fb[i]))) return fail(r);
        if ((r = vkCreateSemaphore(g->device, &semi, NULL, &g->rendered[i]))) return fail(r);
    }
    VkDeviceSize need = (VkDeviceSize)extent.width * extent.height * 4;
    if (need != g->shot_size && (usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        if (g->shot) vkDestroyBuffer(g->device, g->shot, NULL);
        if (g->shot_mem) vkFreeMemory(g->device, g->shot_mem, NULL);
        g->shot = VK_NULL_HANDLE; g->shot_mem = VK_NULL_HANDLE; g->shot_size = 0;
        if ((r = host_buffer(g, need, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                             &g->shot, &g->shot_mem, &g->shot_map))) return fail(r);
        g->shot_size = need;
    }
    return 0;
}

void ps_extent(ps_gpu *g, uint32_t *w, uint32_t *h) {
    *w = g && g->swapchain ? g->extent.width : 0;
    *h = g && g->swapchain ? g->extent.height : 0;
}

int ps_present(ps_gpu *g, const ps_rect *rects, size_t count, const ps_glyph *glyphs, size_t nglyphs, uint8_t *rgba, double *cpu_ms) {
    if (!g || !g->swapchain || (count && !rects) || (nglyphs && (!glyphs || !g->atlas_w)) || (rgba && !g->shot))
        return fail(VK_ERROR_INITIALIZATION_FAILED);
    double t = now_ms();
    VkResult r;
    if ((r = ensure_vertices(g, count ? count : 1)) || (r = ensure_glyphs(g, nglyphs ? nglyphs : 1))) return fail(r);
    uint32_t index;
    r = vkAcquireNextImageKHR(g->device, g->swapchain, UINT64_MAX, g->acquired, VK_NULL_HANDLE, &index);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) return 1;
    if (r < 0) return fail(r);
    if (count) memcpy(g->vertices_map, rects, count * sizeof *rects);
    if (nglyphs) memcpy(g->glyphs_map, glyphs, nglyphs * sizeof *glyphs);
    uint32_t w = g->extent.width, h = g->extent.height;
    vkResetCommandBuffer(g->cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(g->cmd, &bi);
    record(g, g->cmd, g->image_fb[index], w, h, count, nglyphs, g->vertices, g->glyphs);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (rgba) {
        VkBufferImageCopy copy = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
        vkCmdCopyImageToBuffer(g->cmd, g->image[index], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, g->shot, 1, &copy);
        VkBufferMemoryBarrier host = {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_HOST_READ_BIT, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, g->shot, 0, VK_WHOLE_SIZE};
        vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &host, 0, NULL);
    }
    VkImageMemoryBarrier to_present = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT, 0,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
        g->image[index], range};
    vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &to_present);
    vkEndCommandBuffer(g->cmd);
    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 1, &g->acquired, &wait, 1, &g->cmd, 1, &g->rendered[index]};
    vkResetFences(g->device, 1, &g->fence);
    if ((r = vkQueueSubmit(g->queue, 1, &si, g->fence))) return fail(r);
    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 1, &g->rendered[index], 1, &g->swapchain, &index, NULL};
    VkResult pr = vkQueuePresentKHR(g->queue, &pi);
    /* One frame in flight: the command buffer, acquire semaphore and shot buffer are reused. */
    if ((r = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, UINT64_MAX))) return fail(r);
    if (rgba) memcpy(rgba, g->shot_map, (size_t)w * h * 4);
    if (cpu_ms) *cpu_ms = now_ms() - t;
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) return 1;
    return pr < 0 ? fail(pr) : 0;
}

static VkResult grow(ps_gpu *g, VkBuffer *b, VkDeviceMemory *m, void **map, size_t *cap, size_t need, VkBufferUsageFlags usage) {
    if (need <= *cap) return VK_SUCCESS;
    if (*b) vkDestroyBuffer(g->device, *b, NULL);
    if (*m) vkFreeMemory(g->device, *m, NULL);
    *b = VK_NULL_HANDLE; *m = VK_NULL_HANDLE; *cap = 0;
    size_t size = need < 65536 ? 65536 : need * 2;
    VkResult r = host_buffer(g, size, usage, 0, b, m, map);
    if (!r) *cap = size;
    return r;
}
static void drop_frames(ps_gpu *g) {
    for (int i = 0; i < PS_FRAMES; i++) {
        struct ps_frame *f = &g->frame[i];
        if (f->fb) vkDestroyFramebuffer(g->device, f->fb, NULL);
        if (f->view) vkDestroyImageView(g->device, f->view, NULL);
        if (f->target) vkDestroyImage(g->device, f->target, NULL);
        if (f->target_mem) vkFreeMemory(g->device, f->target_mem, NULL);
        VkBuffer bufs[] = {f->readback, f->rects, f->glyphs, f->staging};
        VkDeviceMemory mems[] = {f->readback_mem, f->rects_mem, f->glyphs_mem, f->staging_mem};
        for (int k = 0; k < 4; k++) { if (bufs[k]) vkDestroyBuffer(g->device, bufs[k], NULL); if (mems[k]) vkFreeMemory(g->device, mems[k], NULL); }
        if (f->fence) vkDestroyFence(g->device, f->fence, NULL);
        if (f->acquired) vkDestroySemaphore(g->device, f->acquired, NULL);
        if (f->cmd) vkFreeCommandBuffers(g->device, g->pool, 1, &f->cmd);
        memset(f, 0, sizeof *f);
    }
    g->frames = 0;
}

int ps_frames_init(ps_gpu *g, uint32_t w, uint32_t h, uint32_t aw, uint32_t ah) {
    if (!g || !w || !h || !aw || !ah) return fail(VK_ERROR_INITIALIZATION_FAILED);
    vkDeviceWaitIdle(g->device);
    drop_frames(g); drop_atlas(g);
    VkResult r;
    VkImageCreateInfo ai = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8_UNORM, {aw, ah, 1}, 1, 1,
        VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
    VkMemoryRequirements req;
    if ((r = vkCreateImage(g->device, &ai, NULL, &g->atlas))) return fail(r);
    vkGetImageMemoryRequirements(g->device, g->atlas, &req);
    if ((r = allocate(g, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &g->atlas_mem)) ||
        (r = vkBindImageMemory(g->device, g->atlas, g->atlas_mem, 0))) return fail(r);
    VkImageViewCreateInfo avi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, g->atlas, VK_IMAGE_VIEW_TYPE_2D,
        VK_FORMAT_R8_UNORM, {0, 0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if ((r = vkCreateImageView(g->device, &avi, NULL, &g->atlas_view))) return fail(r);
    /* Clear once and leave the atlas in GENERAL for its whole life. */
    vkResetCommandBuffer(g->cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(g->cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier to_general = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, g->atlas, range};
    vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_general);
    VkClearColorValue zero = {{0, 0, 0, 0}};
    vkCmdClearColorImage(g->cmd, g->atlas, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
    VkImageMemoryBarrier readable = to_general;
    readable.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; readable.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    readable.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    vkCmdPipelineBarrier(g->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         0, 0, NULL, 0, NULL, 1, &readable);
    vkEndCommandBuffer(g->cmd);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &g->cmd, 0, NULL};
    vkResetFences(g->device, 1, &g->fence);
    if ((r = vkQueueSubmit(g->queue, 1, &si, g->fence)) || (r = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, UINT64_MAX))) return fail(r);
    VkDescriptorImageInfo image = {g->sampler, g->atlas_view, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet write = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, NULL, g->set, 0, 0, 1,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &image, NULL, NULL};
    vkUpdateDescriptorSets(g->device, 1, &write, 0, NULL);
    g->atlas_w = aw; g->atlas_h = ah;
    for (int i = 0; i < PS_FRAMES; i++) {
        struct ps_frame *f = &g->frame[i];
        VkCommandBufferAllocateInfo ca = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, g->pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT};
        VkImageCreateInfo ti = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, 0, VK_IMAGE_TYPE_2D, FORMAT, {w, h, 1}, 1, 1,
            VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED};
        VkSemaphoreCreateInfo semi = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, NULL, 0};
        if ((r = vkAllocateCommandBuffers(g->device, &ca, &f->cmd)) || (r = vkCreateFence(g->device, &fi, NULL, &f->fence)) ||
            (r = vkCreateSemaphore(g->device, &semi, NULL, &f->acquired)) ||
            (r = vkCreateImage(g->device, &ti, NULL, &f->target))) return fail(r);
        vkGetImageMemoryRequirements(g->device, f->target, &req);
        if ((r = allocate(g, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &f->target_mem)) ||
            (r = vkBindImageMemory(g->device, f->target, f->target_mem, 0))) return fail(r);
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, NULL, 0, f->target, VK_IMAGE_VIEW_TYPE_2D, FORMAT,
            {0, 0, 0, 0}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        if ((r = vkCreateImageView(g->device, &vi, NULL, &f->view))) return fail(r);
        VkFramebufferCreateInfo fbi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, g->pass, 1, &f->view, w, h, 1};
        if ((r = vkCreateFramebuffer(g->device, &fbi, NULL, &f->fb)) ||
            (r = host_buffer(g, (VkDeviceSize)w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                             &f->readback, &f->readback_mem, &f->readback_map))) return fail(r);
    }
    g->frames = 1; g->fw = w; g->fh = h; g->submitted = g->retired = 0;
    return 0;
}

static void settle_slot(ps_gpu *g, struct ps_frame *f) {
    if (f->pending && vkGetFenceStatus(g->device, f->fence) == VK_SUCCESS) f->pending = 0;
}
uint64_t ps_frames_retired(ps_gpu *g) {
    /* In submission order: a frame counts only when every earlier one has also signalled. */
    while (g->retired < g->submitted) {
        struct ps_frame *f = &g->frame[g->retired % PS_FRAMES];
        settle_slot(g, f);
        if (f->pending || f->number != g->retired + 1) break;
        g->retired++;
    }
    return g->retired;
}
int ps_frames_wait(ps_gpu *g, uint64_t number) {
    if (!g || number > g->submitted) return fail(VK_ERROR_INITIALIZATION_FAILED);
    while (ps_frames_retired(g) < number) {
        struct ps_frame *f = &g->frame[g->retired % PS_FRAMES];
        VkResult r = vkWaitForFences(g->device, 1, &f->fence, VK_TRUE, UINT64_MAX);
        if (r) return fail(r);
    }
    return 0;
}
int ps_frames_pending(ps_gpu *g) { ps_frames_retired(g); return (int)(g->submitted - g->retired); }

/* Takes the next ring slot (waiting only if its frame is still pending), fills its buffers and
 * begins its command buffer with the frame's uploads. */
static VkResult frame_begin(ps_gpu *g, struct ps_frame *f, const ps_upload *up, size_t nup, const uint8_t *pixels, size_t pixel_bytes,
                            const ps_rect *rects, size_t nr, const ps_glyph *glyphs, size_t ng) {
    VkResult r;
    if (f->pending) {                                 /* the ring is full: wait for the oldest */
        if ((r = vkWaitForFences(g->device, 1, &f->fence, VK_TRUE, UINT64_MAX))) return r;
        f->pending = 0;
        ps_frames_retired(g);
    }
    if ((r = grow(g, &f->staging, &f->staging_mem, &f->staging_map, &f->staging_cap, pixel_bytes ? pixel_bytes : 1, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) ||
        (r = grow(g, &f->rects, &f->rects_mem, &f->rects_map, &f->rects_cap, (nr ? nr : 1) * sizeof *rects, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)) ||
        (r = grow(g, &f->glyphs, &f->glyphs_mem, &f->glyphs_map, &f->glyphs_cap, (ng ? ng : 1) * sizeof *glyphs, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)))
        return r;
    if (pixel_bytes) memcpy(f->staging_map, pixels, pixel_bytes);
    if (nr) memcpy(f->rects_map, rects, nr * sizeof *rects);
    if (ng) memcpy(f->glyphs_map, glyphs, ng * sizeof *glyphs);
    vkResetCommandBuffer(f->cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(f->cmd, &bi);
    if (nup) {
        VkBufferImageCopy copies[64];
        for (size_t done = 0; done < nup;) {
            size_t n = nup - done < 64 ? nup - done : 64;
            for (size_t k = 0; k < n; k++) {
                const ps_upload *u = &up[done + k];
                copies[k] = (VkBufferImageCopy){u->offset, u->w, u->h, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                                                {u->x, u->y, 0}, {u->w, u->h, 1}};
            }
            vkCmdCopyBufferToImage(f->cmd, f->staging, g->atlas, VK_IMAGE_LAYOUT_GENERAL, (uint32_t)n, copies);
            done += n;
        }
        /* This frame's uploads before its own sampling. Earlier frames sample other regions. */
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageMemoryBarrier visible = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED,
            VK_QUEUE_FAMILY_IGNORED, g->atlas, range};
        vkCmdPipelineBarrier(f->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, NULL, 0, NULL, 1, &visible);
    }
    return VK_SUCCESS;
}
static void readback(struct ps_frame *f, VkImage image, uint32_t w, uint32_t h) {
    VkBufferImageCopy copy = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {w, h, 1}};
    vkCmdCopyImageToBuffer(f->cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, f->readback, 1, &copy);
    VkBufferMemoryBarrier host = {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_HOST_READ_BIT, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, f->readback, 0, VK_WHOLE_SIZE};
    vkCmdPipelineBarrier(f->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &host, 0, NULL);
}

int ps_frame_submit(ps_gpu *g, const ps_upload *up, size_t nup, const uint8_t *pixels, size_t pixel_bytes,
                    const ps_rect *rects, size_t nr, const ps_glyph *glyphs, size_t ng, uint64_t *number) {
    if (!g || !g->frames || (nup && (!up || !pixels)) || (nr && !rects) || (ng && !glyphs)) return fail(VK_ERROR_INITIALIZATION_FAILED);
    struct ps_frame *f = &g->frame[g->submitted % PS_FRAMES];
    VkResult r = frame_begin(g, f, up, nup, pixels, pixel_bytes, rects, nr, glyphs, ng);
    if (r) return fail(r);
    record(g, f->cmd, f->fb, g->fw, g->fh, nr, ng, f->rects, f->glyphs);
    readback(f, f->target, g->fw, g->fh);
    vkEndCommandBuffer(f->cmd);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &f->cmd, 0, NULL};
    vkResetFences(g->device, 1, &f->fence);
    if ((r = vkQueueSubmit(g->queue, 1, &si, f->fence))) return fail(r);
    f->pending = 1; f->number = ++g->submitted;
    if (number) *number = f->number;
    return 0;
}

int ps_frame_present(ps_gpu *g, const ps_upload *up, size_t nup, const uint8_t *pixels, size_t pixel_bytes,
                     const ps_glyph *glyphs, size_t ng, int shot, uint64_t *number) {
    if (!g || !g->frames || !g->swapchain || (nup && (!up || !pixels)) || (ng && !glyphs)) return fail(VK_ERROR_INITIALIZATION_FAILED);
    uint32_t w = g->extent.width, h = g->extent.height;
    if (shot && (w != g->fw || h != g->fh)) return fail(VK_ERROR_INITIALIZATION_FAILED);   /* the ring's readback size */
    struct ps_frame *f = &g->frame[g->submitted % PS_FRAMES];
    VkResult r = frame_begin(g, f, up, nup, pixels, pixel_bytes, NULL, 0, glyphs, ng);
    if (r) return fail(r);
    uint32_t index;
    r = vkAcquireNextImageKHR(g->device, g->swapchain, UINT64_MAX, f->acquired, VK_NULL_HANDLE, &index);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r < 0) {
        /* Not drawn, but the uploads residency already counts must still happen: submit them. */
        vkEndCommandBuffer(f->cmd);
        VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &f->cmd, 0, NULL};
        vkResetFences(g->device, 1, &f->fence);
        VkResult s = vkQueueSubmit(g->queue, 1, &si, f->fence);
        if (s) return fail(s);
        f->pending = 1; f->number = ++g->submitted;
        if (number) *number = f->number;
        return r == VK_ERROR_OUT_OF_DATE_KHR ? 1 : fail(r);
    }
    record(g, f->cmd, g->image_fb[index], w, h, 0, ng, f->rects, f->glyphs);
    if (shot) readback(f, g->image[index], w, h);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier to_present = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT, 0,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
        g->image[index], range};
    vkCmdPipelineBarrier(f->cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &to_present);
    vkEndCommandBuffer(f->cmd);
    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 1, &f->acquired, &wait, 1, &f->cmd, 1, &g->rendered[index]};
    vkResetFences(g->device, 1, &f->fence);
    if ((r = vkQueueSubmit(g->queue, 1, &si, f->fence))) return fail(r);
    f->pending = 1; f->number = ++g->submitted;
    if (number) *number = f->number;
    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 1, &g->rendered[index], 1, &g->swapchain, &index, NULL};
    VkResult pr = vkQueuePresentKHR(g->queue, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) return 1;
    return pr < 0 ? fail(pr) : 0;
}

int ps_frame_pixels(ps_gpu *g, uint64_t number, uint8_t *rgba) {
    if (!g || !rgba || !number || number > ps_frames_retired(g)) return fail(VK_NOT_READY);
    struct ps_frame *f = &g->frame[(number - 1) % PS_FRAMES];
    if (f->number != number || f->pending) return fail(VK_INCOMPLETE);   /* slot reused since */
    memcpy(rgba, f->readback_map, (size_t)g->fw * g->fh * 4);
    return 0;
}

void ps_close(ps_gpu *g) {
    if (!g) return;
    if (g->device) {
        vkDeviceWaitIdle(g->device);
        drop_frames(g);
        drop_target(g);
        drop_swapchain(g);
        if (g->acquired) vkDestroySemaphore(g->device, g->acquired, NULL);
        if (g->shot) vkDestroyBuffer(g->device, g->shot, NULL);
        if (g->shot_mem) vkFreeMemory(g->device, g->shot_mem, NULL);
        if (g->vertices) vkDestroyBuffer(g->device, g->vertices, NULL);
        if (g->vertices_mem) vkFreeMemory(g->device, g->vertices_mem, NULL);
        if (g->glyphs) vkDestroyBuffer(g->device, g->glyphs, NULL);
        if (g->glyphs_mem) vkFreeMemory(g->device, g->glyphs_mem, NULL);
        drop_atlas(g);
        if (g->sampler) vkDestroySampler(g->device, g->sampler, NULL);
        if (g->descriptors) vkDestroyDescriptorPool(g->device, g->descriptors, NULL);
        if (g->glyph_pipeline) vkDestroyPipeline(g->device, g->glyph_pipeline, NULL);
        if (g->glyph_layout) vkDestroyPipelineLayout(g->device, g->glyph_layout, NULL);
        if (g->set_layout) vkDestroyDescriptorSetLayout(g->device, g->set_layout, NULL);
        if (g->pipeline) vkDestroyPipeline(g->device, g->pipeline, NULL);
        if (g->layout) vkDestroyPipelineLayout(g->device, g->layout, NULL);
        if (g->pass) vkDestroyRenderPass(g->device, g->pass, NULL);
        if (g->fence) vkDestroyFence(g->device, g->fence, NULL);
        if (g->pool) vkDestroyCommandPool(g->device, g->pool, NULL);
        vkDestroyDevice(g->device, NULL);
    }
    if (g->surface) vkDestroySurfaceKHR(g->instance, g->surface, NULL);
    if (g->instance) vkDestroyInstance(g->instance, NULL);
    free(g);
}
