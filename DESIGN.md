# misc-uploader-android 设计文档（拍板表）

> 完整依据在私有仓 tianyi-misc-repo：`docs/client-protocol-decision.md`（协议拍板）、`docs/android-client-plan.md`（需求分析 + 技术架构）。
> 本文只留结论与执行约定；改方案前先读 AGENTS.md。

## 需求

杂物仓库（OpenList `/misc` 热层）的手机端上传客户端：双窗口文件管理器（左=手机文件，右=远程 `/misc`），多选跨侧上传，可靠队列，所见即所得核对落点。单用户自用，侧载分发。

## 拍板决策一览（2026-10-03）

| 决策点 | 结论 |
|---|---|
| 技术栈 | 原生 **Kotlin 2.x + Jetpack Compose + Material3**；单模块 `:app` 三层（ui/data/core）；MVVM + StateFlow |
| 上传协议 | **纯 REST**（OpenList）：`POST /api/auth/login`、`POST /api/fs/list`、`POST /api/fs/mkdir`、`PUT /api/fs/put`；无 WebDAV |
| 协议防御 | 响应强制 JSON + `code==200`；401 重登一次（OkHttp Authenticator）；`File-Path` 头 URL 编码统一封装 |
| 服务端闸门 | 上传带 `Overwrite: false`（同名 403 = 已存在分支）；可选 `Last-Modified` / `X-File-Sha256` |
| 接口缝 | `RemoteStorage` 接口（login/list/mkdir/upload），V1 仅 OpenListRestClient 一个实现（迁离 alist 系时补 WebDAV 实现） |
| 上传队列 | 前台服务（dataSync 类型）+ **Room 唯一真相源**（UI 与服务同进程，靠 Room Flow 对齐）；状态机沿用桌面端 hashing/pending/uploading/cooldown/done/skipped/failed + finished_at；并发/重试退避 2s/8s/30s 可配 |
| 进度 | 客户端字节计数（ProgressRequestBody），协议无关 |
| 网络 | kotlinx.serialization 1.7.3 + **OkHttp 4.12.0**（M1 实钉；原拍板写 5.x，stable 坐标未验证，API 同构，升级留 V1.1 评估）；不上 Retrofit（全 app 4 个端点） |
| DI | Hilt（KSP） |
| 持久化 | Room（队列+历史）+ Preferences DataStore（设置）；密码 = Keystore AES-GCM 包 DataStore（androidx security-crypto 已废弃不用） |
| 密码框语义（移动端适配，M1） | 输入框留空 = 沿用已存密码（placeholder 掩码提示，对齐桌面端哨兵语义）；「显示」= 取回真实密码进输入框，「隐藏」= 清空回到沿用态；显式清空后保存 = 删除已存密码 |
| 地址归一化（M1） | 无 scheme 自动补 https://、去尾部斜杠（桌面端要求手输完整 URL，移动端放宽） |
| 连接测试（M1） | 「连接」= 保存 + login + list("/") 一步，已连接卡片显示根目录项数；启动时配置齐全自动连接（MiscApp → ConnectionManager） |
| 协议回归测试（M1） | `OpenListClientTest`（MockWebServer，9 例）：token 透传 / 401 重登一次 / 防无限循环 / File-Path 编码还原 / 流式+进度 / Overwrite 头 / 200+HTML 假成功防御 / 403 同名闸门——质量门从空转变为真门 |
| 本地文件 | `MANAGE_EXTERNAL_STORAGE` + `java.io.File`（自用侧载，不上架） |
| 双窗口交互 | 借 SplitLanzou 的交互模型与参数：聚焦模型、展开动画 400ms、非聚焦侧两列瀑布流、底部 12sp 操作条；**借参数不借实现**（其代码 Apache-2.0，选择性借用需署名） |
| 工程纪律 | **零本地环境**：不装 Android SDK/Gradle/Studio，构建签名发布全在 GitHub Actions，push 后监听到 completed；诊断下沉（设置→调试日志页，V1 必做）；adb 仅采集日志 |
| CI | push → `:app:compileReleaseKotlin :app:testDebugUnitTest` 门禁 → `assembleRelease` → 自动 tag Release（gh-proxy 直链 + sha256 + latest.json + keep-10 + concurrency 取消旧 run）；versionCode = 秒级 Unix（中国时区），versionName = `{version}-{buildtime}` |
| minSdk/target | 26 / 35（compileSdk 35，AGP 8.7.3 + Gradle 8.10.2 + Kotlin 2.0.21） |
| 分发 | 侧载：GitHub Release APK；不上架 |
| M0 裁剪 | minify=false、lintVital 关（质量门 = compile + unit test）；混淆规则随 V1.1 去重一起补 |

## V1 范围（MUST）

1. 双窗口主界面：本地浏览 / 远程 `/misc` 目录树，聚焦切换 + 展开动画，面包屑 + 新建文件夹
2. 多选 → 上传：目标完整路径确认框，REST PUT 流式
3. 上传队列：前台服务 + 内联进度 + 失败原因 + 重试
4. 设置页：地址/账号/密码（失焦自动保存 + 连接测试，对齐桌面端交互）
5. 分享接收（ACTION_SEND / SEND_MULTIPLE）辅助入口
6. 诊断页（上传日志 / 队列失败 / ApplicationExitInfo）

## V1.1（SHOULD）

SHA-256 去重（本地历史：sha → 最新远程路径）、`auto/YYYY/MM` 自动归类、上传历史面板、上传成功 `refresh:true`、仅 Wi-Fi 开关、Release 更新检查、R8 混淆

## V1 不做（WON'T）

下载回本地 / 远端写操作（删改移）/ Crypt 直传 vault / 断点续传（整文件重试）/ 多服务器 / 应用内更新通道 / 多模块 / Clean Architecture 用例层

## 里程碑

- **M0 CI 闭环**：仓库 + 骨架 + push→APK→Release 跑通（本提交）
- **M1 协议层 + 设置页（本提交）**：`RemoteStorage` 接口缝 + `OpenListClient` + 设置页（失焦保存/掩码/连接）+ 协议回归测试
- M2 双栏 UI（本地浏览 + 远程浏览 + 聚焦/展开 + 面包屑）
- M3 多选 + 上传队列 + 前台服务 + 进度（MVP 可用）
- M4 V1.1（去重 / 归类 / 历史）

## 密码体系（重复以示重要）

App 只持 OpenList 专用 chroot 账号（base_path=/misc，最小权限位），密码 Keystore 加密存本机；与 Crypt password/salt（天翼密钥体系）完全无关，永不接触。
