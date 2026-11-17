# 构建说明（本 fork 特有）

这份文档只描述**本 fork 相对上游增加的东西**和**在 GitHub Actions 上出包的全过程**。
上游三端（iOS / Android / HarmonyOS）的原始说明见 `README.md`、`README_zh.md`、`AGENTS.md`。

分支：`feat/android-v47-full-settings`

---

## 1. 这个 fork 加了什么

| 功能 | 位置 | 与上游的差别 |
|---|---|---|
| MiniCPM-V **4.7 1B** / **4.7 35B-A3B** | `ModelInfo.kt` | 上游 Android 只有 4.0 / 4.6 / MiniCPM5 / VoxCPM2 / OCR |
| `moe_slot_bank` 贯通 | `llama_jni.cpp` → `llama_model_params` | 上游 iOS 有，Android 没有；`les.moe` 模型槽位为 0 会被拒绝加载 |
| 推理参数设置页 | `InferenceSettings*.kt` | 上下文 / 输出上限 / 温度 / Top-K / Top-P / n_ubatch / 切片 / MoE 槽位 / 思考 / 系统提示词 |
| 本地 GGUF 导入 | `CustomModelStore.kt` | SAF 两步选择（主模型 + 可选 mmproj），流式复制，失败自动清理 |
| 模型家族识别 | `GgufProbe.kt` | 真解析 GGUF 元数据（`clip.projector_type` 优先），不再靠文件名猜 |
| 按设备推荐参数 | `DeviceProfile.kt` | 按总内存分档给出一组保守默认值 |
| 视觉塔独立后端 | `llama_jni.cpp` (`use_gpu`) | 上游"有 GPU 就用"，本 fork 默认固定 CPU，可单独开关 |
| QNN 构建自动降级 | `CMakeLists.txt` + `qnn_vit_stub.cpp` | 缺 QAIRT 头文件时编 stub，干净 clone 也能过 |
| SoC → Hexagon 表修正 | `QnnVit.kt` | 上游把 SM8750 映射到 v85（错的），这里改为 v79 |
| CI 出包 | `.github/workflows/android.yml` | 上游 Android 只有本机构建 |
| QNN 上下文二进制构建 | `.github/workflows/qnn-v79.yml` | 上游只发了 v85 的图 |

---

## 2. 后端能力矩阵（重要，别误会）

| 组合 | 状态 | 说明 |
|---|---|---|
| CPU 文本 + CPU 视觉 | ✅ 稳定 | 默认；`libggml-cpu-v86.so`（i8mm+bf16）自动加载 |
| GPU(OpenCL) 文本 | ⚠️ 可构建 | 需要 `enable_opencl`；上游 CI 真机验证过 |
| GPU(OpenCL) 视觉 | ⚠️ 实验 | 默认关闭（`visionUseGpu=false`），开启后曾出现卡死/闪退 |
| GPU(Vulkan) 文本 | ⚠️ 实验 | 能出包，但 coopmat 路径被禁用；不推荐 |
| NPU(Hexagon) 文本 | ⚠️ 可构建 | 需要 `enable_hexagon`；只覆盖 LLM，视觉仍走 CPU |
| NPU OCR 视觉塔 | ⚠️ 需生成图 | 需要 `qnn-v79` workflow 产出的 `.bin` + adb 侧载 |

**没有**"后端自由搭配/自动调度"这层：两个 GPU 类后端（OpenCL / Hexagon）都注册为
`GGML_BACKEND_DEVICE_TYPE_GPU`，同时存在时 llama.cpp 只会静默挑一个。所以 CI **一次只带一个**。

---

## 3. 在 Actions 上出包

`Actions → android → Run workflow`，参数：

| 输入 | 作用 |
|---|---|
| `enable_opencl` | 从上游 release 取 `libggml-opencl.so` 打进 APK（Adreno GPU） |
| `enable_hexagon` | 取 `libggml-hexagon.so` + `libggml-htp-v79.so`（Hexagon NPU） |
| `enable_vulkan` | 用 `-DGGML_VULKAN=ON` 从源码编 Vulkan 后端（实验） |
| `enable_qnn` | 拉 QAIRT 并把 QNN 运行时 + v79 skel 打进 APK（OCR NPU 用） |
| `release_tag` | 填了就把 APK 直接发布到该 Release（否则只留 artifact） |

`enable_opencl` 与 `enable_hexagon` **不要同时开**（见上一节）。
`push` 到 `main` 或 `feat/**` 会自动跑一次纯 CPU 构建。

