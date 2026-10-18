# SB-SEC-001 独立 UID 验证夹具

这是测试源码，不是 SakuraBox 的业务模块，也不会被打入 SakuraBox。包名为
`local.sakurabox.security001probe`，没有 shared UID、signature 权限、HOME 角色或快捷方式宿主权限。
使用独立测试证书打包，不能使用 SakuraBox 签名。只能在隔离测试设备/测试安装中运行；
在存在旧漏洞的版本上运行，负向用例可能真的停止 VPN。

当前交付只编译验证了 Java 源码，**未打包、安装或运行本夹具**。

## 自动化运行方式

1. 用现有 Android SDK 的 aapt2、javac、d8 和 apksigner 将此目录的 Manifest 和 Java 源码
   打包为单独 APK。Manifest 注册的 runner 是框架 `Instrumentation`，无需 AndroidX Test。
   Java 编译 classpath 使用项目现有 `platforms/android-35/android.jar`，minSdk 21。
   测试包仅用于测试，签名与被测 App 不同，安装后核验两包 UID 不同。
2. 安装修复后的独立 SakuraBox 测试构建及本测试包，解锁设备，关闭其他浮窗。
3. 执行（release 包名时把 target 改为 `app.sakurabox.client`）：

```powershell
adb shell am instrument -w -e target app.sakurabox.client.debug local.sakurabox.security001probe/.ProbeInstrumentation
```

runner 让自己的普通 UID Activity 位于前台，再使用显式 Intent 调用 SakuraBox。
它断言 6 次私有入口调用被 `SecurityException` 拒绝（3 种操作 × 是否伪造 extras），
及 6 次公开旧入口调用只出现确认框（3 种操作 × 是否伪造 extras），随后按返回取消。
另外断言普通 UID 发送三种系统保护广播均被拒绝，并发送一次伪造的普通显式广播；
后者需结合下表的服务/数据库快照确认没有启动副作用。
从不点击“仅允许这一次”。只有断言全部成立才返回 `RESULT_OK`。
界面语言须为简体中文或英语；其他语言目前回退英语。

不能用 `adb shell am start` 的结果替代上述独立 UID 测试：shell 具有不同权限。
夹具另有普通 Launcher 页面，完全不启动 instrumentation 也能按按钮执行显式调用。
该路径可排除测试框架对行为的潜在影响。前三个按钮只应触发确认，最后一个应明确拒绝；
Toggle 可填入隔离测试节点 ID。无论使用哪条路径，都须核对 VPN/当前节点前后状态。

## 完整设备验收补充（尚未执行）

| 用例 | 操作和必须核对的结果 |
|---|---|
| VPN 已运行 / 未运行 | 两种状态各执行 runner；记录运行状态、当前节点 ID 前后相同，不能只检查弹窗 |
| 系统 VPN 已授权 / 未授权 | 两种状态各测试；无用户确认前不能启动内核、停 VPN 或弹系统 VPN 授权框 |
| 指定 profile | `ProbeActivity.attempt` 传入隔离安装中已存在的合成节点 ID，取消后节点和 VPN 不变；确认时界面明确显示该节点，操作符合 Toggle 语义 |
| 参数错误 | profile 为字符串、0、负值、不存在 ID，或 Enable/Disable 携带 profile，必须拒绝；无 profile 的三个普通快捷操作仍可确认使用 |
| 重复/生命周期 | 保持确认框时再次发送同组件 Intent；原请求取消。旋转、重建、进程死亡和后台启动不得恢复授权或产生控制副作用 |
| 正常 Launcher | 长按三个静态快捷操作应经系统 broker 一次触发；新增 profile 固定快捷方式保持节点 ID；旧固定快捷方式升级后迁移，未迁移者需确认 |
| 扫码/Tile/通知/主界面 | 各自正常工作，Tile 锁屏解锁机制和通知控制不改变 |
| 系统撤销 | 用户启动另一个 VPN，SakuraBox 正常 `onRevoke()` 停止且不抢占重连 |
| 开机恢复入口 | 隔离安装开启“重启后恢复”，由探测 App 发普通显式广播，VPN/节点不变；真实重启和应用升级后的合法恢复保持正常 |
| Overlay/锁屏 | 完整及局部遮挡下点击不能授权；锁屏、失焦、离开确认页后请求失效 |

完整验收需要同时观察服务状态和 `selectedProxy`。普通探测 App 按设计不能读取 SakuraBox
私有数据库，因此本 runner 不宣称自动验证这两个值。可在隔离安装用同 UID instrumentation
通过现有 Binder/数据库读取前后快照，或人工核对主界面和服务日志（不输出节点凭据）。
这与本项目的 JVM 状态机测试、Manifest 检查属于不同证据层次。
