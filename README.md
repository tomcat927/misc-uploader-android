# misc-uploader-android

杂物数字仓库的 Android 上传客户端：双窗口文件管理器（左=手机文件，右=远程目录），多选一键跨侧上传，可靠后台队列。原生 Kotlin + Jetpack Compose。

> 单人自用工具，侧载分发。服务端为 [tianyi-misc-repo](https://github.com/tomcat927/tianyi-misc-repo)（私有仓）的 OpenList 热层架构；Windows 桌面端见 [misc-uploader](https://github.com/tomcat927/misc-uploader)。

## 功能（按里程碑推进，见 DESIGN.md）

- 双窗口：左侧手机文件 / 右侧远程目录树，触摸聚焦、一键展开单侧、面包屑导航
- 多选 → 一键跨侧上传，落点完整路径确认框
- 可靠上传队列：前台服务、并发可配、失败自动重试（2s/8s/30s 退避）、内联进度
- 纯 REST 协议（OpenList）：流式上传、内存恒定；专用 chroot 账号，密码 Keystore 加密存储
- 诊断页：上传日志 / 队列失败原因 / 历史退出原因，应用内可复制导出

## 安装

到 [Releases](../../releases) 下载 APK（附 sha256 校验；国内可用 Release 内的 gh-proxy 加速直链）。

首次运行：设置页填 OpenList 地址（`https://…:5245`）、用户名、密码 → 连接。
建议为 App 建专用账号（base_path 限定 `/misc`，最小权限），不要用 admin。

## 构建

本机零环境：构建/签名/发布全部在 GitHub Actions。push 到 master 即自动构建并发布 Release（`v{version}-{buildtime}`），保留最近 10 个。签名 keystore 走 Actions secrets（`ANDROID_KEYSTORE_BASE64` 等），未配置时使用 debug 签名（可装可测，与正式签名版不能互相覆盖安装）。

## License

MIT
