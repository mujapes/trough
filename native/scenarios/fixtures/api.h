#ifndef TEST_MODULE_API_H
#define TEST_MODULE_API_H
#include <stdint.h>
typedef struct {
    unsigned abi, schema;
    void *(*restore)(uint64_t snapshot);
    uint64_t (*snapshot)(const void *state);
    uint64_t (*evaluate)(const void *state, uint64_t input);
    void (*destroy)(void *state);
} test_api;
const test_api *test_module(void);
#endif
