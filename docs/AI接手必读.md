# AI 接手必读

## 从哪里开始

本工程维护酷我车机 APK 的补丁和注入代码。基线为 7.3.9.23，原始 APK SHA-256：

```text
3ecb120667584c8026b66a0f62ad61846a77abf01f6dd49bc4b7f0dfac55552f
```

不要改用其他版本的解包结果。Smali 名称和方法都绑定该基线，补丁不匹配会直接终止构建。仓库不包含原厂反编译代码，构建时临时解包。

## 先看这些文件

| 文件 | 用途 |
|---|---|
| `config/version.json` | 显示版本、versionCode、发布仓库、包名及原包哈希 |
| `scripts/build.py` | 解包、补丁、Java/D8、APK 重建、同证书签名、更新清单生成和校验 |
| `scripts/patch_apk.py` | 精简入口、组件、版本、启动和关于页的 Smali/资源修改 |
| `scripts/performance_patch.py` | 页面延后绑定、搜索联想、首页刷新、封面等补丁 |
| `src/cn/kuwo/bridge/SilentStartup.java` | 开机/覆盖安装启动、启动状态、原音乐前台服务 |
| `src/cn/kuwo/bridge/GithubUpdater.java` | 关于页绑定、GitHub 更新清单入口和边界校验 |
| `src/cn/kuwo/bridge/UpdateActivity.java` | 检查/下载状态、存储权限、APK 校验、安装器调用 |
| `src/cn/kuwo/bridge/BridgeProvider.java` | 接口入口、调用方白名单、异步请求和观察通知 |
| `src/cn/kuwo/bridge/MusicApi.java` | 音乐接口分发及宿主适配 |
| `sdk/api-contract.json` | 机器可读的 67 项接口合同 |

`BuildInfo.java` 由构建脚本根据版本配置生成在外部构建目录，不手工维护。

## 不要混淆的标识

- 安装包名：`cn.kuwo.kwmusiccas`，沿用此前车机用户版，避免与系统预装包冲突。
- 原内部类名前缀：`cn.kuwo.kwmusiccar`，不能全局替换。
- 同机接口：`content://cn.kuwo.kwmusiccar.bridge`。
- 默认客户端：`com.cabindeck`，扩展白名单修改 `config/bridge_clients.txt` 并重新构建。
- 签名证书 SHA-256：`f90366ddd1c20a75d21fb6162dc07572348922ab744e481c1ceb3d454d4593d0`。

接口总数包含 `capabilities` 自身，共 67 个入口；`capabilities.methods` 返回其余 66 个方法。

## 版本与更新维护

新版本执行 `python3 scripts/bump_version.py`，补充 `docs/版本更新.md`，构建并使用既有证书签名。提交并推送代码后运行发布脚本。`update.json` 必须和对应 APK 一起作为同一 Release 的附件，不能手写不匹配的哈希或版本码。

应用只在打开更新页或点击检查时访问更新源，不在冷启动时联网检测。详见 [更新机制](更新机制.md)。

## 本机资料位置

本次整理前的源码、原始 APK、历史版本和设备记录归档在项目同级 `酷我-历史归档-20261103/`。既有签名材料另存于 `~/.local/share/kuwo-music/signing/`。这些是本机路径，不是其他开发者必须具备的目录；其他开发者通过构建参数提供自己的受授权基线及维护者提供的同一签名材料。

## 验证边界

构建脚本自动验证 APK 签名、对齐、DEX 注入、反射符号、版本和资源修改。它只证明产物结构，不证明在线播放、登录、音质或真实开机行为。实测结论和仍未验证的内容写入版本更新记录。不要通过新增无限重试或保护分支掩盖具体问题。
