# 酷我音乐车机版

基于酷我车机版 7.3.9.23 的可重复构建补丁工程，当前版本 **1.0.0**。包含维护的 Java 代码、Smali/资源补丁、构建发布脚本和同机音乐接口 SDK；原厂代码由构建时提供的原始 APK 解包得到。

- [下载安装包](https://github.com/cxcboss/kuwo-music-car/releases/latest)
- [AI 接手必读](docs/AI接手必读.md)
- [修改内容与启动机制](docs/修改说明.md)
- [GitHub 更新机制](docs/更新机制.md)
- [构建与发布](docs/构建与发布.md)
- [完整接口说明](sdk/接入说明.md)

## 目录

```text
config/       版本配置、接口白名单、原包符号清单
src/          精简适配、性能调整、后台启动、更新和音乐接口
scripts/      构建、产物校验、版本递增和发布
sdk/          Java 客户端、67 项接口合同、CabinDeck 对接说明
docs/         修改说明、更新约定和接手资料
AGENTS.md     维护规则
```

原始 APK、签名密钥、解包结果、历史版本及测试记录均保存在项目外。Git 仓库不存放这些文件，安装包通过 Releases 发布。

## 使用

首次安装后打开应用并完成原隐私确认。开机启动默认开启，可在原设置页关闭。后台启动仅准备音乐服务，保持无声通知，不打开页面、不自动播放。

在 **关于 → 检查更新** 检测本仓库的最新正式 Release。下载完成后进入系统安装流程；系统需要时会请求存储及安装权限。安装包一直保存在：

```text
内部存储/000/酷我音乐更新/1.0.0-酷我音乐.apk
```

安装器未能完成安装时，可通过车机文件管理器打开该文件手动安装。

## 构建

准备 Python 3.10+、JDK 17+、Apktool 2.12.1、Android SDK platform 35 和 build-tools 36.1.0，以及指定原始 APK 和既有签名密钥。

```bash
python3 scripts/build.py --base-apk /绝对路径/酷我音乐-cn.kuwo.kwmusiccar.apk
```

构建结果写入 `~/.cache/kuwo-music/builds/时间戳/`。详细步骤及签名要求见 [构建与发布](docs/构建与发布.md)。

## 版本规则

显示版本从 `1.0.0` 开始，之后每次递增 0.01：`1.0.01`、`1.0.02`……`1.0.99`、`1.1.00`。Android `versionCode` 从 **74000** 开始，每次加 1，确保能覆盖原有精简版 73928。执行 `python3 scripts/bump_version.py` 统一更新配置；不要只手动改界面版本文字。
