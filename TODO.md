# TODO / 会话交接

> 更新: 2026-10-04 · 供换会话/换机器后快速续接。
> 阅读顺序: AGENTS.md(纪律与坑) → DESIGN.md(拍板表) → 本文件(现状与队列)。
> 决策史: 私有仓 tianyi-misc-repo `docs/`(协议/架构拍板依据)。

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
6. OpenList 后台: 核对 misc-uploader 账号实际权限位(DESIGN 记录 255 与"走 WebDAV"自相矛盾,已无关本 App 但待澄清)
7. Notion 侧已就绪(顶层双库 + Last Update 视图 + keystore 容灾页),无需动作

## 待办 — 开发队列(按推荐顺序)

1. **A1 SHA-256 去重 + 上传历史**(下一项,已拍板方向): 上传前流式 hash(状态机 hashing 已预留)、Room 历史表(sha→最新落点)、同内容跳过并提示已有路径;语义对齐桌面端(内容级去重、上限一万条)
2. **文件查看/打开**(2026-10-04 用户新需求): 本地文件 `ACTION_VIEW` + FileProvider(root-path 全盘)+ 扩展名 MIME;远程文件先经 `POST /api/fs/get` 拿 raw_url(或 WebDAV GET)下载到缓存再打开;内置图片大图/txt 查看器列为二期
3. **C 归档状态可见**: misc-sync.py 每轮写状态文件 + App 队列页卡片("已归档/待归档 N 项");需拍板状态文件位置(`/misc/.sync/` 或独立挂载)
4. **D1 PiGallery2 深链**: 远程目录面包屑旁按钮 → 浏览器打开 PiGallery2 对应路径(约半天)
5. **B misc-uploader 桌面端纯 REST 重构**: 依据 tianyi-misc-repo `docs/client-protocol-decision.md`;建议等实测把 REST 细节验证完再动
6. 设置项补齐: 并发数 / 最大重试 / 仅 Wi-Fi / 默认分享目录
7. **诊断页补课**: README 已宣称"上传日志+退出原因"但**未实现**——需 AppLogger(文件 ring)+ 设置页诊断卡,或先修正 README
8. 低优先: POST_NOTIFICATIONS 运行时请求 / R8 / OkHttp 5.x 评估 / Room DAO 测试(Robolectric) / 启动静默更新检查 / keystore 源文件改名(带应用名,涉及 Notion 容灾页内文同步)
9. 热更新增强(可选): 下载改 WorkManager(杀进程续传)、启动静默检查 + 通知

## 已知坑(全量见 AGENTS.md;Notion 侧见 notion-mcp-setup TROUBLESHOOTING)

- Android: INTERNET 权限必须声明;常量名勿凭训练数据硬写;DB schema 文本列=rich_text;gh paginate 输出 JSON Lines;mkdirp 逐级建目录
- Notion: 移动页面切断集成权限(404);create-view 需 2026-03-11+data_source 模型;code.language 是枚举
- 网络: 三源(gh-proxy/github/api)国内网络可能全挂,更新检查已做明确报错与重试

## 跨会话续接要点

- **本仓任何 push = CI 构建并发布 Release**(监听到 completed,失败拉 log 修复重推——AGENTS.md 硬性流程)
- 真机反馈直接描述现象: 更新/上传错误已带异常类名,可精确定位
- Notion 工具: notion-mcp-setup 仓(token 随私有仓),Last Update 视图/Agent 导出库均已就绪
