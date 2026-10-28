#!/bin/sh
# Rectangle renderer pixel oracle on the VM's Vulkan (Mesa lavapipe: apt install
# mesa-vulkan-drivers libvulkan-dev). The phone runs the same oracle via android.scene.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror ${CORE_CFLAGS:-} -I"$ROOT/native/present" \
    "$ROOT/native/present/scene.c" "$ROOT/native/present/oracle.c" "$ROOT/native/scenarios/scene.c" \
    -lvulkan -lm -o "$W/oracle"
VK_ICD_FILENAMES=${VK_ICD_FILENAMES:-/usr/share/vulkan/icd.d/lvp_icd.json} "$W/oracle"