### 构建步骤与原因

1. **checkout（含子模块）** —— `llama.cpp-omni` 不在仓库里，CMake 找不到会直接 `FATAL_ERROR`
2. **Free disk space** —— runner 约 14 GB 可用，NDK+SDK+全量 llama.cpp 编不下
3. **JDK 21 + SDK 包** —— `build.gradle.kts` 钉死 NDK `27.0.12077973`、CMake `4.1.2`；
   `buildGgmlCpu_v86` 任务还**硬编码**了 `$ANDROID_HOME/cmake/4.1.2/bin/cmake`，装别的版本没用
4. **Vulkan 工具链**（仅 `enable_vulkan`）—— `glslc` + `spirv-headers` + `libvulkan-dev`；
   头文件要复制进 NDK sysroot（`vulkan/`、`vk_video/`、`spirv/`），因为交叉编译时
   clang 不搜宿主 `/usr/include`，而且 `FindVulkan` 不会把 include 挂到 `Vulkan::Vulkan` 上
5. **拷后端 .so**（仅 opencl/hexagon）—— 从 `llama-<tag>-bin-android-arm64-snapdragon.tar.gz`
   取库放进 `app/src/main/jniLibs/arm64-v8a/`；**不要**校验 `libggml-htp-*.so` 是 ARM
   （它是 Hexagon DSP 的 ELF）
6. **签名** —— 没配 `KEYSTORE_B64` secret 时现场生成临时 keystore，见第 5 节
7. **Gradle 构建** —— `-Pminicpmv.snapdragon=true` 会让 CMake 把 `ggml_snapdragon_compat.c`
   编进 `libggml-base.so`（上游后端需要两个本 fork 还没有的 ggml 访问器，缺了 dlopen 会失败）
8. **校验并改名** —— `apksigner verify` + `aapt dump badging`，按仓库约定命名
9. **发布（可选）** —— `contents: write` 权限已声明，否则上传 release asset 会 403

### 产物

```
MiniCPM-V-Demo-Android-v2.7.apk            ~39 MB   纯 CPU
MiniCPM-V-Demo-Android-v2.7-opencl.apk     ~43 MB   + libggml-opencl.so
MiniCPM-V-Demo-Android-v2.7-hexagon.apk    ~41 MB   + libggml-hexagon.so + libggml-htp-v79.so
MiniCPM-V-Demo-Android-v2.7-vulkan.apk     ~94 MB   Vulkan（libggml-vulkan.so 57 MB 是 shader）
```

16 KB 页对齐已验证（Android 15+ / 17 的硬要求）：所有 `.so` 在 zip 内未压缩、数据偏移
16 KB 对齐、ELF `PT_LOAD` 的 `p_align = 0x4000`。

---

## 4. 安装与验证

```bash
# 签名每次可能不同，先卸载旧版
adb uninstall com.tianchi.minicpmv
adb install MiniCPM-V-Demo-Android-v2.7-opencl.apk

# 设置页 → GPU 层数 = 99 → 返回（会自动重载模型）

adb logcat -c && adb logcat | grep -E "device\[|offloaded|ggml_opencl|ggml-hex"
```

| 日志 | 含义 |
|---|---|
| `device[1] name=Adreno (TM) 830 type=2` | OpenCL 后端注册成功 |
| `device[1] name=HTP0 type=2` | Hexagon 后端注册成功 |
| 只有 `device[0] name=CPU` | 后端没加载（ABI 不匹配）→ 静默回退，不崩 |
| `offloaded N/M layers to GPU` | 真的把层放上去了 |

应用内也可以在**设置页 → 导出诊断日志**取到同一份日志（不需要 adb）。

---

## 5. 签名

CI 默认现场生成临时 keystore（`minicpmv` / `minicpmv`），**每次构建签名都不同**，
所以升级安装前必须卸载。

想固定签名：本地生成一次，把 base64 存进仓库 secret `KEYSTORE_B64`：

```bash
keytool -genkeypair -v -keystore release.jks -alias minicpmv \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <PW> -keypass <PW> \
  -dname "CN=..., O=..., C=CN"
base64 -w0 release.jks    # 粘进 secret
```

密码目前在 workflow 里写死为 `minicpmv`（配合 `-PMINICPMV_KEYSTORE_PASSWORD` 传入）。

---

## 6. 模型相关

### 内置条目

