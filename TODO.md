# TODO / 会话交接

> 更新: 2026-10-05(root 桥落地;文件查看功能已接线待实测) · 供换会话/换机器后快速续接。
> 阅读顺序: AGENTS.md(纪律与坑) → DESIGN.md(拍板表) → 本文件(现状与队列)。
> 决策史: 私有仓 tianyi-misc-repo `docs/`(协议/架构拍板依据)。

## 文件查看/打开功能(2026-10-04)——代码已落地,待真机实测

**拍板**: 内置查看器 = 图片(全屏 Dialog/双指缩放 1–6x/同目录翻看) + 文本只读(编码识别/256KB 分块);其余委托系统 ACTION_VIEW;hex/ZIP/APK/编辑器不做;先于 A1。

**本会话完成**(提交 = 接线收尾,CI 验证随提交):
- WIP 存档(67b4afe)的 CI 报错清单已全部修复: FileViewer.kt 缺 import(size/height/verticalScroll/rememberScrollState/SelectionContainer/TextButton/mutableLongStateOf) + 委托属性 smart-cast(url/error 先取快照)
- FileViewerDialog 改全屏 Dialog(独立窗口盖底部导航,返回键关闭;判空在 Dialog 外,防空窗挡触摸)
- **接线完成**: HomeVM.openFile(side,item) 单击→FileKind 路由(多选态单击仍是勾选);远程文本打开前 fs/get 预检,>50MB 走 Snackbar 不进查看器(免整文件下载);三个闭包 localFileOf/rawUrlOf/downloadPreview(下载到 cache/remote_preview,文件名=路径 hash 前缀防撞)
- DESIGN.md 已补拍板行;接口缝行补 mkdirp/fileInfo/downloadTo

**真机实测清单(装机后按此测查看功能)**:
1. 本地图片: 单击全屏、双指缩放、左右翻看同目录图片、关闭
2. 远程图片: 直连加载(Coil/raw_url);断连时显示「加载失败」
3. 文本: MT 式 UI(白底行号/头部信息条/点行高亮)、中文与非 UTF-8 编码显示、大文本「加载更多」、>50MB 拒绝提示
4. 视频/其他: 本地单击直接弹系统「打开方式」选择器(MT 同款,不进查看页);远程单击→进度→选择器并自动关页
5. 两侧列表(聚焦单列/非聚焦两列)单击行为一致;多选态单击仍是勾选

## 当前状态(2026-10-05)

- V1 里程碑(M0–M4)+ A2 自动归类 + 应用内热更新 + **root 桥(Android/data 受限目录浏览/上传)**: **全部完成并发布(正式签名,keystore 见 tianyi-misc-repo secrets/android-release-keystore.md)**
- 真机实测: 部分完成——连接 EPERM(INTERNET 权限)与 http 支持已修,**完整实测清单未走完**(见下)
- 最新 Release 以 GitHub Releases 为准(keep-10 自动清理最老版本);latest.json 含 version_code

## 待办 — 用户侧(装机实测清单)

