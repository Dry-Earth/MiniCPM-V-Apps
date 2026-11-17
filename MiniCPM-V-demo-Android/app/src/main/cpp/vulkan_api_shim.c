// Vulkan 1.1 entry point forwarder for Android.
//
// The NDK ships a single libvulkan.so stub, built against the API 24 export
// table. That table predates Vulkan 1.1, so neither vkGetPhysicalDeviceFeatures2
// nor its KHR alias can be resolved at link time — even though every device this
// app runs on exposes both through the on-device loader.
//
// ggml-vulkan calls the entry point directly (the C++ HPP paths go through the
// dynamic dispatcher and are unaffected), which makes it a link-time dependency.
// This translation unit satisfies that dependency and forwards the call to
// whichever name the loader actually exports, resolved once on first use.
//
// Cost: one cached dlsym per process. Behaviour is identical to a direct call.

#include <dlfcn.h>

#ifdef MINICPMV_VULKAN_SHIM

typedef void (*vk_features2_fn)(void * physical_device, void * features2);

static vk_features2_fn resolve_features2(void) {
    static vk_features2_fn fn = 0;
    static int resolved = 0;
    if (!resolved) {
        resolved = 1;
        fn = (vk_features2_fn) dlsym(RTLD_DEFAULT, "vkGetPhysicalDeviceFeatures2");
        if (!fn) {
            fn = (vk_features2_fn) dlsym(RTLD_DEFAULT, "vkGetPhysicalDeviceFeatures2KHR");
        }
    }
    return fn;
}

// ggml-vulkan is patched to call the KHR spelling so that both this build and a
// hypothetical stub that does export the alias keep working.
void vkGetPhysicalDeviceFeatures2KHR(void * physical_device, void * features2) {
    vk_features2_fn fn = resolve_features2();
    if (fn) {
        fn(physical_device, features2);
    }
}

#endif  // MINICPMV_VULKAN_SHIM
