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
| 接口缝 | `RemoteStorage` 接口（login/list/mkdir/mkdirp/upload/fileInfo/downloadTo），V1 仅 OpenListRestClient 一个实现（迁离 alist 系时补 WebDAV 实现） |
| 上传队列 | 前台服务（dataSync 类型）+ **Room 唯一真相源**（UI 与服务同进程，靠 Room Flow 对齐）；状态机沿用桌面端 hashing/pending/uploading/cooldown/done/skipped/failed + finished_at；并发/重试退避 2s/8s/30s 可配 |
| 进度 | 客户端字节计数（ProgressRequestBody），协议无关 |
| 网络 | kotlinx.serialization 1.7.3 + **OkHttp 4.12.0**（M1 实钉；原拍板写 5.x，stable 坐标未验证，API 同构，升级留 V1.1 评估）；不上 Retrofit（全 app 4 个端点） |
| DI | Hilt（KSP） |
| 持久化 | Room（队列+历史）+ Preferences DataStore（设置）；密码 = Keystore AES-GCM 包 DataStore（androidx security-crypto 已废弃不用） |
| 密码框语义（M1 建，2026-10-04 实测修订） | **已存密码以掩码值回显（•••••••• 作为字段值）**，聚焦全选、输入即替换（对齐桌面端哨兵语义）；清空 = 沿用；「显示」取回真实密码、「隐藏」回掩码值；UI 暂无删除密码入口（边缘需求）。原 placeholder 方案被实测推翻（视觉像空字段，看不出已存数据） |
| 地址归一化（M1） | 无 scheme 自动补 https://、去尾部斜杠（桌面端要求手输完整 URL，移动端放宽） |
| 连接测试（M1） | 「连接」= 保存 + login + list("/") 一步，已连接卡片显示根目录项数；启动时配置齐全自动连接（MiscApp → ConnectionManager） |
| 协议回归测试（M1） | `OpenListClientTest`（MockWebServer，9 例）：token 透传 / 401 重登一次 / 防无限循环 / File-Path 编码还原 / 流式+进度 / Overwrite 头 / 200+HTML 假成功防御 / 403 同名闸门——质量门从空转变为真门 |
| 底部导航（M2） | 文件/队列/设置 三 tab，`rememberSaveable` 状态切换，**不上 navigation-compose**；页数据在 ViewModel 切 tab 不丢，列表滚动位置不保留（已知边界） |
| 存储权限（M2） | `MANAGE_EXTERNAL_STORAGE` + `isExternalStorageManager()` 检测；未授权时左栏显示授权卡片（去授权/重检）；R 以下(minSdk 26~28)视为已授权、由 listFiles 报错兜底（用户设备 R+，已知边界）；`Android/data` 系统限制不可读（root 桥回落解决，见 2026-10-05 行） |
| 双栏交互（M2） | 左=本地右=远程；触摸聚焦（聚焦指示条 + 底部操作条高亮）；聚焦侧单列 / 非聚焦侧两列 Grid；展开动画 = weight 400ms tween（9.9/0.1 近似 SplitLanzou 的 1px 压缩），展开态边缘把手恢复双栏；面包屑 + 列表首项「..」双导航；单侧刷新按钮 |
| 远程浏览边界（M2） | 浏览一律 `refresh=false`（避免频繁刷 OpenList 目录缓存；上传后的定向刷新在 M3）；`per_page=1000` 单页，total>1000 的目录极少见（加载更多留 V1.1） |
| 列表排序（M2） | 目录优先 + 名称不区分大小写升序，本地/远程一致；文件单击 M2 无操作（M3 接多选/上传） |
| 上传队列（M3） | **Room 唯一真相源**（upload_items 表）；状态机沿用桌面端 pending/uploading/cooldown/done/failed/skipped + finished_at（hashing 留给 V1.1 去重）；进行中按入队序置顶、完成按完成时间最新在前 |
| 前台服务（M3） | dataSync 类型 + 常驻通知（IMPORTANCE_LOW，含进度文本）；并发 worker 固定 2（可配置化留 V1.1）；claim 用 Mutex 串行（单进程足够）；失败退避 2s/8s/30s 共 3 次（沿用桌面端），冷却在 worker 内 delay；服务启动时把 uploading/cooldown 重置 pending（进程被杀续跑）；START_NOT_STICKY + MiscApp 启动 resumeIfPending |
| 上传行为（M3） | 流式 PUT（内存恒定）；overwrite=true（M3 桌面端同语义，403 同名闸门留给 V1.1 去重）；文件夹递归展开入队保留内部结构（拍板沿用桌面端）；逐级 mkdir best-effort 忽略"已存在"错误（对齐 misc-sync.py）；上传成功后对目标目录 list(refresh=true) 触发 OpenList 增量索引 |
| 多选（M3） | 仅本地侧可选（长按进入、单击切换、checkbox）；换目录即清空（不跨目录保留）；上传前确认框明示目标完整路径（对齐桌面端"来源去向可见"） |
| M3 已知边界 | 分享接收（ACTION_SEND）移至 M4；诊断页（日志/ApplicationExitInfo）随 M4 一起；DAO 无单元测试（需 Robolectric/仪器，V1.1 评估）；Android 13+ 通知权限未主动请求（前台服务仍运行，可系统设置授予） |
| 分享接收（M4） | SEND/SEND_MULTIPLE + `*/*`，MainActivity singleTask + onNewIntent；**content:// 先拷贝到 app cache 再按文件入队**（队列/重试逻辑不变；成功后的孤儿缓存由启动清理回收）；目标统一 = 仓库根目录 `/`（默认目录设置项留 V1.1）；未连接时任务排队，连接成功自动恢复 |
| 诊断（M4） | **诊断能力下沉**（零本地环境的排查窗口）：AppLogger 文件 ring 日志（filesDir/diagnostics/upload.log，256KB 截半，只存本机）打点入队/上传/失败/重试/连接；`ApplicationExitInfo` 最近 5 次退出原因（Android 11+，识别厂商杀后台）；设置页底部诊断卡展开查看 + 复制全部 |
| root 桥（2026-10-05） | 左栏 `File.listFiles()` 失败时回落 su 列目录（**libsu 5.2.2** 常驻会话；首次触发 Magisk 授权框，拒绝后本进程内不再重试）；多选上传 = 逐文件 `cat` 拷 cache/root 后入队（与分享路径同构；单文件失败跳过不中断整批）；自动归类按 ls/stat 解析的 mtime（解析失败落回当下，同分享语义）。背景：「所有文件访问」不覆盖其它 app 的 Android/data、Android/obb，SAF 树授权 Android 13+ 被封，root(uid 0) 是唯一无系统版本分岔的通路。已知边界：文件名含换行不可读；symlink 一律按文件；0 字节文件跳过；大目录逐文件拷贝慢（FUSE 双份 IO，走 /data/media 绕行留优化）；左栏 root 目录显示「root」徽标 |
| 上传模式（A2） | 设置项：手动目录（默认，现状）/ 按日期自动；**自动 = 按每个文件自身 mtime → `auto/yyyy/MM`**（本地时区），文件夹内部结构保留在月份目录下、多 mtime 会跨月（桌面端同语义）；分享接收在自动模式按**分享时刻**归类（content 无可靠 mtime）；手动模式行为不变 |
| 入队任务制（A2） | `enqueue(tasks: List<UploadTask>)`——目标目录规划全部在入队侧完成（VM/分享），服务只管按 remotePath 上传；`UploadPlanning.autoDirFor/joinRemotePath` 单测覆盖（含时区固定） |
| 修正（A2 顺带） | **M3 遗留 bug**：服务上传前未建远程目录，文件夹上传/auto 目录必失败 → `RemoteStorage.mkdirp`（逐级 mkdir 忽略"已存在"，misc-sync.py 同款），服务在 PUT 前调用 |
| 明文连接策略（实测反馈拍板） | 平台默认禁 http 挡住了合法内网场景（5244 iptables 只对内；LE 证书只覆盖公网 IP，内网 IP 无证书可验；桌面端本就支持 http）。拍板：**允许 cleartext（usesCleartextTraffic=true）**，但无 scheme 仍自动补 https、显式输 `http://` 时设置页红字警示「仅建议可信内网」——选择权还给用户；公网仍按安全模型走 5245 https |
| 更新源偏好（2026-10-06 拍板） | 背景：gh-proxy 是**第三方公共镜像**，可篡改同源返回（latest.json 内的 sha256 与 APK 同源时，SHA-256 校验对镜像内篡改无效），用户要求可控。设置「应用更新」卡三选：**自动·镜像优先**（默认，清单链=镜像→直连，下载对=镜像/直连，API 兜底可用）/**仅直连**（不经镜像，完整性最优）/**仅镜像**；偏好同时约束清单链、下载对、API 兜底（仅镜像时 API 也不用）；错误文案按偏好区分。源链为 UpdateService companion 纯函数（`manifestChainFor/downloadPairFor/apiFallbackFor`）单测覆盖；DataStore 持久化，下次检查即生效 |
| 应用内热更新（2026-10-04 拍板，从 WON'T 移出） | 移植 ncm-cloud-player 同款：设置页「检查更新」→ latest.json 双源检查（gh-proxy 优先/GitHub 回退，GitHub releases API 兜底）→ versionCode 比较 → APK 下载（进度）→ SHA-256 校验 → FileProvider + 系统安装器；REQUEST_INSTALL_PACKAGES 权限 + 未授权时跳「安装未知应用」设置。**简化差异：下载在 VM 作用域（ncm 用 WorkManager），杀进程需重新下载**；发现新版 → **统一 AlertDialog 确认「下载并安装/暂不」**（主动检查每次弹；启动发现每版本弹一次；「暂不」后仍可点卡片内按钮）；**确认后弹窗原地转下载进度条（「后台下载」可收起，设置卡同显）→ 下载完成自动拉起系统安装器**（卡片「安装」键保留为恢复路径：未授权「安装未知应用」时自动跳授权页，授权返回后点按钮继续；2026-10-04 拍板） |
| 启动检查更新（2026-10-04 拍板） | 设置「应用更新」卡内开关（**默认开**，DataStore 持久化）。开启时启动延迟 3s 静默检查（UpdateCheckManager 单例持有结果，避让自动连接；复用 UpdateService 双源检查）。发现新版本 = **AlertDialog 确认（每版本只弹一次：主动检查「暂不」不记忆、启动发现「暂不」记忆 tag，之后冷启动降级为 Snackbar）**，设置卡片同时直显；不上系统通知（POST_NOTIFICATIONS 运行时请求未接，已知边界）；静默失败只记 logcat 不打扰，手动「检查更新」仍显式报错；**关闭后启动完全不发起更新源请求** |
| 双栏触摸聚焦开关（2026-10-04 拍板） | 设置页「双栏触摸聚焦」Switch，**默认开**。开启 = 原 M2 行为（触摸聚焦：聚焦侧单列/非聚焦侧两列总览）；**关闭 = 两栏恒为单列**，聚焦只剩标题/指示条/操作条颜色变化，点击不再改变布局。起因：真机上左栏自动变两列先后两次被误认为显示异常——聚焦的视觉线索（标题色/2dp 指示线）太弱，留开关让用户自选 |
| 系统返回键（2026-10-04 拍板） | 无 navigation-compose，返回键自研**四级链 + 二次确认**：其他 tab→回文件页 → 单侧展开→收起双栏 → 多选→退出多选 → 聚焦侧子目录→返回上级 → 根目录按返回提示「再按一次退出」（Snackbar，2s 内再按才 finish）。此前返回键 = 无条件直接退出（子目录层级无从返回、误触即退，用户指出不合理）；弹窗（查看器/新建文件夹/上传确认）打开时由 Dialog 窗口优先消费返回键，BackHandler 在 MainScreen 统一注册，逻辑在 HomeViewModel.onBackConsumed() |
| 显示隐藏文件（2026-10-04 拍板） | 设置页 Switch，**默认关**；隐藏规则 = 名称以「.」开头，本地/远程同规则。过滤做在**展示侧**（combine DataStore 流），切换即时生效无需重新拉列表；查看器同目录图片翻页列表同源过滤；列表计数「N 项」按过滤后计 |
| 远程整理（2026-10-04 拍板） | 右栏开放**多选 + 移动/删除**（长按进入，模式同本地多选；换目录即清空）。协议 = OpenList `POST /api/fs/move`（src_dir/dst_dir/names）+ `POST /api/fs/remove`（dir/names）——字段名对照 OpenList 源码 server/handles/fsmanage.go 核实（坑 5 流程）；移动成功后源目录 refresh=true、目标目录预热 list(refresh=true)（对齐上传后语义）。**保护规则：仓库根 auto/ 本体不可选中/移动/删除，也不可作为移动目标**（防散件进归档根），其下文件/子目录可自由整理。删除 = 硬确认框（红标题 + 完整路径清单 + 「文件夹连同内容递归删除」警示）；**app 内回收站（移入 .trash + Snackbar 撤销）留增强**——需先在服务器侧拍板 .trash 位置与 misc-sync.py 清理/归档的协调，删除逻辑届时从 remove 换成 move 即可；误删兜底 = 云盘网页端回收站。**权限前提：misc-uploader 账号需 move/remove 权限位**（报 403 PermissionDenied → OpenList 后台补；账号权限位核对本就在待办） |
| A1 SHA-256 去重 + 上传历史（2026-10-04 拍板） | **内容级去重对齐桌面端**：上传 worker 在 PUT 前流式 hash（64KB 分块，状态机启用 hashing=「校验中」，通知栏同显），**命中历史 = 同内容任意目标目录都跳过**，队列显示「同内容已存在：<路径>」；信任历史不回查远端（桌面端同语义；远端该文件事后被移走则提示路径过时 = 已知边界）。历史 = Room `upload_history`（sha256 索引 → 最新落点，REPLACE 语义），**上限一万条自动修剪**（对齐桌面端）；队列页「队列/历史」FilterChip 切换，历史展示最近 500 条。DB v1→v2 手写 Migration（队列表加 sha256 列 + 建历史表，避免破坏性迁移丢在途队列）；`resetInterrupted`/`activeCount`/通知全部纳入 hashing 状态。**顺带修复 M3 队列排序 bug**：`partition` 解构变量名颠倒导致已完成项反而置顶，与拍板「进行中置顶」相反 |
| D1 PiGallery2 深链（2026-10-04 拍板） | 设置页「PiGallery2 地址（可选）」（DataStore，失焦即存，空 = 不显示按钮）；远程栏标题旁相册按钮 → 浏览器打开当前目录对应相册页。**URL 格式对照 pigallery2 源码核实**（坑 5）：路由式 `/gallery/<相对路径>`，路径中 `/` 编码为 `%2F` 构成单路由段（app.routing.ts galleryMatcher 的 posParams.directory），base 尾部已带 `/gallery` 时自动去重；**相册根 = 热层 /misc 根，路径零映射直用**（私有仓 plan §1：PiGallery2 只读浏览的就是 /opt/misc 热层）。地址为用户运行时配置，不写入任何环境信息 |
| 上传设置补齐（2026-10-04 拍板） | 设置页「上传设置」卡四项：**并发数**（默认 2，服务启动时读取，改动下次队列启动生效）；**最大重试**（默认 3，退避序列仍 2s/8s/30s 超出封顶 30s，`retries <= maxRetries` 判定，0 = 一次失败即终态）；**仅 Wi-Fi**（默认关，worker 网关轮询：非 Wi-Fi 下每 15s 重查、恢复自动续跑，开关**即时生效**，复用预置的 ACCESS_NETWORK_STATE）；**默认分享目录**（默认仓库根，手动模式分享接收目标，失焦即存并归一化；自动归类模式不受影响）。历史默认值与 M3 一致，纯配置化不改变行为。**修订（2026-10-05，用户建议）**：并发/重试从 chips 改 **DropdownMenu 下拉 + 自定义**（省空间；与 HomeScreen 排序菜单同款稳定 API，非实验性；并发预设 1–4、范围 1–16；重试预设 0/1/2/3/5、范围 0–10，非预设值显示「自定义（N）」），repository 取值范围同步放宽 |
| C 归档状态可见（2026-10-05 拍板） | 状态文件 = **`/opt/misc/.sync/state.json`**（用户拍板接受 `/misc/.sync/`；app 账号 base_path=/misc，chroot 视角即 `/.sync/state.json`）。**格式契约**：`{version:1, last_run_at(epoch 秒), pending_count(热层有而冷层无=本轮已排队数), vault_total(冷层文件总数)}`——三个数都是 misc-sync.py 现成算出的。App 侧：队列页顶部「归档状态」卡（数据/读取中/未部署三态 + 刷新按钮，进页自动加载），复用 fileInfo/downloadTo 读取。**服务器侧已部署验证（2026-10-05，用户授权）**：patch 提交私有仓（6a8a1a1：print 后/missing 早退前写 state.json + os.walk 跳过隐藏目录——必须排除 `.sync` 否则状态文件进归档循环）→ tencent-01 备份旧脚本（`.bak-20261005`）→ 上传（md5 一致，py_compile 过）→ 手动跑两轮：state.json 内容正确、第二轮 `local=11` 不含 `.sync`（排除生效）→ OpenList `fs/get /misc/.sync/state.json` 200 且 raw_url 内容正确。**顺带核对账号权限位（存疑清单项）**：misc-uploader 实际 permission=**32767**（0–14 位全开，含移动/复制/删除/WebDAV 管理），DESIGN 原记录"255"过时——远程整理 403 顾虑解除。另发现 `/root/misc-uploader.txt` 密码登录 401（文件过期或密码已轮换；不影响 app，用户手机自配密码），待用户自行核对 |
| 浏览增强（2026-10-05 拍板，对照 MT 菜单分析后） | 四项展示层增强（不动协议/数据）：**类型过滤**——每栏常驻工具条 chips（全部/图片/视频/音频/文档/压缩包/安装包/其他），**目录恒显示不受过滤**（否则过滤态没法导航），与隐藏文件开关叠加；**排序菜单**——名称/大小/时间 × 升降（同字段再点翻转；时间默认新在前；目录恒优先；时间序按 modifiedText 字典序=时间序，本地/远程同格式成立）；**多选态顶栏**——替换面包屑显示 取消/全选/已选 N（全选 = 当前可见条目，右栏剔除受保护 auto；**反选未做**，半栏宽度取舍）；**底部统计**——「文件夹 X 文件 Y」随过滤计。过滤/排序每栏独立记忆（StateFlow，不持久化）。**不做**（对照 MT 分析）：终端/交换窗口/退出项，书签与导航历史进缓办池；~~全局递归搜索~~ → **2026-10-05 检索拍板推翻**（root 桥批量上传场景使其价值成立，见检索行） |
| 检索（2026-10-05 拍板，对照 MT 检索模型分析后） | 两层都做。**第一层·目录内实时过滤**：每栏工具条搜索图标 → 输入行替换 chips 行，输入即过滤（空词=行为不变）；搜索词非空时目录也参与名字匹配（取消"目录恒显示"）；与隐藏/类型/排序叠加，`processEntries` 单条谓词，combine 加至 5 路；selectAll/查看器翻页列表同步传 query（**漏传会选中看不见的条目**）；换目录即清空；底栏显示"匹配 n / 共 m"。**第二层·全局搜索页（MT 同款裁剪）**：范围=当前目录起递归（可关）+ 关键词通配符（`*` `?`，忽略大小写，`NameMatching` 单测）+ 类型 + 时间范围（不限/今天/近7天/近30天）；**仅本地侧含 root 桥**（root 走 `find -iname` 一次性命令无逐条进度；远程栏每层一次 API 不做）；结果页 = 计数 + 停止（普通目录增量扫描每 32 文件回报，root 只能整体放弃）+ **临时多选直接入队**（不动"多选不跨目录"状态机；rel 平铺文件名）+ 点行跳转定位（load 目标目录并选中高亮）；搜索只出文件不出目录。明确不做：正则、文件内容搜索、拼音匹配、~~搜索历史~~ → **2026-10-06 补做**（MT 截图确认其历史面板价值：通配符回填成本高+周期性重复查询；实现 = DataStore 存换行分隔列表，发起搜索即记，**去重置顶 + 上限 20 条**（MT 原版每次输入都记导致 te/kinga/autoja 类前缀重复并存，属缺陷不照抄）；入口 = 搜索面板标题行时钟图标，点条目回填关键词不自动开搜，支持清空） |
| 队列暂停（2026-10-05 拍板） | **队列级**暂停/继续（队列页按钮；不做逐项暂停——状态机复杂度不值）。语义：暂停 = worker 网关不再取新任务（1s 轮询，即时生效），**进行中的任务传完为止**（不做断点续传，避免大文件半途作废重传）。状态存 DataStore **持久化**——杀进程/重启后仍是暂停态，自动恢复队列也尊重暂停。通知栏双态：「暂停中，等当前项完成（xxx）」/「队列已暂停（待传 N 项）」；诊断日志打点暂停/继续。与仅 Wi-Fi 网关叠加（先查 Wi-Fi 再查暂停） |
| 队列取消（2026-10-05） | 在途任务（pending/hashing/uploading/cooldown）行内 ✕ 取消：**排队中的直接删行**；**上传中的 = 删行 + 内存取消标记**（ConcurrentHashMap 集合），worker 进度回调感知后抛 UploadCancelledException 中止——**取消不算失败、不进重试**，行已删故后续状态写自然失效。检查点三处：process 开头 / hash 后 / 上传进度回调（每次回调都查，粒度=分块级）。与暂停互补：暂停=温和（整队等当前完成），取消=单项立即中止 |
| 修正（实测 bug 1） | **M1 遗留：Manifest 漏声明 `INTERNET` 权限**——真机连接报 `socket failed: EPERM`（坑 4）；补 INTERNET + ACCESS_NETWORK_STATE（后者为仅 Wi-Fi 功能预置） |
| 文件查看（2026-10-04 拍板，先于 A1） | **内置查看器只做两类**：图片（全屏 Dialog 盖底部导航/双指缩放 1–6x/同目录图片左右翻看，远程 Coil 直连 raw_url 不落盘）+ 文本只读（juniversalchardet 编码识别、256KB 分块「加载更多」）；**其余类型（视频/PDF 等）委托系统 ACTION_VIEW**（远程先下载 cache/remote_preview 再 FileProvider 打开）；hex/ZIP/APK/编辑器不做。远程文本 >50MB 不进查看器（打开前 fs/get 预检 size，免整文件下载）；单击文件按 FileKind 路由（多选态单击仍是勾选，语义不变）；Dialog 返回键 = 关闭。**修订（2026-10-05）**：本地其他类型**不进查看器**，单击直接弹系统「打开方式」选择器（`Intent.createChooser`，MT 管理器同款，每次都选应用）；远程其他类型仍保留查看器页（唯一作用 = 下载进度），完成后弹选择器并**自动关页**；`openWithSystem` 统一走 chooser（NEW_TASK 挂在 chooser intent 上）。**修订 2（2026-10-05，用户对照 MT 截图）**：文本查看器改 **MT 管理器风格**——深色头部信息条（文件名/编码/行数/关闭，替代原黑底悬浮标题，顺带修掉标题压正文问题）+ 白底黑字 + 行号栏（LazyColumn 每行一行号，软换行）+ 点击行米黄高亮；分块「加载更多」保留；选择改为行内 SelectionContainer（跨行整体选择随虚拟化放弃，查看器场景以单行/单段复制为主） |
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

下载回本地 / 远端写操作（删改移）/ Crypt 直传 vault / 断点续传（整文件重试）/ 多服务器 / 多模块 / Clean Architecture 用例层

## 里程碑

- **M0 CI 闭环**：仓库 + 骨架 + push→APK→Release 跑通（本提交）
- M1 协议层 + 设置页 ✅（2026-10-03）
- M2 双栏 UI ✅（2026-10-03）
- M3 上传队列 ✅（2026-10-03）——MVP 可用
- M4 分享接收 + 诊断 ✅（2026-10-03）——V1 里程碑完成
- **A2 自动归类（本提交）**：上传模式设置项（手动/按日期自动）+ 任务制入队 + mkdirp 修正 M3 文件夹上传 bug
- **文件查看（2026-10-04）**：FileKind 路由 + 图片/文本内置查看器 + 其余委托系统 + 协议层 fileInfo/downloadTo（raw_url host 修正）；真机实测并入用户侧清单
- **root 桥（2026-10-05）**：左栏受限目录（Android/data）su 回落浏览 + 多选拷缓存入队（拍板表 2026-10-05 行）
- **检索（2026-10-05）**：目录内实时过滤（双栏统一）+ MT 式全局搜索页（本地侧含 root 桥，结果直接入队+跳转定位）
- 待拍板/待做：A1 去重+历史 / C 归档状态可见 / B 桌面端重构 / D1 PiGallery2 深链；P0 实测仍欠
- M3 多选 + 上传队列 + 前台服务 + 进度（MVP 可用）
- M4 V1.1（去重 / 归类 / 历史）

## 密码体系（重复以示重要）

App 只持 OpenList 专用 chroot 账号（base_path=/misc，最小权限位），密码 Keystore 加密存本机；与 Crypt password/salt（天翼密钥体系）完全无关，永不接触。
