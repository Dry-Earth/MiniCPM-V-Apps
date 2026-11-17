// Compatibility shims for the upstream Snapdragon backend drop-ins.
//
// The Adreno OpenCL / Hexagon backends published by ggml-org (b12470) are
// prebuilt against a slightly newer ggml than this fork. An ABI diff of the
// binaries shows exactly two missing symbols, both of them trivial accessors
// added upstream:
//
//     ggml_prec_get_src(const struct ggml_tensor *, int)
//     ggml_rope_get_truncate(const struct ggml_tensor *)
//
// Both read op_params, whose layout is identical here (int32_t[]), so the
// upstream bodies can be reproduced verbatim. Defining them in libggml-base.so
// is what makes the vendored .so loadable; without them dlopen fails and the
// backend silently falls back to the CPU.
//
// Compiled only when MINICPMV_SNAPDRAGON_BACKENDS is set, so a stock build is
// unaffected.

#include "ggml.h"

#include <string.h>

#ifdef MINICPMV_SNAPDRAGON_BACKENDS

// ggml builds with hidden visibility and exports through GGML_API; these need
// the same treatment or they stay invisible to dlopen().
#if defined(__GNUC__)
#define MINICPMV_COMPAT_API __attribute__((visibility("default")))
#else
#define MINICPMV_COMPAT_API
#endif

#ifndef GGML_PREC_UNDEFINED
#define GGML_PREC_UNDEFINED (-1)
#endif

MINICPMV_COMPAT_API enum ggml_prec ggml_prec_get_src(
        const struct ggml_tensor * a,
        int                        idx) {
    if (a == NULL || idx < 0 || idx >= GGML_MAX_SRC) {
        return (enum ggml_prec) GGML_PREC_UNDEFINED;
    }

    switch (a->op) {
        case GGML_OP_MUL_MAT:
        case GGML_OP_MUL_MAT_ID:
            if (idx != 1) {
                return (enum ggml_prec) GGML_PREC_UNDEFINED;
            }
            return (enum ggml_prec) a->op_params[2 + idx];
        default:
            return (enum ggml_prec) GGML_PREC_UNDEFINED;
    }
}

MINICPMV_COMPAT_API bool ggml_rope_get_truncate(const struct ggml_tensor * a) {
    if (a == NULL) {
        return false;
    }
    return a->op_params[3] == 0;
}

#endif  // MINICPMV_SNAPDRAGON_BACKENDS
