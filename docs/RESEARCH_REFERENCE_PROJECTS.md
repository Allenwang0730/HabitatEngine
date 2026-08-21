# 参考项目调研汇总（Automate / Tasker / MacroDroid / Auto.js）

> 依据 ENGINE_MASTER_PLAN §5：从参考项目整理"值得补进引擎的节点/触发器"清单，标注可行性。
> 调研方式：4 个子代理分别抓取官方文档（Automate 416 页 block 索引 / Tasker 官方 A-Z 索引与更新日志 / MacroDroid 官方 Wiki 97 触发器 / Auto.js 全系文档），再交叉对照 Habitat 现有 67 个节点。
> 日期：2026-08-22

## 0. 关键趋势（先看）

- **Tasker 6.6 官方集成 Shizuku**（Run Shell 支持 Shizuku、权限管理页）——最成熟竞品的最新方向与 Habitat"无障碍 + Shizuku"架构一致，路线正确。
- **Automate 付费版不锁任何块**，免费版仅限 30 并发块；官方 16 类共 416 页（宣传 380+）。
- **Automate 官方也没有**：PDF、SFTP、磁条卡/MIFARE、独立正则块、WebSocket、IMAP、Dropbox、OAuth、独立加密块——这些不是"别人有我们没有"的差距。
- **控制流：Habitat 反而领先**——Automate 没有 if/else、case/switch、loop/while、try/catch 等"语法块"（其控制流只有 Expression true 分支 + Go to/Label + Subroutine + Failure catch）；Habitat 的 CONDITION_SWITCH / CONDITION_ADVANCED_SWITCH / ACTION_LOOP / ACTION_TRY_CATCH 结构上已优于 Automate，无需对齐。
- **事件模型共识**：Automate 用"decision 块 + When changed"、MacroDroid 用系统广播回调；Habitat 是轮询/挂起模型，落地方式 = ①节点加 waitForChange 语义 ②常驻监听服务投递工作流。

## 1. 四项目能力对照（Habitat 缺口）

