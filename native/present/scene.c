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
    VkPipelineLayout layout;
    VkPipeline pipeline;
    /* Sized on demand. */
    uint32_t w, h;
    VkImage target; VkDeviceMemory target_mem; VkImageView view; VkFramebuffer framebuffer;
    VkBuffer readback; VkDeviceMemory readback_mem; void *readback_map;
    VkBuffer vertices; VkDeviceMemory vertices_mem; void *vertices_map; size_t vertex_capacity;
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

static VkResult pipeline(ps_gpu *g) {
    VkAttachmentDescription color = {0, FORMAT, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR,
        VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE,
        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
    VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, NULL, 1, &ref, NULL, NULL, 0, NULL};
    VkSubpassDependency after = {0, VK_SUBPASS_EXTERNAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, 0};
    VkRenderPassCreateInfo rp = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, NULL, 0, 1, &color, 1, &sub, 1, &after};
    VkResult r = vkCreateRenderPass(g->device, &rp, NULL, &g->pass);
    if (r) return r;
    VkPushConstantRange push = {VK_SHADER_STAGE_VERTEX_BIT, 0, 8};
    VkPipelineLayoutCreateInfo li = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 0, NULL, 1, &push};
    if ((r = vkCreatePipelineLayout(g->device, &li, NULL, &g->layout))) return r;
    VkShaderModule vs = module(g, rect_vert, sizeof rect_vert), fs = module(g, rect_frag, sizeof rect_frag);
    if (!vs || !fs) { r = VK_ERROR_INITIALIZATION_FAILED; goto out; }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, vs, "main", NULL},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", NULL}};
    VkVertexInputBindingDescription bind = {0, sizeof(ps_rect), VK_VERTEX_INPUT_RATE_INSTANCE};
    VkVertexInputAttributeDescription attrs[3] = {
        {0, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 0}, {1, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 16},
        {2, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 32}};
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO, NULL, 0, 1, &bind, 3, attrs};
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
        &vp, &rs, &ms, NULL, &cb, &ds, g->layout, g->pass, 0, VK_NULL_HANDLE, -1};
    r = vkCreateGraphicsPipelines(g->device, VK_NULL_HANDLE, 1, &pi, NULL, &g->pipeline);
out:
    if (vs) vkDestroyShaderModule(g->device, vs, NULL);
    if (fs) vkDestroyShaderModule(g->device, fs, NULL);
    return r;
}

int ps_open(ps_gpu **out, char *info, size_t capacity) {
    *out = NULL;
    ps_gpu *g = calloc(1, sizeof *g);
    if (!g) return VK_ERROR_OUT_OF_HOST_MEMORY;
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "trough", 1, "trough", 1, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0, &app, 0, NULL, 0, NULL};
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
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, NULL, 0, 1, &dq, 0, NULL, 0, NULL, NULL};
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

int ps_render(ps_gpu *g, const ps_rect *rects, size_t count, uint32_t w, uint32_t h, uint8_t *rgba, double *gpu_ms) {
    if (!g || !w || !h || (count && !rects) || !rgba) return -(int)-VK_ERROR_INITIALIZATION_FAILED;
    VkResult r;
    if ((r = ensure_target(g, w, h)) || (r = ensure_vertices(g, count ? count : 1))) return -(int)(r < 0 ? -r : r);
    if (count) memcpy(g->vertices_map, rects, count * sizeof *rects);
    vkResetCommandBuffer(g->cmd, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    vkBeginCommandBuffer(g->cmd, &bi);
    VkClearValue clear = {{{0, 0, 0, 0}}};
    VkRenderPassBeginInfo rb = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, NULL, g->pass, g->framebuffer, {{0, 0}, {w, h}}, 1, &clear};
    vkCmdBeginRenderPass(g->cmd, &rb, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport viewport = {0, 0, (float)w, (float)h, 0, 1};
    VkRect2D scissor = {{0, 0}, {w, h}};
    vkCmdSetViewport(g->cmd, 0, 1, &viewport);
    vkCmdSetScissor(g->cmd, 0, 1, &scissor);
    vkCmdBindPipeline(g->cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g->pipeline);
    float size[2] = {(float)w, (float)h};
    vkCmdPushConstants(g->cmd, g->layout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof size, size);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(g->cmd, 0, 1, &g->vertices, &offset);
    if (count) vkCmdDraw(g->cmd, 6, (uint32_t)count, 0, 0);
    vkCmdEndRenderPass(g->cmd);
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
        (r = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 10000000000ull))) return -(int)(r < 0 ? -r : r);
    if (gpu_ms) *gpu_ms = now_ms() - t;
    memcpy(rgba, g->readback_map, (size_t)w * h * 4);
    return 0;
}

void ps_close(ps_gpu *g) {
    if (!g) return;
    if (g->device) {
        vkDeviceWaitIdle(g->device);
        drop_target(g);
        if (g->vertices) vkDestroyBuffer(g->device, g->vertices, NULL);
        if (g->vertices_mem) vkFreeMemory(g->device, g->vertices_mem, NULL);
        if (g->pipeline) vkDestroyPipeline(g->device, g->pipeline, NULL);
        if (g->layout) vkDestroyPipelineLayout(g->device, g->layout, NULL);
        if (g->pass) vkDestroyRenderPass(g->device, g->pass, NULL);
        if (g->fence) vkDestroyFence(g->device, g->fence, NULL);
        if (g->pool) vkDestroyCommandPool(g->device, g->pool, NULL);
        vkDestroyDevice(g->device, NULL);
    }
    if (g->instance) vkDestroyInstance(g->instance, NULL);
    free(g);
}