| 模型 | 文件 | 大小 | 备注 |
|---|---|---|---|
| V4.7 1B | `MiniCPM-V-4_7-1B-Q4_K_M.gguf` | 1.8 GB | HF + ModelScope 双源 |
| V4.7 35B-A3B | `LCO-MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf` | 16.6 GB | **仅 HF**；`les.moe` 格式，槽位自动 = 256 |
| V4.6 | `MiniCPM-V-4_6-Q4_K_M.gguf` | 1.6 GB | 有 **MD5 钉子**，替换文件会在下次启动被校验掉 |
| OCR 1.0 | 3 个文件 | 1.6 GB | 视觉塔可走 NPU（需 v79 图） |

### 两个坑

1. **mmproj 同名**：4.6 和 4.7-1B 的视觉投影器都叫 `mmproj-model-f16.gguf`，靠**目录**区分。
   导入错文件时日志会打 `mmproj family 46 != model family 47`。
2. **MD5 钉子**：4.6 / VoxCPM2 / OCR 的 `ggufMd5`、`mmprojMd5` 是硬编码的，手动替换文件
   会在下次启动被判定损坏（这正是"官方 app 替换 4.7 用一次、重启就失效"的原因）。
   用 4.7 请走本 fork 的独立条目，不要往 4.6 目录里塞。

### 本地导入

模型管理 → 导入本地模型 → 选 `.gguf` → 可选 mmproj（纯文本模型跳过）。
导入时会解析 GGUF 元数据，把 `family` 和 `les.moe` 标记写进 SharedPreferences 注册表；
文件被外部删除时该条目会在下次读取时自动剔除。

---

## 7. OCR 的 NPU 路径（可选）

1. 触发 `qnn-v79` workflow（`qairt_version` 默认 `2.52.1.261105`，`arch=v79`）
   它会：拉 QAIRT → 从公开 mmproj 导出视觉塔 → `qairt-converter` 转 DLC →
   `qnn-context-binary-generator` 出 `minicpm-ocr-qnn-v79.bin`（约 868 MB）
2. 侧载：

```bash
adb push minicpm-ocr-qnn-v79.bin /data/local/tmp/
adb shell run-as com.tianchi.minicpmv mkdir -p files/models/minicpm-ocr-1.0
adb shell "run-as com.tianchi.minicpmv cp /data/local/tmp/minicpm-ocr-qnn-v79.bin \
           files/models/minicpm-ocr-1.0/"
adb logcat -s QnnVit | grep -E "probe|tower ready"
```

3. 前提：APK 是 `enable_qnn` 打出来的（含 `libQnnHtp.so` 和 `assets/qnn/libQnnHtpV79Skel.so`）

---

## 8. 已知问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Vulkan 构建曾缺 `vkGetPhysicalDeviceFeatures2` | NDK 的 API-24 `libvulkan.so` 桩只有 Vulkan 1.0 导出表 | 已加 `vulkan_api_shim.c` 运行时转发 |
| Vulkan 构建缺 `matmul_id_*_cm1_*` 符号 | 本 fork 的 shader 生成器没给 iq3_s/iq4_nl/iq4_xs/mxfp4/nvfp4 生成 coopmat 变体 | CI 里把 coopmat 路径 `#if 0`，普通 compute shader 仍走 GPU |
| 视觉塔在 GPU 上卡死/闪退 | 上游 `loadMmproj` 只要检测到 GPU 就 `use_gpu=true` | 本 fork 默认 `visionUseGpu=false`，设置页可开 |
| 4.7-1B 识图一直转圈 | 疑似切片数 × 上下文（原 4096） | 已把 4.7 的 n_ctx 提升到 8192；仍建议先试切片=1 |
| 导入的专家模型加载失败 | `les.moe` 要求 `moe_slot_bank > 0` | 已自动识别并默认 256 |

---

## 9. 私有仓库依赖

`Dry-Earth/qairt-sdk`（private）保存两类不可公开分发的东西：

- `qairt-2.52.1.261105/` —— QAIRT 头文件 + Android 运行时 + v79 骨架（27 MB）
- `backends/<tag>/` —— 上游 snapdragon 后端的镜像（含 SHA256SUMS、FILE-TYPES.txt）

刷新后端镜像：该仓库的 `vendor-snapdragon-backends` workflow。
若上游 release 的 ABI 与 fork 差距变大（`readelf` 对比出现多个 `ggml_*` 缺失），
改用 `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` 容器从 fork 源码编后端。

---

## 10. 提交约定

沿用 `AGENTS.md`：单行英文 commit subject，不带 AI 工具署名，不带 emoji，
不提交编译产物 / keystore / `local.properties`。
