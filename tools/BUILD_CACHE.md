# 本地构建缓存与保留规则

这些规则用于离线工具，不改变应用、协议、签名或核心版本。没有自动删除任务。

## Gradle 8.10.2

下载依赖复用同一个受保护的专用 Gradle User Home。每次构建的 build、project-cache、日志与交付输出继续隔离，避免覆盖已有 APK、AAR 和 mapping。

首次使用离线 Android 工具前，选择已有合法下载依赖的 Home，并安装策略：

```powershell
./tools/install-gradle-cache-policy.ps1 -GradleUserHome $env:GRADLE_USER_HOME
./tools/offline-android-build.ps1 -GradleUserHome $env:GRADLE_USER_HOME -CoreAar <本次AAR> -RunDirectory <新的输出目录>
```

安装器只新建 `init.d/sakurabox-cache-policy.gradle`，相同内容再次执行不写入；已有不同内容时停止。Android入口检查策略摘要。策略使用 Gradle 8.10.2 的 `Settings.caches.cleanup = Cleanup.DISABLED`，并固定该属性；不同 Gradle 版本或冲突设置会报错，需先复核兼容性。

`--offline` 仅限制依赖获取，`--no-daemon` 不代表关闭缓存回收。此策略控制 Gradle User Home 的受管清理，不阻止任意任务的删除操作，不是文件系统备份。直接运行其他入口、使用别的 Home/Gradle 版本、修改 init script 或并行外部进程仍需单独管理。项目缓存和 build 仍应隔离；没有把旧缓存当作永久归档。

官方依据：[8.10.2 CacheConfigurations](https://github.com/gradle/gradle/blob/v8.10.2/subprojects/core-api/src/main/java/org/gradle/api/cache/CacheConfigurations.java)、[8.10.2 Cleanup](https://github.com/gradle/gradle/blob/v8.10.2/subprojects/core-api/src/main/java/org/gradle/api/cache/Cleanup.java)。

## Go 缓存选择

host 与 native 工具的 `GOCACHE` 优先级为：

1. 显式 `-SharedCache`。
2. 已有 `GOCACHE` 环境变量。
3. `$env:SAKURABOX_CACHE_ROOT/go/<Go版本>/<host或native>`；未设置根目录时使用项目 `.local-builds/shared-cache`。

已有可信缓存应显式传入，不复制、不迁移，也不根据历史目录名自动选用。默认路径跨任务稳定；Go自行按输入、编译器、目标和参数区分对象。host/native默认分开，显式共享仍可使用同一可信缓存。模块下载缓存 `GOMODCACHE` 与编译缓存分开保留；不把模块下载目录用作 `GOCACHE`。

只有确需冷缓存验证时使用 `-ColdCache`：它选择新输出目录中的 `cold-go-cache`，拒绝已有目录，也不能与 `-SharedCache` 同用。不自动清理冷缓存。

native工具继续保持固定Go/NDK、原有tags、多ABI、gomobile补丁和AAR源码摘要验证。gomobile工作目录、GOPATH及工具输出继续隔离；复用这些目录的额外改动尚未验证，不自动实施。
未显式指定模块缓存时，native入口先解析已有 `GOMODCACHE`，再设置隔离的gomobile `GOPATH`，避免把模块下载意外定向到每次新任务的空目录。

## 生命周期分类

| 类别 | 位置/复用 | 清理条件与风险 |
|---|---|---|
| 源码、Git、patch、许可证、签名材料 | 原维护位置；签名独立安全备份 | 禁止作为缓存清理；Git不能恢复未提交文件和私钥 |
| Gradle下载、Go modules、SDK/NDK/JDK/Go | 已有共享Home/模块库/工具目录 | 长期复用；离线恢复条件独立核验前不得清理 |
| Go编译对象、Gradle transforms/DSL | 指定稳定缓存路径 | 可复用；不是唯一历史证据，人工核验引用和恢复前提后才决定清理 |
| AGP intermediates、tmp、KSP、project-cache、显式冷缓存 | 本次独立输出目录 | 构建已结束、无独有源文件/证据、输入和输出保留后列入精确候选；不能整删build父目录 |
| APK/AAR、mapping、source manifest、公开证书摘要 | 每次独立交付目录 | 长期追溯；不可覆盖，不能因同名或较旧自动删除 |
| 测试结果、日志、fixtures、恢复清单、异常记录 | 对应任务证据目录 | 私有保留；内容核验后由维护者确定保留期，不能自动按扩展名删除 |

三个离线入口写入小型 `retention-<随机ID>.json`，记录缓存路径、输出类别和人工审查要求；它不是删除授权，也不假称构建已经成功。实际结果以任务日志及测试退出记录为准。复用编译缓存减少重复对象，不代表允许无限保存每次构建的 intermediates；每次任务结束应审查保留记录、归档交付证据并另行批准精确清理。

## 产物验证

`verify-artifacts.py` 对文件和ZIP成员流式哈希，对SO做ELF头/程序头检查，保留ZIP CRC、逐字节比较、精确AGP strip、Go build info、JNI、DEX、签名及对齐验证。

仅 `llvm-strip`、`go version -m`、`llvm-readelf` 需要实体SO；它们使用每ABI新建临时目录，成功或异常均清理本次临时文件。`--temporary-root` 可指定已有的本地scratch父目录，不能提供已有文件供覆盖。最终证据目录保留摘要和文本，不再永久保存解包SO。Windows外部进程锁住临时文件导致无法清理时会报错，不能假称成功。

## 轻量验证

```powershell
python -B -m unittest discover -s tools/tests -p test_verify_artifacts.py -v
./tools/tests/test-build-cache.ps1 -OutputDirectory <新的临时测试目录>
```

单元测试不是Android/原生四ABI编译或真机验收。更换Gradle或Go版本前，重新验证缓存策略和构建入口，不仅修改版本字符串。
