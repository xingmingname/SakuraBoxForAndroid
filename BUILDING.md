# SakuraBox 源码构建

正式发布准备版本：应用 `1.0.0`（versionCode 230），核心 `1.14.3-sakura`。
发布标题约定 `Sakurabox-1.0.0`，tag `v1.0.0`。本文不是已发布声明。

## 工具和依赖

- Git、Bash；Windows 原生离线脚本另需 PowerShell、Python 3.11+。
- Go 1.26.9、JDK 21；Gradle Wrapper 8.10.2、AGP 8.8.1、Kotlin 2.0.21。
- Android SDK platform 35、build-tools 35.0.1、NDK 27.0.12077973；最低 API 21。
- SDK/JDK 路径通过 `ANDROID_HOME`、`JAVA_HOME` 设置；不要提交 `local.properties`。
- 在线首次构建需要从项目声明的公开源下载 Go/Gradle 依赖。离线命令要求先有相同版本的合法本地缓存。

源码 pin 位于 `buildScript/lib/core/get_source_env.sh`：

| 依赖 | 官方/原维护方 | 固定提交 |
|---|---|---|
| sing-box v1.14.3 | SagerNet/sing-box | `7054cac5124465ffdb145870d7c01885566711b1` |
| libneko | MatsuriDayo/libneko | `1c47a3af71990a7b2192e03292b4d246c308ef0b` |
| gomobile | MatsuriDayo/gomobile | `17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f` |

`libcore/go.mod` 的相对 replace 指向 `.build-deps`，不指向开发者个人目录。
`get_source.sh` 应用 sing-box、libneko Socket protect 应答和 gomobile 补丁，并复制 boxapi/nekoutils 适配源码。
遇到依赖目录已有不匹配修改时明确停止，不覆盖用户修改。

## 首次构建（Linux / Bash）

