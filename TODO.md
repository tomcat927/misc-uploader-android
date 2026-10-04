# TODO / 会话交接

> 更新: 2026-10-04(文件查看功能落地接线) · 供换会话/换机器后快速续接。
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
3. 文本: 中文/非 UTF-8 编码显示、大文本「加载更多」、>50MB 拒绝提示
4. 视频/其他: 下载进度→系统应用打开
5. 两侧列表(聚焦单列/非聚焦两列)单击行为一致;多选态单击仍是勾选

## 当前状态(2026-10-04)

- V1 里程碑(M0–M4)+ A2 自动归类 + 应用内热更新: **全部完成并发布(正式签名,keystore 见 tianyi-misc-repo secrets/android-release-keystore.md)**
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
13. Notion 侧已就绪(顶层双库 + Last Update 视图 + keystore 容灾页),无需动作

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
- Notion: 移动页面切断集成权限(404);create-view 需 2026-03-11+data_source 模型;code.language 是枚举
- 网络: 三源(gh-proxy/github/api)国内网络可能全挂,更新检查已做明确报错与重试

## 跨会话续接要点

- **本仓任何 push = CI 构建并发布 Release**(监听到 completed,失败拉 log 修复重推——AGENTS.md 硬性流程)
- 真机反馈直接描述现象: 更新/上传错误已带异常类名,可精确定位
- Notion 工具: notion-mcp-setup 仓(token 随私有仓),Last Update 视图/Agent 导出库均已就绪