| 能力 | Automate | Tasker | MacroDroid | Auto.js | 可行性 | 备注 |
|------|:--:|:--:|:--:|:--:|:--:|------|
| Dialog 输入/确认/选择（6 件套） | ✅ | ✅ | — | ✅ | ★高 | 悬浮窗 UI，半自动工作流，**最高优先** |
| SQLite 查询/修改 | ✅ | ✅ | — | — | ★高 | 纯 JVM |
| ZIP 压缩/解压 | ✅ | ✅ | — | — | ★高 | java.util.zip |
| 文件加密/解密 | — | ✅ | — | — | ★高 | javax.crypto AES-GCM |
| TTS 朗读（Say / Say to file） | ✅ | ✅ | — | ✅ | ★高 | 已有 TTS 节点，补参数与合成到文件 |
| 媒体控制（play/pause/next/prev） | ✅ | ✅ | ✅ | — | ★高 | 无障碍 GLOBAL_ACTION_MEDIA_* 或 keyevent 85/87/88/86 |
| 壁纸设置 | ✅ | ✅ | ✅ | — | ★高 | WallpaperManager |
| 系统设置读写（泛化 Custom Setting） | ✅ | ✅ | ✅ | — | ★高 | Shizuku `settings put/get`，一个节点顶暗色/超时/字体等十几个 |
| 飞行模式 | ✅ | ✅ | ✅ | — | ★高 | Shizuku `cmd connectivity airplane-mode` |
| 移动数据开关 | ✅ | ✅ | ✅ | — | ★高 | Shizuku `svc data enable/disable` |
| 发送短信 | ✅ | ✅ | ✅ | — | ◐中 | SEND_SMS（Play 敏感权限） |
| 图像处理管线（裁剪/缩放/旋转/灰度） | ✅ | ✅ | — | ✅ | ★高 | Bitmap 纯计算 |
| OCR 文字识别 | ✅ | 插件 | ✅ | ✅ | ◐中 | ML Kit / 云 API / 离线模型（LiteRT 已有推理栈） |
| 日期时间解析/格式化 | — | ✅ | — | — | ★高 | java.time |
| 数组/多变量操作 | ✅ | ✅ | — | ✅ | ★高 | 引擎内部纯逻辑 |
| 屏幕状态（On/Off/解锁） | ✅ | ✅ | ✅ | ✅ | ★高 | PowerManager/KeyguardManager |
| 耳机插拔/音乐播放状态 | — | ✅ | ✅ | — | ★高 | AudioManager 直读 |
| 广播发送/接收 | ✅ | ✅ | — | ✅ | ★高 | Intent + 动态 receiver |
| ContentProvider CRUD / 日历/联系人 | ✅ | ✅ | ✅ | — | ★高 | ContentResolver，一次补齐 |
| 天气（Open-Meteo 免 key） | ✅ | 插件 | ✅ | — | ★高 | 内部走 HTTP 节点 |
| 地理编码/逆地理编码 | ✅ | — | — | — | ★高 | Nominatim 纯 HTTP |
| 媒体元数据（EXIF/时长/码率） | ✅ | — | — | — | ★高 | MediaMetadataRetriever |
| 屏幕超时/强制旋转/深色模式 | ✅ | ✅ | ✅ | — | ★高 | 走泛化 Custom Setting 即可 |
| QR 生成/条码识别 | ✅ | — | — | — | ◐中 | 生成 ZXing ★高 |
| FTP 上传下载 | ✅ | — | — | — | ★高 | Commons Net（SFTP 用 SSHJ 自扩） |
| 热点开关 | ✅ | ✅ | ✅ | — | ◐中 | 版本差异大，需降级链 |
| NFC 标签读写 | ✅ | — | ✅ | — | ◐中 | 需屏幕亮 |
| STT 语音识别 | ✅ | ✅ | — | — | ◐中 | 后台受限，需"屏幕亮"降级 |
| 地理围栏 | ✅ | ✅ | ✅ | — | ◐中 | Geofencing 或轮询 GET_LOCATION |
| 通知点击/快捷回复 | ✅ | — | ✅ | ✅ | ◐中 | 已有 READ_NOTIFICATIONS，补交互半环 |
| 悬浮按钮/Quick Settings 磁贴（入口） | ✅ | ✅ | — | ✅ | ★高 | 新触发器入口 |
| Process text（接收选中文本） | ✅ | — | — | — | ★高 | intent filter，实现简单 |
| 屏幕方向/铃声模式/勿扰策略细分 | ✅ | ✅ | ✅ | — | ★高 | 与 DND 互补 |
| 地理编码/定时器（系统闹钟/倒计时） | ✅ | — | ✅ | — | ★高 | AlarmManager |

## 2. 触发器（事件/状态）候选

Habitat 现有：notification / sms / timer / clipboard / key（5 个）。交叉共识的高价值补充：

### 事件（一次性触发，多为广播/无障碍事件）
| 触发器 | 实现 | 可行性 |
|--------|------|:--:|
| 屏幕亮/灭/解锁 | ACTION_SCREEN_ON / USER_PRESENT | ★高 |
| 充电器插拔 / 电量变化 | POWER_CONNECTED 等广播 | ★高 |
| WiFi 连接/断开 | ConnectivityManager 回调 | ★高 |
| 蓝牙设备连接 | ACTION_ACL_CONNECTED | ★高 |
| 耳机插拔 | ACTION_HEADSET_PLUG | ★高 |
| 前台应用变化 / 新窗口 | 无障碍 TYPE_WINDOW_STATE_CHANGED | ★高 |
| 通知点击/移除 | 已有 NLS 扩展 | ★高 |
| 应用安装/卸载/更新 | PACKAGE_ADDED 等广播 | ★高 |
| 开机 / 关机 | RECEIVE_BOOT_COMPLETED | ★高 |
| 时间/日期/时区变化 | TIME_CHANGED 等 | ★高 |
| 摇一摇 / 传感器阈值 | SensorManager 监听 | ★高 |
| 来电状态（响铃/摘机/挂断） | READ_PHONE_STATE（已有 IncomingCallService 基础） | ★高 |
| 变量变化 | 引擎内部 | ★高 |
| Intent Received（通用广播） | 动态 receiver | ★高 |
| 短信收发 | RECEIVE_SMS 广播 | ◐中 |
| 新未接来电 | READ_CALL_LOG 轮询 | ◐中 |
| 日出日落（纯计算） | 离线算法（Tasker 6.6 Get Sunrise/Sunset） | ★高 |