在项目根设置好 SDK、NDK 和 JDK 后执行：

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
bash ./run lib core
./gradlew testOssDebugUnitTest assembleOssDebug processOssReleaseMainManifest
```

核心 AAR 由源码新生成至 `.build-deps/outputs/` 和 `app/libs/libcore-sing-box-1.14.3-sakura-sr01.aar`。
缺少当前 AAR 时 Android 配置明确失败，不自动挑选旧版本。
已有 AAR 不会被原生脚本覆盖；重建请设置新的 `OUTPUT_AAR` 和 `APP_AAR`，或使用下面独立输出流程及 `-PnekoCoreAar`。

路由数据库属于外部数据资产；若需要 APK 内附初始 GeoIP/Geosite，请额外运行 `bash buildScript/lib/assets.sh`。
该脚本在线获取维护方当前资源，不宣称数据库逐字节可再现；运行时仍保留资产管理与更新功能。
现有 `yacd.zip` 是随应用分发的第三方 UI，保留来源及许可证说明，见 NOTICE。

## Windows 离线验证

先运行 `bash buildScript/lib/core/get_source.sh` 准备固定依赖；再使用本地缓存，不包含在源码仓库中。
PowerShell 工具接受明确环境参数，没有开发者专用 SDK、缓存或报告路径。

```powershell
./tools/offline-native-build.ps1 -GoRoot $env:GOROOT -RunDirectory .local-builds/native-check -OutputName libcore-sing-box-1.14.3-sakura-sr01.aar -SharedCache $env:GOCACHE -ModuleCache $env:GOMODCACHE
./tools/offline-android-build.ps1 -CoreAar .local-builds/native-check/libcore-sing-box-1.14.3-sakura-sr01.aar -RunDirectory .local-builds/android-check -Tasks testOssDebugUnitTest,assembleOssDebug,processOssReleaseMainManifest
./tools/offline-go-tests.ps1 -GoRoot $env:GOROOT -RunDirectory .local-builds/go-check -SharedCache $env:GOCACHE -ModuleCache $env:GOMODCACHE -ConfigFixtures .local-builds/android-check/config-fixtures
python tools/check-shortcut-security.py
```

可用 `-GradleExecutable` 指定已安装的同版本 Gradle，默认使用 Wrapper；首次没有缓存时离线构建将正常失败。
源码仓库不附 AAR、APK、SO、Gradle/Go 缓存、签名密钥或测试输出。

`tools/prepare-host-tests.py` 复制当前测试源码，显式替代 Windows 不支持的 Unix Socket protect 服务和 Android TUN FD 操作。
所有根目录 Go 回归测试均保留；宿主模拟不证明 Android Network、TUN、JNI、VpnService 或跨 UID 行为。
需要浏览器/Android 设备的专项测试可能要求额外受控环境，不能将 SKIP 记为通过。

## 包内验证

四 ABI Debug APK 位于独立输出目录或 `app/build/outputs/apk/oss/debug`，命名 `SakuraBox-1.0.0-<ABI>-debug.apk`。
`tools/verify-artifacts.py` 为 Windows Android 工具链的严格核验器，要求传入自己可信的证书摘要：

```powershell
python tools/verify-artifacts.py --apk-directory <APK目录> --aar <本次AAR> --output <新的证据目录> --go-root $env:GOROOT --sdk $env:ANDROID_HOME --java "$env:JAVA_HOME/bin/java.exe" --expected-signer <证书SHA256>
```

核验实际 APK 和 AAR 原生库、AGP strip、Go build info、JNI、签名、ABI 和 16 KB 对齐。
不要仅凭文件名或字符串检索断言核心来源。

## Release 签名与发布边界

正式构建需使用维护者独立、未公开、妥善保存的 Release 密钥。
现有构建逻辑读取本地 `release.keystore` 及未提交的签名环境/属性；源码不提供密钥或密码。
未配置签名时只会产生未签名的 Release 候选，不能用 Debug 证书冒充正式发布。
不要使用旧上游历史里公开的签名文件；删除当前文件不会删除已有 Git 历史。

GitHub workflow 只支持手动、只读构建验证，没有上传/发布步骤。创建提交、push、Release 和上传 APK 需另获维护者明确批准。
公开前还需真机验证：安装与共存、关于页 1.0.0、VPN 撤销与切网、Hy2/VLESS、独立 URL Test A/B 隔离、YACD 认证、导入配额、快捷方式、SFA 导出。


### 独立 Release 签名准备（未配置，不在本轮生成密钥）

请使用 SakuraBox 独立、从未公开的签名密钥，固定其公开证书 SHA-256，并离线备份。
不要复用 Git 历史里的 NekoBox PKCS#12 文件；本地没有该文件和签名参数时，Debug 构建可独立进行。
当前 Helpers.kt 仅在提供 KEYSTORE_PASS 等参数时读取本地 release.keystore，并会将此签名同时应用于 Debug；
因此正式签名应使用隔离且只构建 Release 的工作环境，避免把正式凭据带入普通 Debug 构建。
在不改动现有签名逻辑的方案下，未来由维护者在隔离副本放置新的、被 ignore 的 release.keystore，
仅通过本地未跟踪属性或进程环境注入凭据。长期可另行审查支持外部 KEYSTORE_FILE 与 Debug/Release 分离。
私钥、密码及包含密码的命令都不得提交或写入构建日志；只使用证书摘要核验产物。
任何签名配置生效前，再检查 git ls-files 中没有密钥、属性或环境文件；不要使用 git add -f 绕过白名单。
本仓库 workflow 不接收签名 Secrets，也不上传或发布任何文件。正式 APK 签名及设备验收需另行确认。

## YACD 资源来源与可复核的参考构建

Android 构建直接使用保留的 `app/src/main/assets/yacd.zip`，无需运行 npm 或重新生成 UI。
该 ZIP 为 Yacd-meta 0.3.6，SHA-256 为
`5ab53b5c2dbee58d2074b00b71f228b3f7c0e748c9ac34501d33b01ef186b7a7`。
完整第三方许可位于 `app/src/main/assets/LICENSE`，来源说明见 `NOTICE`。

可从 MetaCubeX 官方固定源码复核现有运行文件：

- 源码提交：`e3bba3b401383a207da875b279d2e713048782ab`。
- 源码归档：`https://codeload.github.com/MetaCubeX/Yacd-meta/zip/e3bba3b401383a207da875b279d2e713048782ab`。
- 此归档 SHA-256：`fa7ea62fbebe162e19f4734c49b6f1d3fdb083daa9c738cf94b2fea80c8f98be`。
- `pnpm-lock.yaml` SHA-256：`6add30eb046034c43e4622e305ea1c258314424593adb1d7dfc9ab9a8b29e261`。
- 已用于参考构建的工具：Node.js 24.19.0、pnpm 8.15.9；Vite 4.0.4 来自原锁文件。

在独立空目录中下载并校验官方归档，解压后进入源码根目录；使用上述 pnpm 版本运行：

```bash
pnpm install --frozen-lockfile --ignore-scripts --ignore-pnpmfile --registry=https://registry.npmjs.org
node node_modules/vite/bin/vite.js build
```

依赖安装禁止生命周期脚本；不升级依赖，不改源码或 Vite 配置。输出位于原配置的 `public/`。
移除原 ZIP 的 `Yacd-meta-gh-pages/` 根目录后比较文件：现有 46 个文件中 45 个应逐字节一致，
仅 `CNAME` 末尾换行不同。所有现有 JS、CSS、HTML、Service Worker、图像和字体都包含在这 45 个文件中。
这是对应已分发内容的等价源码验证，不代表找回了唯一的历史构建提交、Node 版本或部署流水线。

参考构建还会生成原 ZIP 没有的 `Twemoji_Mozilla.ttf` 及其带哈希副本。
原 CSS 存在对应字体引用，但原 ZIP 未包含它；不要将这两个新增文件冒充原分发内容，
也不要直接覆盖现有 ZIP 或递增 Android 资源计数器。
若以后采用重建包，需要额外保留 Twemoji Mozilla v0.7.0 的 Apache-2.0 代码许可和 CC-BY-4.0 图形许可，
重新核对字体来源、完整通知及资源清单，并完成页面、认证、缓存更新和 Android WebView 回归。
本次保留原 UI 文件，参考构建不是已验收的替代发行包。
