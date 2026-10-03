# Repository Agent Rules

本仓库是 agent 协作开发项目。任何会话进入本仓库工作前，先读完本文件与 DESIGN.md。

## 项目概述

misc-uploader-android：杂物数字仓库（tianyi-misc-repo，私有仓）的 Android 上传客户端。双窗口文件管理器（左=手机文件，右=远程 /misc），多选跨侧上传，可靠队列。原生 Kotlin + Jetpack Compose；上传协议纯 REST（OpenList）。

- 设计基线：`DESIGN.md`（本仓，拍板表）；完整依据在私有仓 `tianyi-misc-repo/docs/`（client-protocol-decision.md / android-client-plan.md），私有仓内容不得复制进本公开仓。

## 环境规约（重要）

- **本机零环境**：不安装 Android SDK / Gradle / Android Studio 等任何构建工具链；本地不执行编译、打包、发布——全部交给 GitHub Actions。
- adb 仅用于采集设备日志，不用于构建。
- 迭代方式 = 改代码 → push → CI → 看结果 → 修。一轮约 5-10 分钟。
- **每次 push 后必须监听 Build & Release 到 completed**（`gh run watch` 或 API 轮询）；failure 拉 `gh run view <id> --log-failed` 分析修复重推，重复直到 success。这是硬性流程，不要构建一半就汇报完成。
- 纯逻辑验证可用系统已有 node/python 直跑，不为此新增依赖。

## CI/CD 规约

- workflow：`.github/workflows/release.yml`；触发：push 到 master / 手动 dispatch。
- **新 push 自动取消同分支进行中的旧 run**（concurrency + cancel-in-progress）；监听时只看最新 run。
- 每次实质性代码变更必须同时通过质量门：`:app:compileReleaseKotlin` + `:app:testDebugUnitTest`。
- 产物：签名 APK + sha256 + latest.json 自动发布到 `v{version}-{buildtime}` 格式 tag 的 Release；保留最近 10 个 Release，其余清理。
- 版本号：CI 按中国时区生成，versionCode = 秒级 Unix 时间戳；仓库内数值仅是本地默认值，不要手动改。
- 签名：keystore 走 Actions secrets（`ANDROID_KEYSTORE_BASE64` / `_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`）；未配置时 fallback debug 签名（可装可测，与正式签名版不能互相覆盖安装）。

## 已踩过的坑（随迭代补，别再踩）

（暂无；首建 CI 时记录）

## 凭据与安全规约（不可妥协）

- 代码/配置/文档禁止出现任何服务器 IP、域名、账号、密码、token 字面量——仓库是公开的；服务器地址全部由用户运行时在设置页配置。
- 密码存储 = Keystore AES-GCM 包 DataStore；密码永不写入日志。
- 私有仓（tianyi-misc-repo）可引用其文件名/路径，内容不得复制进来。

## 行为纪律

- **必须先拍板再动手**：协议/架构级决策；可能丢失或破坏数据（队列、历史、远端文件）的改动；不可逆操作；账号权限相关方案。
- 免确认直接做（做完汇报）：纯 UI 展示层逻辑、缺陷修复、文档，以及其它可逆且不碰服务器、不碰用户数据的改动。
- 归类拿不准时用选项列表问，给推荐但让用户拍板。
- 每次实质变更后同步更新 `DESIGN.md`（拍板表）与 README。
