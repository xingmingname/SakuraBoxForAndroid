# SakuraBox 源码构建

## 注：以下为AI生成

## 工具链

- JDK 21、Go 1.26.9、Git 和 Bash。Windows 离线工具需要 PowerShell 与 Python 3.11+。
- Android SDK platform 35、Build Tools 35.0.1、NDK 27.0.12077973。应用最低版本为 Android API 21。
- Gradle 使用仓库的 Wrapper。SDK 与 JDK 路径通过 ANDROID_HOME 和 JAVA_HOME 配置。
- sing-box、libneko 和 gomobile 的固定提交记录在 buildScript/lib/core/get_source_env.sh。源码准备脚本会应用 buildScript/lib/core/patches 中的适配。

## Linux 和 Bash

在仓库根目录执行。

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
bash ./run lib core
./gradlew assembleOssDebug assembleOssRelease
```

核心构建将 AAR 写入 .build-deps/outputs/ 和 app/libs/。Android 默认读取 app/libs/libcore-sing-box-1.14.3-sakura-with_nakis.aar。要使用独立输出的核心，传入 -PnekoCoreAar=<AAR绝对路径>。旧 AAR 即使文件名相同，也可能缺少当前 gomobile 接口。优先使用来自当前固定源码和补丁的 AAR。

如需在 APK 内附带初始 GeoIP/Geosite，运行 bash buildScript/lib/assets.sh。应用仍提供运行时资产管理与更新。

## Windows 离线构建

先准备固定内核源码及当前工具版本所需的 Go、Gradle 缓存。离线 Android 构建使用带缓存保留策略的 Gradle User Home。按需使用 -GradleExecutable 指定同版本 Gradle。每次使用新的输出目录。

```powershell
./tools/install-gradle-cache-policy.ps1 -GradleUserHome $env:GRADLE_USER_HOME
./tools/offline-native-build.ps1 -GoRoot $env:GOROOT -RunDirectory .local-builds/native
./tools/offline-android-build.ps1 -CoreAar .local-builds/native/libcore-sing-box-1.14.3-sakura-with_nakis.aar -RunDirectory .local-builds/android
```

可用 -Tasks 选择具体 Gradle 任务。四种 ABI 的 APK 位于相应的 build/app/outputs/apk/oss/ 输出目录。tools/verify-artifacts.py 可核验 ABI、JNI、原生库来源、签名及 16 KiB 对齐。

正式签名使用维护者在本机提供的签名材料与构建属性。源码不包含签名所需的密钥。无正式签名配置时，Release APK 为未签名产物。

## 第三方资源

- Nakis、Sudoku 和其他原生集成的上游及许可见 NOTICE、libcore/nakis/LICENSE、libcore/sudoku/LICENSE 和 libcore/sudoku/UPSTREAM.json。
- 应用直接使用 app/src/main/assets/yacd.zip。第三方授权见 app/src/main/assets/LICENSE。该归档的等价源码、固定提交和校验信息见 NOTICE。
- 核心、应用和资源的版本分别由固定源码、nb4a.properties 及随附资源决定。应用更新记录见 GitHub Releases。
