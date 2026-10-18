# SakuraBox for Android

SakuraBox 是基于 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 修改的 Android 网络代理客户端，保留上游来源、原作者署名与许可证。长期使用 sing-box 单内核。

- 源码：[xingmingname/SakuraBoxForAndroid](https://github.com/xingmingname/SakuraBoxForAndroid)
- Telegram：[SakuraBoxchannel](https://t.me/SakuraBoxchannel)
- 客户端版本：`1.0.0`，正式发布标题约定为 **Sakurabox-1.0.0**，对应 tag 为 **v1.0.0**。
- 核心：上游 `1.14.3`，本地适配标识 `1.14.3-sakura`。包含补丁，不是未经修改的官方核心。
- 下载：[本项目 Releases](https://github.com/xingmingname/SakuraBoxForAndroid/releases)。本次发布准备时尚无正式 Release；未经确认不会发布或提供虚构直链。

## 功能与验证边界

保留节点和订阅管理、Android VPN、独立 URL Test、分流、配置导入导出、Android 官方 SFA 配置导出及 YACD。包括 Hysteria2、VLESS/TLS/Reality 等协议适配，部分协议依赖外部插件。实现存在不等于所有功能、所有设备已验收。

当前主要设备验收目标为 Android 16 / HyperOS；最低 API 为 21。独立 URL Test 在 API 21/22 存在明确限制，不静默退回默认网络。Debug 编译或宿主回归不能证明 Android VPN、TUN、物理 Network 或真实网络行为。

## 安装身份与迁移

Release applicationId：`app.sakurabox.client`；Debug：`app.sakurabox.client.debug`。内部 namespace 保留 `io.nekohasekai.sagernet`，不是安装标识。

相对旧 `moe.nb4a` 和 `top.heds.sakurabox` 包名为独立应用，不能覆盖升级或自动读取旧应用私有数据库。请通过用户主动导出/导入迁移。配置可能包含节点凭据，请私下保管。不会自动卸载旧应用或清除数据；Android VPN 互斥机制保留。

客户端版本从开发期 1.4.2 重新编号为 SakuraBox 1.0.0，versionCode 保持 230。不能仅按 versionName 判断覆盖安装、降级或数据兼容性。

## 源码构建

完整步骤、工具版本、依赖 pin 和测试边界见 [BUILDING.md](BUILDING.md)。源码不包含 APK/AAR/SO、签名私钥、缓存、真实用户配置或内部审计报告；核心由公开依赖的固定提交和本仓库补丁重新生成。

产物命名：`SakuraBox-<版本>-<ABI>-<debug|release>.apk`。例如 `SakuraBox-1.0.0-arm64-v8a-debug.apk`。发布标题与 APK 文件名分别管理；不通过文件名伪装正式签名。

## 主动更新检查与发布规则

目前仅计划正式版发布，标题 `Sakurabox-1.0.0`、tag `v1.0.0`，后续正式版遵循 `vMAJOR.MINOR.PATCH`。检查器使用 tag 进行数值比较，而非 Release 标题。关于页仅用户点击时读取本仓库 HTTPS API，不自动下载或安装。保留既有预览检查代码，暂无预览发布时显示无版本，不建立预览发布流程。

GitHub Actions 仅允许手动构建验证、只读权限，不自动创建 PR、推送、上传产物或发布 Release。正式发布需独立配置妥善保管的 Release 签名，并完成设备验收；不要使用历史公开仓库里的签名文件。

## 许可证与来源

见 [LICENSE](LICENSE)、[AUTHORS](AUTHORS)、[NOTICE](NOTICE)、`libcore/LICENSE` 及应用内第三方许可证。保留原开发者版权，品牌更名不改变开源义务。

Credits: [SagerNet/sing-box](https://github.com/SagerNet/sing-box)、[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)、[SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)、[shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)、[Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)。