1. 卸载 debug 签名旧版 → 装最新 Release(一次性;之后用应用内热更新)
2. 连接: 公网 `https://…:5245` + 内网 `http://内网IP:5244`(输 http:// 应有红字警示)
3. 多选上传(**含一个文件夹**——验证 mkdirp 修复)与分享接收
4. 自动归类: 设置切"按日期自动" → 分享截图 → 应落 `auto/2026/10`
5. 杀应用重开 → 队列续跑;设置页「应用更新」卡片的检查/下载/安装流程
6. ~~核对 misc-uploader 账号权限位~~ ✅ 2026-10-05: 实际 permission=32767(含移动/删除/WebDAV 全位)——**远程整理不会 403**,顾虑解除
7. 启动检查更新(2026-10-04 落地): 开关在设置「应用更新」卡、默认开;发现新版 → 弹窗询问是否下载安装(**每版本一次**——启动发现时「暂不」记住该版本,之后冷启动仅 Snackbar 提示;主动检查每次都弹);**当前装的版本即最新,正常应无任何提示**;关掉开关冷启动不再请求更新源
8. 远程整理实测(2026-10-04 落地): 右栏长按多选 → 移动到其他目录(源列表刷新、目标出现)与删除(红色确认);根目录长按 auto/ 应提示「受保护」;**报 403 PermissionDenied = OpenList 后台给 misc-uploader 账号补 move/remove 权限位**(DESIGN 记录 255 与实际权限待核对本就在此清单)
9. A1 去重实测(2026-10-04 落地): 传一个文件 → 再传同内容改名文件 → 队列应显示「已跳过：同内容已存在:<原路径>」;队列页「历史」chip 可见记录;**热更新升级(v1→v2 DB)后旧队列不丢、上传正常**(Migration 真机验证)
10. D1 深链实测(2026-10-04 落地): 设置页填 PiGallery2 地址 → 远程栏标题旁出现相册按钮 → 点开浏览器落在当前目录相册页(中文/空格目录名验证编码)
11. 上传设置实测(2026-10-04 落地): 并发改 1 → 重启队列后逐个传;仅 Wi-Fi 开启 → 蜂窝下队列暂停、连 Wi-Fi 自动续跑;分享接收(手动模式)落自定义目录
12. 归档状态卡实测(C,2026-10-05 服务器侧已验证): 装机后队列页卡片应显示「待归档 0 项 · 冷层已有 11 项 · <时间>」;传几个新文件等下一轮 cron(:17)后「待归档」应先升后清零
13. 浏览增强实测(2026-10-05 落地): 类型 chips 过滤(目录恒显示)、排序菜单三字段×升降、多选态顶栏「全选」(右栏受保护 auto 不可全选入)、底栏文件夹/文件统计
14. 队列暂停实测(2026-10-05 落地): 队列页「暂停」→ 当前项传完即停、通知栏显示暂停态;「继续」恢复;杀进程重开仍是暂停态;暂停中入队 → 新任务待命不抢跑
15. 队列取消实测(2026-10-05 落地): 排队中 ✕ = 立即移除;上传中 ✕ = 传输立即中止并从队列消失(不算失败不重试);取消不影响其他在途项
16. root 桥实测(2026-10-05 落地): 左栏进 `Android/data/<浏览器>/` → 首次 Magisk 授权框 → 列出且左栏顶部显「root」徽标 → 多选(含一个子文件夹)上传 → 队列/落点核对(去重管线同样生效:同内容再传应提示跳过);root 不可用/拒绝授权时应报系统限制提示+指路分享接收
17. 检索实测(2026-10-05 落地): ①目录内过滤——工具条放大镜 → 输入即过滤、底栏显"匹配 n / 共 m"、搜索态全选只选中可见项、换目录自动清空、关闭清词恢复;②全局搜索(左栏过滤行尾图标)——关键词+通配符(`*.apk`/`img_???.png`)+类型+时间范围 → 结果页计数/停止 → 勾选"上传所选"/"全部上传"(落点=右栏当前目录或 auto 归类) → 点单行跳转该目录并选中高亮;root 目录下同样可搜可传(无逐条进度,转圈等结果属预期)
18. 更新源偏好实测(2026-10-06 落地): 设置「应用更新」卡三选切换 → 主动检查 → 诊断日志可见"更新源=xx"与命中的源 URL;选「仅直连」后(挂代理)检查应命中 GitHub;选「仅镜像」断网直连源时明确报"无法连接 gh-proxy 镜像"
19. 搜索历史实测(2026-10-06 落地): 发起几次搜索 → 面板时钟图标 → 历史列表最近在前且同词不重复 → 点条目回填关键词(不自动开搜) → 清空生效;杀进程后历史仍在(持久化)
20. 下载前台服务实测(2026-10-06 落地): 检查更新 → 确认下载 → 首次弹 13+ 通知授权(拒绝不影响下载) → 通知栏进度条实时走 → 点「后台下载」收弹窗切到桌面 → 通知继续走、完成后自动弹安装器(或通知"点击安装") → 设置卡进度同显;下载中划掉最近任务进程应存活(FGS);升级过程中下载被系统杀=作废重下属预期
21. Notion 侧已就绪(顶层双库 + Last Update 视图 + keystore 容灾页),无需动作

## AI 助手(2026-10-15)——P0 已落地,待实测

**拍板**: 接入 OpenAI 兼容接口辅助归类/清理;P0=配置+拉模型+选多模态模型+探针验证;红线=AI 只建议不执行、默认关。

**本会话完成**: `core/AiClient.kt`(listModels/chatText/chatVision/verifyVision,OpenAI 兼容,IO 调度器)、`core/AiModels.kt`、`SettingsRepository` AI 段(密钥 Keystore 加密)、`SettingsViewModel` AI 段、设置页 `AiAssistantCard.kt`;单测 `AiClientTest`(MockWebServer 6 例)。DESIGN/README 已同步。

**实测清单(AI 助手 P0)**:
1. 设置页「AI 助手」卡:开开关 → 填接口地址(含 `/v1`)+API Key → 「获取模型」出列表 → 选一个多模态模型
2. 「验证多模态」:视觉模型应显示「该模型可处理图片 ✓」;选纯文本模型应「验证失败」(证明探针有效)
3. 密钥:填后失焦即存;重进设置页掩码回显「••••••••」;「显示」可取回真值;改地址/模型失焦即存
4. 错误路径:地址错/密钥错 → 「获取模型」明确报错(HTTP 4xx 原文片段),不静默
5. 默认关:不开启时诊断日志无任何 `ai` 打点(未联网)

**P1 已落地(2026-10-15)**: 本地多选 → 上传确认框「AI 建议落点」——`data/AiSuggest.kt`(纯函数提示词+解析,`AiSuggestTest` 覆盖)、`core/ImageThumb.kt`(图片缩略图 512/q70)、`AiClient.chatVision` 改多图、`HomeViewModel.requestUploadSuggestion`、`HomeScreen` 确认框勾选采用(`uploadSelected(targetOverride)`)。**只建议**:勾选「改用此目录」才生效;自动归类模式不适用。

**P1 实测清单**: ① 选几个图片 → 上传确认框 → 「AI 建议落点」→ 出建议目录 → 勾选「改用」→ 执行上传落点正确;② 选纯文档 → 走纯文本(无图)路径;③ 未配置 AI → 明确报「未配置 AI 助手」;④ 建议失败不阻断上传(仍可正常传当前目录)

**顺带修复(2026-10-15)**: 首次「去授权」返回后左栏授权卡片不消失 → `HomeViewModel.onResumed()` + `HomeScreen` ON_RESUME 复检(刚授权则刷新左栏)

**修复(2026-10-15,二)**: ① `uploadSelected`/`uploadSearchResults` 的文件夹遍历/建 task 移到 `Dispatchers.IO`(大目录不再卡 UI/ANR);② 存储权限:Manifest 补 `READ_EXTERNAL_STORAGE`(maxSdk 32),`recheckStoragePermission` R 以下改看该权限(之前恒 false),授权卡 R 以下走运行时请求并加「重检」

**P2 已落地(2026-10-15,本地清理建议)**: 左栏底栏「AI 清理」→ `AiSuggest.cleanupPrompt/parseCleanup`(纯函数,`AiSuggestTest` 覆盖) 审查当前目录(文件名+图片缩略图) → 建议清单弹框(勾选) → `applyCleanup` 预选;本地多选新增「删除」→ 强确认 → `deleteSelectedLocal` **软删除**到 `<存储根>/.misc-trash/<时间戳>/`(可移回,不立即释放空间)。红线:AI 永不自动删;解析只认真实文件名。

**P2 实测清单**: ① 进一个乱目录 → 「AI 清理」→ 出建议+理由 → 取消勾选部分 → 「选中这 N 项」→ 左栏多选态;② 「删除」→ 强确认 → 文件消失、`/.misc-trash/<时间戳>/` 下出现;③ AI 未启用/未配置 → 明确报错;④ 不点删除时绝不自动删

**回收站已落地(2026-10-15)**: 本地栏顶栏「回收站」图标 → `scanTrash` 列批次(`.misc-trash/<时间戳>/`,**镜像原目录**) → 勾选**恢复**回原位置(同名不覆盖,空批次自动清理) / **清空**永久删除(二次确认)。`deleteSelectedLocal` 改为镜像存放,使恢复能回原位。

**回收站实测清单**: ① 删 `/DCIM/Camera/A.jpg` → 顶栏回收站 → 列表显示 `DCIM/Camera/A.jpg` 与批次时间 → 「恢复选中」→ 回到 `/DCIM/Camera/`;② 恢复时原位已有同名 → 报「失败(同名或已缺失)」不覆盖;③ 「清空」→ 二次确认 → `.misc-trash` 清空;④ 空批次(全部恢复后)不再显示

**修复(2026-10-15,三)——「验证多模态」报 400**: 现象 `HTTP 400 {"error":{"message":"invalid image base64 content"}}`。**根因**: 探针用的 **1×1 灰度+透明 PNG(colortype 4)** 被后端拒绝(与体积无关)。**已对真实端点实测**确认: 1×1 灰度 PNG → 400;标准 **RGBA** PNG(1×1 至 64×64 均可)与 JPEG → 200;不带 `data:image/...;base64,` 前缀的裸 base64 → 400。**修复**: `PROBE_PNG_BASE64` 换成 64×64 纯红 RGBA PNG。**教训**: 探针图片必须用标准 RGBA/真彩 PNG,别用极简灰度 PNG。

**远程诊断日志(2026-10-15)**: 设置页「诊断」卡新增「**上传到服务器**」——`RemoteLogUploader`(data) 复用已有 OpenList 连接,把脱敏快照(上传日志全文 + 版本 + 设备/系统 + 退出原因)上传到 `<远程根>/_logs/install-<installId>/diagnostic-<yyyyMMdd-HHmmss>.txt`,返回远程路径可复制。**动机**: 对齐 notion-app-android,排查者直接从服务器取回,免手动复制粘贴。

**修复(2026-10-15,四)——「AI 未给出清理建议」**: ① `parseCleanup` 增强——逐行先剥列表标记(`-`/`*`/`•`/`1.`/`1、`/`1)`)与成对引号/反引号/markdown 粗体再匹配,兼容更多模型输出;② 区分「模型说无需清理」(`saysNothingToClean` → 「AI 认为该目录无需清理」)与「解析失败」(提示去看诊断日志);③ **AI 原始回复写入上传日志**(`HomeViewModel` 注入 `AppLogger`),清理建议与落点建议都记。

**远程日志实测清单**: ① 设置 → 诊断 → 展开 → 「上传到服务器」→ 显示「已上传：/_logs/install-xxxxxx/diagnostic-...txt」;② 「复制路径」拿到路径;③ 服务器上 `<远程根>/_logs/install-xxxxxx/` 下确有该文件且内容含上传日志;④ 未连接时会自动用已存配置登录;⑤ 未配置服务器连接 → 明确报错

**AI 归档(2026-10-15 重设计——合并原 P1 落点建议 + P2 清理建议)**: 用户反馈「AI 清理」方向不对——要的是「AI 帮我判断该传到仓库哪里,采纳后建上传任务,传完进待清理或立即清理」。落地: ① 底栏「AI 清理」→「**AI 归档**」(一个入口);② 选中文件/文件夹 → `AiSuggest.prompt`(仓库目录树 + 当前远程目录 + 展开后的内容名 + 最多 4 张缩略图) → 模型按**现有仓库结构**给落点;③ 文件夹**递归展开**,**深度 > 7 层**或**文件数 > 200** → 拒绝分析并提示;④ 弹框选「上传后:进待清理 / 立即清理」→「采纳并上传」= `uploadSelected(targetDir, cleanupAfter)`;⑤ `upload_items` 加 `cleanupAfter`(DB v2→v3 手写 Migration) + 新表 `pending_cleanup`;⑥ `UploadService` 在 `done`/`skipped`(去重命中)后调 `UploadRepository.afterUploaded` → 立即清理 = `LocalTrash.moveToTrash` 软删除到回收站,进待清理 = 落表;⑦ 队列页新增「**待清理**」页签(清理/全部清理/忽略);⑧ 软删除统一抽到 `data/LocalTrash`。**红线调整**: 删除要么由用户在归档弹框显式选「立即清理」,要么在待清理列表手动确认——AI 自身永不删。**移除**: 上传确认框里的「AI 建议落点」(被归档取代)、旧「AI 清理建议」弹框与 VM 逻辑(`AiSuggest.cleanupPrompt/parseCleanup` 保留 + 单测,留作后续「AI 挑待清理」用)。

**AI 归档实测清单**: ① 选中几张图 → 「AI 归档」→ 出建议落点 + 规模;② 选一个含子目录的文件夹 → 展开正常,深度 >7 层 → 提示拒绝;③ 选「进待清理」→ 采纳并上传 → 传完在 队列页→待清理 出现;④ 选「立即清理」→ 传完本地原件消失、出现在回收站;⑤ 去重命中(重复传同一文件)→ 也进待清理且标注「已在仓库」;⑥ 待清理页「清理选中」→ 进回收站;⑦ 「忽略选中」→ 只移出列表,本地文件还在;⑧ AI 未启用/未连接 → 明确报错不静默;⑨ 不采纳时绝不上传

**修复(2026-10-15,五)——「AI 归档半天没反应」**: 用户上传日志(16:09:43 后**无任何 ai/http 记录**,请求根本没跑)。**根因**: 「AI 归档」按钮错放在底栏**非多选态**分支,而功能要求有选中项 → 点击后 `archiveOpen=true` 但 `requestArchiveSuggestion()` 因 `selected.isEmpty()` 直接 return → 弹出一个**空弹框**(loading/plan/error 全 null,`when` 无 else 分支渲染空白)。**修复**: ① 按钮移到**多选态**底栏(`[上传] [AI 归档] [删除]`,左栏);② `selected` 为空时给出明确提示;③ 弹框补 `else` 分支;④ **加阶段进度**: `aiArchiveStage`(分析本地内容 / 读取仓库目录 / AI 分析中)显示在弹框内;⑤ **全流程打点**(开始/展开结果/候选目录数/完成用时/失败),下次可从诊断日志直接看出卡在哪;⑥ `expandSelection` 加**遍历条目上限 5000**(超大树不再无限走)。

**AI 归档进度/卡点实测**: ① 多选后才出现「AI 归档」;② 点击后弹框依次显示「分析本地内容… → 读取仓库目录… → AI 分析中…」;③ 诊断日志可见「归档分析开始/展开 N 个文件/候选目录 M 个/完成用时 Xs」;④ 不选中时点不到该按钮

**修复(2026-10-15,六)——弹框没显示完整云端路径,容易传错**: 实测日志(16:21)选中文件夹 `ai`(内含 1 个 zip),AI 建议 `/ai`,弹框只显示「建议归档到：/ai」——但按「文件夹连同文件夹名放入目标目录」的规则,实际落地是 **`/ai/ai/kelivo_backup_….zip`**(`入队 → /ai`,`开始 … → /ai/ai/…`)。用户看不出最终落点 → 易传错。**修复**: ① `ArchivePlan` 增 `samplePaths`(≤5 条最终完整路径);② 弹框改为「建议归档目录：/ai」+「**上传后的完整路径(共 N 个文件)：**/ai/ai/xxx.zip…」(超 5 条显示「… 等 N 个文件」);③ 手动上传确认框补一行说明「文件夹会连同文件夹名一起放进该目录(例:目标 /ai + 文件夹 ai → /ai/ai/…)」。

**完整路径显示实测**: ① AI 归档弹框能看到每个文件最终完整路径;② 文件夹多文件时列 5 条 + 「… 等 N 个」;③ 手动上传确认框有同名叠加说明

**改语义(2026-10-15,七)——文件夹按「内容」展开,不再叠加文件夹名**: 用户拍板(承接上条:`/ai` + 文件夹 `ai` → `/ai/ai/…` 多一层)。**改动**: ① `expandSelection` 文件夹展开改用 `child.relativeTo(f).path`(不再 `"$name/$rel"`)→ 内容直接落进 AI 给的目标目录(例:选 `ai`、AI 给 `/ai` → `/ai/里面的文件`);② `ExpandedSelection` 增 `folderNames`,提示词新增「所选文件夹：ai」+「文件夹的内容会直接放进你给出的目录,不要再多套一层同名文件夹」(SYSTEM_PROMPT 同步加一句);③ 单测补 `prompt carries selected folder names and no-extra-layer rule`;④ 文档 AI-ASSISTANT 同步。

**新语义实测**: ① 选文件夹 `ai`(内含 1 个 zip)、AI 给 `/ai` → 弹框「完整路径」显示 `/ai/kelivo_….zip`(不再 `/ai/ai/…`);② 选文件夹含子目录 → 子目录结构保留(`/目标/子目录/文件`);③ 多文件夹同时选中 → 内容合并进同一目标目录(注意同名会互相覆盖)

**改体验(2026-10-15,八)——弹框关掉=后台继续,可取消**: 用户问「点空白处消失是取消还是后台继续?大文件夹该不该后台跑」。**原行为**: 点空白处 `onDismissRequest` → `dismissArchive()` 只清 UI 状态,**协程仍在 viewModelScope 里跑**(不是取消),但结果没人展示 → 「既没取消也看不到」最差组合。**改动**: ① VM 增 `aiArchiveOpen`(弹框开关,与后台分析解耦)+ `archiveJob`(可取消);② `closeArchiveDialog()` 只关弹框(分析继续、结果保留),`cancelArchive()` 才真取消并打日志;③ `requestArchiveSuggestion()` 幂等:在分析中/已有建议 → 只打开弹框不重复请求(省 token);④ 完成/失败时若弹框已关 → `events.emit` 弹提示「建议已就绪」;⑤ 弹框 loading 态:说明文案「可点空白处关闭,分析会在后台继续」+ 主按钮变「**取消分析**」+ 副按钮变「**后台运行**」;⑥ 底栏按钮三态:普通「AI 归档」/ 转圈「分析中…」/「AI 归档 · 建议就绪」。

**后台继续实测**: ① 分析中点空白处 → 弹框关、底栏转圈,完成后弹「建议已就绪」且按钮变「AI 归档 · 建议就绪」→ 再点直接看到结果(不再重新分析);② 点「取消分析」→ 立即停止(日志「归档分析已取消」);③ 分析中反复点按钮 → 不会重复请求

**修复(2026-10-15,九)——`归档分析失败：响应缺少 message.content`(日志实证)**: 20:14 三次分析里两次失败(HTTP 200、4476/2336 字符但无 content)。**根因**: 推理模型(sensenova-6.8-flash-lite)把「思考」也算进 `max_tokens`,原来只给 **1024** → 思考占满额度、`content` 字段缺失/为空,而 `AiClient` 只认 `message.content`,直接抛错白跑。**修复**: ① `max_tokens` 1024 → **4096**;② 解析加回落链:`content` → `reasoning_content`(→ `reasoning`)→ legacy `choices[].text`,取不到才报错;③ 取不到时把**响应体前 400 字符写日志**(原来只有一句错误,无法定位);④ 抽 `textOf()` 兼容字符串/null/多段 `[{type,text}]`;⑤ 顺带修 `OpenListClient` 把 `CancellationException` 当网络异常记日志+包装的问题(改原样抛出,日志不再刷「请求异常 JobCancellationException」);⑥ `AiClientTest` 补 4 个用例(null/缺 content 回落 reasoning、legacy text、无文本报错)。

**AI 解析实测**: ① 推理模型只回思考时也能拿到路径;② 真取不到时日志里有响应体片段可定位;③ 上传完成后日志不再出现 JobCancellationException「请求异常」

## 待办 — 开发队列(按推荐顺序)

1. ~~A1 SHA-256 去重 + 上传历史~~ ✅ 2026-10-04: 内容级去重(同内容任意目录跳过+提示已有路径,PUT 前流式 hash/「校验中」状态)、Room upload_history(sha→最新落点,上限一万自动修剪)、队列页「历史」chip;DB v1→v2 Migration;**顺带修 M3 队列排序 bug(partition 解构颠倒)**——真机实测见下节
2. ~~C 归档状态可见~~ ✅ 2026-10-05 全链路落地: app 侧卡片 + misc-sync.py patch 已部署 tencent-01(备份 .bak-20261005,两轮手动验证 .sync 排除生效,OpenList fs/get 200)——装机后卡片即活,实测见下节 12
3. ~~D1 PiGallery2 深链~~ ✅ 2026-10-04: 设置页填相册地址(可选) → 远程栏相册按钮直达当前目录;URL 格式对照 pigallery2 源码核实(`/gallery/<%2F 编码路径>`);真机实测见下节
4. **app 内回收站**(远程整理增强): 移入 .trash + Snackbar 撤销;等服务器侧拍板 .trash 位置与 misc-sync.py 清理/归档协调
5. **B misc-uploader 桌面端纯 REST 重构**: 依据 tianyi-misc-repo `docs/client-protocol-decision.md`;建议等实测把 REST 细节验证完再动
6. ~~设置项补齐~~ ✅ 2026-10-04: 并发数(1–4,默认 2)/最大重试(0–5,默认 3,退避封顶 30s)/仅 Wi-Fi(蜂窝暂停 15s 轮询自动续跑,即时生效)/默认分享目录(手动模式,默认 "/")——设置页「上传设置」卡;并发与重试下次队列启动生效
7. ~~诊断页补课~~ ✅ 已实现(M4): AppLogger 文件 ring(连接/入队/上传/失败/重试全打点)+ 设置页诊断卡(日志+ApplicationExitInfo)——2026-10-04 核实打点齐全,原"未实现"描述过时
8. 低优先: POST_NOTIFICATIONS 运行时请求 / R8 / OkHttp 5.x 评估 / Room DAO 测试(Robolectric) / keystore 源文件改名(带应用名,涉及 Notion 容灾页内文同步)
9. 热更新增强(可选): ~~启动静默检查~~ ✅ 2026-10-04(改为应用内 Snackbar 提示,系统通知因 POST_NOTIFICATIONS 未请求不做);剩 下载改 WorkManager(杀进程续传)

## 已知坑(全量见 AGENTS.md;Notion 侧见 notion-mcp-setup TROUBLESHOOTING)

- Android: INTERNET 权限必须声明;常量名勿凭训练数据硬写;DB schema 文本列=rich_text;gh paginate 输出 JSON Lines;mkdirp 逐级建目录
- **真机网络(2026-10-05 实锤)**: 手机代理/VPN(Clash 类)会让 app 报 ECONNREFUSED(连接与更新源同时挂);关代理即恢复——给服务器 IP 加 DIRECT 规则可共存;地址必须带 :5245(缺端口连 443 被拒)
- Notion: 移动页面切断集成权限(404);create-view 需 2026-03-11+data_source 模型;code.language 是枚举
- 网络: 三源(gh-proxy/github/api)国内网络可能全挂,更新检查已做明确报错与重试

## 跨会话续接要点

- **本仓任何 push = CI 构建并发布 Release**(监听到 completed,失败拉 log 修复重推——AGENTS.md 硬性流程)
- 真机反馈直接描述现象: 更新/上传错误已带异常类名,可精确定位
- Notion 工具: notion-mcp-setup 仓(token 随私有仓),Last Update 视图/Agent 导出库均已就绪