### 状态（持续性条件，供 CONDITION 节点）
Wifi Connected(SSID)、电量区间、充电中、屏幕状态、飞行模式、VPN、省电模式、耳机、信号强度、传感器区间、前台应用、Shizuku 可用、工作流运行中——均为直读/轮询，★高。

### 需要单独规划的（引擎级）
- **定时/周期触发器**（Time/Day/Location 上下文）：cron 式调度器 + AlarmManager（Tasker/MacroDroid/Automate 都有 Time await/周期触发；Habitat 目前只有 WAIT_UNTIL_TIME 阻塞等待）
- **地理围栏**：Geofencing API 或轮询 GET_LOCATION

## 3. 建议实施顺序（回填 ROADMAP）

**第一批（★高、零/低权限、与现有架构同通道，纯逻辑或 Shizuku 一行命令）**：
1. Dialog 输入/确认/选择（悬浮窗，app 侧委托主线程）
2. SQLite 查询节点
3. ZIP 压缩/解压
4. 文件加解密（AES-GCM）
5. 媒体控制（无障碍全局动作）
6. 壁纸设置
7. 泛化系统设置读写（Shizuku settings）
8. 飞行模式 / 移动数据（Shizuku）
9. 数组/多变量 + 日期时间解析（引擎内部）
10. 屏幕状态/耳机/音乐播放状态查询
11. 广播发送 + Intent Received 触发器
12. TTS 增强（参数 + 合成到文件）
13. 天气 / 地理编码（HTTP）

**第二批（◐中）**：OCR、STT、热点、NFC、通知点击、地理围栏、日历/联系人 CRUD、FTP、SMTP 邮件、Quick Settings 磁贴入口、定时/周期触发器。

**明确不做**：Net Access 防火墙（VPNService）、Call Block/Divert（运营商级）、Matter/MIDI/Device Effects、远程多设备执行、Java/JS 万能后门、磁条卡/MIFARE、PDF。

## 4. 国内 ROM 适配（建议写入文档的"坑"清单）

- **MIUI**：无障碍服务易被自动关闭（MIUI12+ 尤甚）；必改：自启动允许、省电"无限制"、神隐关闭、锁屏清理关闭；悬浮窗在"其他权限"单独管理。
- **ColorOS 15**：无障碍可能自动失效，需"后台耗电管理-允许后台运行"+"自启动"。
- **OriginOS/Funtouch（vivo/iQOO）**：高耗电管理白名单必加 + "后台弹出界面"权限；一键加速会杀服务。
- **EMUI/HarmonyOS**：启动管理默认"自动管理"，需改"自启动/关联启动/后台活动"三项。
- **跨 ROM**：Android 10+ 后台剪贴板受限；13+ 悬浮窗单独授权 + POST_NOTIFICATIONS；MediaProjection 每次授权弹窗；电池优化白名单；微信等大厂有无障碍反检测（需坐标点击 fallback）。

## 5. 来源

- Automate block 索引：https://www.llamalab.com/automate/doc/block/index.html
- Tasker 官方 A-Z：https://tasker.joaoapps.com/userguide/en/help/ah_index.html （6.6 变更：https://tasker.joaoapps.com/changes/changes6.6.html）
- MacroDroid Wiki：https://macrodroidforum.com/wiki/index.php/Triggers / Actions / Constraints
- AutoJs6 文档：https://github.com/SuperMonster003/AutoJs6-Documentation
- AutoX.js 文档：https://autox-community.github.io/AutoX_Docs/
