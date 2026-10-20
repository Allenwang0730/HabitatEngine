# 节点参考

> 本文件由 `lib/src/main/java/com/ailun/habitat/spec/` 自动生成，不要手改。
> 更新：改规格后运行 `UPDATE_DOCS=1 ./gradlew :lib:testDebugUnitTest --tests '*NodeSpecDocTest*'`。

共 88 种节点。所有节点都可以加通用参数：
- `on_error`：节点出错时：skip=跳过继续（默认），stop=停止整个工作流
- `require`：执行后要求为 true 的变量名（如 click_success），不满足按出错处理
- `node_timeout_ms`：单节点超时毫秒，0=不限；默认 30 分钟

必填参数标 ★。字符串参数支持 `${var}` 插值。

## 目录

[`ACTION_FORK`](#action_fork) · [`ACTION_JOIN`](#action_join) · [`CONDITION_SWITCH`](#condition_switch) · [`CONDITION_ADVANCED_SWITCH`](#condition_advanced_switch) · [`ACTION_DELAY`](#action_delay) · [`ACTION_LOOP`](#action_loop) · [`ACTION_TRY_CATCH`](#action_try_catch) · [`ACTION_LOG`](#action_log) · [`ACTION_SET_VARIABLE`](#action_set_variable) · [`ACTION_TEXT_OPERATION`](#action_text_operation) · [`ACTION_MATH`](#action_math) · [`ACTION_CLIPBOARD`](#action_clipboard) · [`ACTION_PARSE_JSON`](#action_parse_json) · [`ACTION_PARSE_XML`](#action_parse_xml) · [`ACTION_BASE64`](#action_base64) · [`ACTION_FILE_OPERATION`](#action_file_operation) · [`ACTION_WORKSPACE`](#action_workspace) · [`ACTION_SQLITE_QUERY`](#action_sqlite_query) · [`ACTION_ZIP`](#action_zip) · [`ACTION_CRYPTO`](#action_crypto) · [`ACTION_ARRAY`](#action_array) · [`ACTION_PARSE_DATETIME`](#action_parse_datetime) · [`ACTION_GET_TIME`](#action_get_time) · [`ACTION_RANDOM`](#action_random) · [`ACTION_WAIT_UNTIL_TIME`](#action_wait_until_time) · [`ACTION_GET_BATTERY`](#action_get_battery) · [`ACTION_GET_DEVICE_INFO`](#action_get_device_info) · [`ACTION_GET_STORAGE_INFO`](#action_get_storage_info) · [`ACTION_GET_SENSOR`](#action_get_sensor) · [`ACTION_GET_SCREEN_STATE`](#action_get_screen_state) · [`ACTION_GET_AUDIO_STATE`](#action_get_audio_state) · [`ACTION_GET_SETTING`](#action_get_setting) · [`ACTION_RETRY`](#action_retry) · [`ACTION_ASSERT`](#action_assert) · [`ACTION_TOAST`](#action_toast) · [`ACTION_VIBRATE`](#action_vibrate) · [`ACTION_CLICK`](#action_click) · [`ACTION_SWIPE`](#action_swipe) · [`ACTION_LONG_PRESS`](#action_long_press) · [`ACTION_INPUT_TEXT`](#action_input_text) · [`ACTION_GLOBAL_KEY`](#action_global_key) · [`ACTION_FIND_ELEMENT`](#action_find_element) · [`ACTION_GLOBAL_NAV`](#action_global_nav) · [`ACTION_WAIT_FOR_ELEMENT`](#action_wait_for_element) · [`ACTION_SHELL`](#action_shell) · [`ACTION_LAUNCH_APP`](#action_launch_app) · [`ACTION_FORCE_STOP_APP`](#action_force_stop_app) · [`ACTION_SCREEN_WAKE`](#action_screen_wake) · [`ACTION_WIFI`](#action_wifi) · [`ACTION_BLUETOOTH`](#action_bluetooth) · [`ACTION_VOLUME`](#action_volume) · [`ACTION_BRIGHTNESS`](#action_brightness) · [`ACTION_FLASHLIGHT`](#action_flashlight) · [`ACTION_SET_ALARM`](#action_set_alarm) · [`ACTION_DND_MODE`](#action_dnd_mode) · [`ACTION_SHARE`](#action_share) · [`ACTION_SEND_BROADCAST`](#action_send_broadcast) · [`ACTION_MEDIA_CONTROL`](#action_media_control) · [`ACTION_CALL_PHONE`](#action_call_phone) · [`ACTION_CALL_DIRECT`](#action_call_direct) · [`ACTION_WAIT_CALL_END`](#action_wait_call_end) · [`ACTION_WAIT_CALL_ANSWER`](#action_wait_call_answer) · [`ACTION_GET_CALL_STATE`](#action_get_call_state) · [`ACTION_GET_SIM_INFO`](#action_get_sim_info) · [`ACTION_READ_CALL_LOG`](#action_read_call_log) · [`ACTION_SCREENSHOT`](#action_screenshot) · [`ACTION_TEXT_TO_SPEECH`](#action_text_to_speech) · [`ACTION_PLAY_MEDIA`](#action_play_media) · [`ACTION_RECORD_AUDIO`](#action_record_audio) · [`ACTION_CAPTURE_PHOTO`](#action_capture_photo) · [`ACTION_SET_WALLPAPER`](#action_set_wallpaper) · [`ACTION_SAY_TO_FILE`](#action_say_to_file) · [`ACTION_HTTP_REQUEST`](#action_http_request) · [`ACTION_NETWORK_STATUS`](#action_network_status) · [`ACTION_GET_WEATHER`](#action_get_weather) · [`ACTION_GEOCODE`](#action_geocode) · [`ACTION_GET_TRAFFIC`](#action_get_traffic) · [`ACTION_READ_SCREEN`](#action_read_screen) · [`ACTION_READ_SMS`](#action_read_sms) · [`ACTION_GET_APP_INFO`](#action_get_app_info) · [`ACTION_FOREGROUND_APP`](#action_foreground_app) · [`ACTION_APP_SEARCH`](#action_app_search) · [`ACTION_GET_LOCATION`](#action_get_location) · [`ACTION_READ_NOTIFICATIONS`](#action_read_notifications) · [`ACTION_AI_CHAT`](#action_ai_chat) · [`ACTION_SEND_NOTIFICATION`](#action_send_notification) · [`ACTION_DYNAMIC_ISLAND`](#action_dynamic_island) · [`ACTION_MINI_SCREEN`](#action_mini_screen)

### ACTION_FORK

并行：同时启动多个分支，各分支沿自己的 next 执行

| 参数 | 类型 | 说明 |
|---|---|---|
| `targets` ★ | array | 要并行启动的节点 id 列表 |

### ACTION_JOIN

汇合：等待 FORK 启动的分支都结束后继续 next

### CONDITION_SWITCH

按条件表达式走 true / false 分支

| 参数 | 类型 | 说明 |
|---|---|---|
| `expression` ★ | string | 条件：变量名 运算符 值（如 battery_level > 20），或单个布尔变量名 |

> 运算符 ==、!=、>、<、>=、<=、contains、startswith、endswith；== / != 按字符串比较，> < >= <= 按数字比较（非数字当 0），contains/startswith/endswith 不区分大小写。左边是变量名（不要写 ${}），右边是字面值，值不加引号；> 和 < 两侧必须有空格；未定义变量按字符串 null 比较。分支写在节点的 branches（true/false → 节点 id），不走 next，缺的分支即该路径结束。

### CONDITION_ADVANCED_SWITCH

高级条件分支：表达式先插值，两边都可以是变量或字面值

| 参数 | 类型 | 说明 |
|---|---|---|
| `expression` | string | 条件表达式，支持 ${var}，如 ${score} > 10、is_daytime；不填则用 var/operator/value 组装 |
| `var` | string | 拆分写法：左侧变量名 |
| `operator` | == / != / > / < / >= / <= / contains / startswith / endswith | 拆分写法：运算符（也认：op） |
| `value` | string_or_number | 拆分写法：右侧值 |

> 插值后每一侧若是已定义的变量名就取其值，否则当字面值；数字比较只认数字类型变量，字符串变量请写成 ${var}。contains/startswith/endswith 不区分大小写且两侧要有空格；内置 is_daytime / is_nighttime（8:00–17:59 为白天）。分支写在 branches（true/false → 节点 id），不走 next。

### ACTION_DELAY

暂停指定毫秒数

| 参数 | 类型 | 说明 |
|---|---|---|
| `millis` | string_or_number | 延迟毫秒数，上限 60000（超出截断），支持 ${var}（默认 0）（也认：ms） |

> 超过 60 秒的等待用 ACTION_WAIT_UNTIL_TIME。

### ACTION_LOOP

计数或条件循环：每次进入判断是跳进循环体还是走 next 结束

| 参数 | 类型 | 说明 |
|---|---|---|
| `mode` | count / while | count=计数循环，while=条件循环（默认 count） |
| `times` | string_or_number | count 模式：循环次数，支持 ${var}（默认 1） |
| `counter_var` | string | count 模式：当前轮次（从 1 起）写到的变量名（默认 loop_index） |
| `condition_expr` | string | while 模式：条件，只支持 变量名 == 值 / 变量名 != 值 |
| `max_iterations` | string_or_number | while 模式：最多迭代次数（默认 100） |
| `body_node` ★ | string | 循环体第一个节点的 id |

输出：`loop_index`

> 循环体最后一个节点的 next 要指回本循环节点；本节点的 next 是循环结束后的去向。count 模式结束时 counter_var 重置为 0。

### ACTION_TRY_CATCH

按上一个节点是否失败走 success / error 分支

| 参数 | 类型 | 说明 |
|---|---|---|
| `catch_var` | string | 出错时写入错误信息的变量名（默认 exception_msg） |

输出：`exception_msg`

> 只看紧挨着的上一个节点：失败指抛异常、超时或 require 不满足；多数节点出错只是 xxx_success=false，要在上一节点加 require（如 file_success）才算失败。分支写在 branches（success/error → 节点 id），不走 next，success 分支也要写，缺的分支即该路径结束。

### ACTION_LOG

输出一条调试日志

| 参数 | 类型 | 说明 |
|---|---|---|
| `message` | string | 日志内容，支持 ${var} |
| `level` | debug / info / warn / error | 日志级别（默认 info） |

> 只写 Logcat（tag HabitatLog），不进工作流运行日志；要让用户看到请用 ACTION_TOAST 或 ACTION_WORKSPACE。

### ACTION_SET_VARIABLE

设置一个变量

| 参数 | 类型 | 说明 |
|---|---|---|
| `name` ★ | string | 变量名（也认：key） |
| `value` | any | 变量值，任意 JSON 值；字符串支持 ${var}；为 null 或转换失败时删除该变量 |
| `type` | auto / string / int / bool / float | 类型转换，auto=保持原类型（默认 auto） |

> 含 ${var} 的值插值后是字符串；后续要做数字运算/比较时设 type=int 或 float。

### ACTION_TEXT_OPERATION

文本处理：替换、截取、拆分、大小写、去空白、拼接

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | replace / substring / split / uppercase / lowercase / trim / append / prepend | 操作类型（也认：operation） |
| `input` | string | 输入：变量名（取其值）或字面文本（支持 ${var}）（也认：source_var） |
| `old` | string | replace：要被替换的文字，支持 ${var}（也认：search） |
| `new` | string | replace：替换成的文字，支持 ${var}（也认：replace_with） |
| `start` | string_or_number | substring：起始下标（含）（默认 0） |
| `end` | string_or_number | substring：结束下标（不含），默认到末尾 |
| `delimiter` | string | split：分隔符（默认 ,） |
| `text` | string | append/prepend：要拼接的文字，支持 ${var} |
| `output_var` | string | 结果变量名（默认 text_output） |

输出：`text_output`

> split 的结果是用换行连接的一个字符串，不是数组；要真正的数组用 ACTION_ARRAY（action=set）。

### ACTION_MATH

两个数的四则、取模、乘方运算

| 参数 | 类型 | 说明 |
|---|---|---|
| `operation` ★ | add / subtract / multiply / divide / modulo / power | 运算类型 |
| `a` | string_or_number | 第一个操作数：数字、${var} 或数字变量名（默认 0）（也认：operand1_var） |
| `b` | string_or_number | 第二个操作数：数字、${var} 或数字变量名（默认 0）（也认：operand2、operand2_var） |
| `output_var` | string | 结果变量名（默认 math_result） |

输出：`math_result`

> 结果总是小数（如 5.0），CONDITION_SWITCH 用 == 比较时要写 5.0，或改用 > / <；除数为 0 时结果为 0；无法解析的操作数按 0。

### ACTION_CLIPBOARD

读取或写入系统剪贴板

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | get / set | get=读取，set=写入 |
| `text` | string | 要写入的文字（action=set 时必填，支持 ${var}） |
| `output_var` | string | 读取结果存到的变量名（默认 clipboard_content） |

输出：`clipboard_content` `clipboard_success` `clipboard_error`

> Android 10+ 应用在后台时系统禁止读剪贴板，get 可能得到空串而 clipboard_success 仍为 true。

### ACTION_PARSE_JSON

解析 JSON 字符串并按路径取值

| 参数 | 类型 | 说明 |
|---|---|---|
| `json` ★ | string | JSON 文本（支持 ${var}）或存着 JSON 的变量名 |
| `path` | string | 取值路径，如 data.items[0].name；不填返回整个 JSON |
| `output_var` | string | 结果变量名（默认 parsed_json） |

输出：`parsed_json` `parsed_value` `parse_success` `parse_error`

> 结果都是字符串（对象/数组为格式化 JSON）；路径不存在时结果是字符串 null，但 parse_success 仍为 true。

### ACTION_PARSE_XML

解析 XML 字符串，按标签路径取元素文本或属性

| 参数 | 类型 | 说明 |
|---|---|---|
| `xml` ★ | string | XML 文本（支持 ${var}）或存着 XML 的变量名 |
| `xpath` | string | 点分标签路径，如 root.item.name（不是真正的 XPath）；不填返回根元素全部文本 |
| `attribute` | string | 取该属性值而不是元素文本 |
| `output_var` | string | 结果另存到的变量名（xml_parsed_value 总会写） |

输出：`xml_parsed_value` `xml_success` `xml_error`

> 匹配到多个元素时结果是 JSON 数组字符串。

### ACTION_BASE64

Base64 编码或解码（UTF-8 文本）

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | encode / decode | encode=编码，decode=解码 |
| `data` ★ | string | 要编码/解码的文字（支持 ${var}） |
| `output_var` | string | 结果存到的变量名（默认 base64_result） |

输出：`base64_result` `base64_success`

### ACTION_FILE_OPERATION

文件读、写、复制、删除、存在检查、列目录

前置：访问共享存储（/sdcard）需「所有文件访问」权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | read / write / copy / delete / exists / list | 操作类型 |
| `path` ★ | string | 文件或目录路径，支持 ${var} |
| `content` | string | write：要写入的内容，支持 ${var} |
| `append` | bool | write：true=追加，false=覆盖（默认 false） |
| `dest` | string | copy 时必填：目标路径；path 是目录时为目标目录，复制其中最新修改的文件 |
| `recursive` | bool | delete 目录时必须设为 true（默认 false） |
| `output_var` | string | read 的内容 / list 的 JSON 数组另存到的变量名（file_result 总会写） |

输出：`file_result` `file_exists` `file_is_directory` `file_is_file` `file_list` `file_count` `file_copied_from` `file_success` `file_error`

> 路径只允许本应用目录和共享存储；系统目录、其它应用私有目录被拒绝，存储根与一级公共目录（DCIM、Download…）不能整体删除。list 结果每项形如 [F] a.txt / [D] dir。

### ACTION_WORKSPACE

读写本机工作区条目（结果、笔记、图片），用户在 App「工作区」页查看

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | write / append / read / list / delete / search | 操作类型（默认 write） |
| `name` | string | 条目显示名（write/append 必填；read/delete 可用它或 id 定位） |
| `id` | string | read/delete：条目 id，优先于 name |
| `content` | string | write/append：内容，支持 ${var}；kind=json 时须为合法 JSON，kind=image 时为 base64 |
| `kind` | text / json / image / record / txt / markdown / md / img / png / jpg / jpeg | write：条目类型 text（默认）/ json / image；list：按类型筛选，可多一个 record；其余为别名 |
| `image_path` | string | kind=image：图片文件路径（代替 content），上限 8MB |
| `tags` | string | write/append：标签，逗号分隔 |
| `overwrite` | bool | write：同名同类时覆盖；false 则自动改名为「名字 (2)」（默认 true） |
| `tag` | string | list：按标签筛选 |
| `query` | string | search：找最近一条名字/标签/内容包含它的笔记（文本或 JSON）；空=最近一条 |
| `limit` | string_or_number | list：最多返回条数（1–200）（默认 20） |
| `output_var` | string | read/search 的内容 / list 的 JSON 数组存到的变量名（默认 workspace_content / workspace_list） |

输出：`workspace_id` `workspace_kind` `workspace_content` `workspace_path` `workspace_list` `workspace_count` `workspace_name` `workspace_success` `workspace_error`

### ACTION_SQLITE_QUERY

对 .db 文件执行一条 SQL

前置：访问共享存储（/sdcard）需「所有文件访问」权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `database` ★ | string | 数据库文件路径，支持 ${var} |
| `query` ★ | string | 一条 SQL 语句，支持 ${var} |
| `args` | any | 绑定到 ? 占位符的参数，JSON 数组字符串如 ["a",1]（不做 ${var} 插值） |
| `output_var` | string | 结果变量名（默认 sql_result） |
| `limit` | string_or_number | SELECT 最多返回行数（1–5000）（默认 500） |
| `create` | bool | 数据库文件不存在时是否创建（默认 false） |

输出：`sql_result` `sql_rows` `sql_success` `sql_error`

> 只有以 SELECT 开头的语句返回行（JSON 数组字符串）；其它语句（含 PRAGMA、WITH）结果为 OK，sql_rows 为受影响行数。路径只允许本应用目录和共享存储。

### ACTION_ZIP

ZIP 压缩、解压、列出条目

前置：访问共享存储（/sdcard）需「所有文件访问」权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | zip / unzip / list | zip=压缩，unzip=解压，list=列出条目（默认 zip） |
| `source` ★ | string | zip：要压缩的文件或目录；unzip/list：zip 文件路径。支持 ${var} |
| `dest` | string | zip：输出 .zip 路径（默认 source 后加 .zip）；unzip：输出目录（默认与 zip 同名的目录） |
| `output_var` | string | 结果变量名：zip/unzip 为目标路径，list 为条目名 JSON 数组（默认 zip_result） |
| `delete_source` | bool | 成功后删除源（zip 只删单个文件，不删目录）（默认 false） |
| `max_unzip_bytes` | string_or_number | 解压总大小上限（字节），超出即中止（默认 1073741824） |

输出：`zip_result` `zip_count` `zip_success` `zip_error`

### ACTION_CRYPTO

用口令对文件做 AES-GCM 加密或解密

前置：访问共享存储（/sdcard）需「所有文件访问」权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | encrypt / decrypt | encrypt=加密，decrypt=解密（默认 encrypt） |
| `path` ★ | string | 输入文件路径，支持 ${var} |
| `password` ★ | string | 口令，支持 ${var} |
| `output_path` | string | 输出路径；默认加密为 path 后加 .enc，解密去掉 .enc（不以 .enc 结尾则加 .dec）；不能与输入相同 |
| `output_var` | string | 保存输出路径的变量名（默认 crypto_result） |

输出：`crypto_result` `crypto_success` `crypto_error`

> 密文格式为本节点专用（PBKDF2 派生密钥 + AES-256-GCM），只能用本节点和同一口令解密；口令错误时解密失败，不会覆盖已有文件。

### ACTION_ARRAY

数组变量操作：建立、追加、弹出、取值、长度、拼接、清空、删除、包含

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | set / push / pop / get / length / join / clear / remove / contains | 操作类型 |
| `var` ★ | string | 数组变量名（不要写 ${}） |
| `value` | string | set：按 delimiter 分隔的文本，或 JSON 数组字符串 |
| `item` | string | push/remove/contains：元素 |
| `index` | string_or_number | get/remove：下标，负数从末尾数；get 默认 0，remove 不填则按 item 删除 |
| `delimiter` | string | set/join：分隔符（默认 ,） |
| `output_var` | string | 结果变量名：set/push/clear/length 为长度，pop/get 为元素，remove 按下标为元素、按 item 为布尔，join 为字符串，contains 为布尔（默认 array_result） |

输出：`array_result`

> value、item、index 不做 ${var} 插值；元素一律按字符串存。数组本体存回 var。

### ACTION_PARSE_DATETIME

取当前时间、解析或格式化日期时间，可加减偏移

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | now / parse / format | now=当前时间，parse=解析字符串，format=格式化（默认 now） |
| `input` | string_or_number | parse/format 时必填：日期时间字符串，format 也可给毫秒时间戳；支持 ${var} |
| `input_format` | string | input 的格式（java.time 模式）（默认 yyyy-MM-dd HH:mm:ss） |
| `format` | string | 输出格式（java.time 模式）（默认 yyyy-MM-dd HH:mm:ss） |
| `add_days` | string_or_number | 加减天数（可为负）（默认 0） |
| `add_hours` | string_or_number | 加减小时（可为负）（默认 0） |
| `add_minutes` | string_or_number | 加减分钟（可为负）（默认 0） |
| `add_seconds` | string_or_number | 加减秒数（可为负）（默认 0） |
| `output_var` | string | 格式化结果变量名（默认 datetime_result） |

输出：`datetime_result` `datetime_epoch` `datetime_year` `datetime_month` `datetime_day` `datetime_hour` `datetime_minute` `datetime_second` `datetime_weekday` `datetime_success` `datetime_error`

> 按 LocalDateTime 解析，input_format 必须含时间部分（纯日期如 yyyy-MM-dd 会失败）；datetime_weekday 1=周一…7=周日。

### ACTION_GET_TIME

获取当前时间及年月日时分秒

| 参数 | 类型 | 说明 |
|---|---|---|
| `format` | string | 时间格式（SimpleDateFormat 模式）（默认 yyyy-MM-dd HH:mm:ss） |
| `output_var` | string | 格式化时间另存到的变量名（current_time 总会写）（默认 current_time） |
| `timestamp` | bool | 目前不影响输出；毫秒时间戳总写在 current_timestamp（默认 false） |

输出：`current_time` `current_timestamp` `current_year` `current_month` `current_day` `current_hour` `current_minute` `current_second` `current_weekday` `time_success`

> current_weekday 0=周日…6=周六（与 ACTION_PARSE_DATETIME 的 1=周一不同）。

### ACTION_RANDOM

生成随机整数、小数或布尔值

| 参数 | 类型 | 说明 |
|---|---|---|
| `min` | string_or_number | 最小值（只取整数部分）（默认 0） |
| `max` | string_or_number | 最大值（只取整数部分）（默认 100） |
| `type` | int / float / boolean | 结果类型（默认 int） |
| `output_var` | string | 结果变量名（默认 random_value） |

输出：`random_value` `random_success`

> int 含两端，float 为 [min, max)；min > max 时自动交换。

### ACTION_WAIT_UNTIL_TIME

等待到指定时刻再继续（每秒轮询）

| 参数 | 类型 | 说明 |
|---|---|---|
| `time` ★ | string_or_number | 目标时刻：clock 模式为 HH:mm 或 HH:mm:ss，timestamp 模式为毫秒时间戳；支持 ${var} |
| `mode` | clock / timestamp | clock=钟点，timestamp=毫秒时间戳（默认 clock） |
| `timeout_ms` | string_or_number | 最长等待毫秒（至少 1000）；默认 29 分钟，设了 node_timeout_ms 时默认 12 小时 |
| `output_var` | string | 是否等到的结果变量名（time_reached 总会写）（默认 time_reached） |

输出：`time_reached` `wait_until_time`

> 执行器默认单节点超时 30 分钟：等待超过 29 分钟需同时设 node_timeout_ms=0（或足够大），否则 timeout_ms 会被截到 29 分钟。clock 模式下今天已过的时刻顺延到明天；超时后 time_reached=false 并继续 next。

### ACTION_GET_BATTERY

读取电量和充电状态

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | 电量另存到的变量名（battery_level 总会写） |

输出：`battery_level` `battery_charging` `battery_success` `battery_error`

> battery_level 为 0–100；充满时 battery_charging 也为 true。

### ACTION_GET_DEVICE_INFO

读取设备型号、Android 版本和屏幕尺寸

输出：`device_model` `device_manufacturer` `device_brand` `device_android_version` `device_sdk` `device_screen_width` `device_screen_height` `device_density` `device_success` `device_error`

### ACTION_GET_STORAGE_INFO

读取内部存储空间（字节）

输出：`storage_total` `storage_available` `storage_used` `storage_available_percent` `storage_success` `storage_error`

### ACTION_GET_SENSOR

读取一次光线、距离或加速度传感器的值

| 参数 | 类型 | 说明 |
|---|---|---|
| `type` | light / proximity / accel / accelerometer | 传感器类型（默认 light） |
| `output_var` | string | 读数另存到的变量名（sensor_value 总会写）（默认 sensor_value） |

输出：`sensor_value` `sensor_type` `sensor_success` `sensor_error`

> 只取首个读数的第一个分量（加速度为 X 轴）；3 秒内没有读数即失败。

### ACTION_GET_SCREEN_STATE

读取屏幕是否亮着、是否锁屏

输出：`screen_on` `screen_locked` `screen_state_success` `screen_state_error`

### ACTION_GET_AUDIO_STATE

读取是否在播放音乐、是否连着耳机

输出：`music_active` `headset_connected` `audio_state_success` `audio_state_error`

> headset_connected 含有线/USB 耳机和蓝牙 A2DP。

### ACTION_GET_SETTING

读取一项系统设置（只读）

| 参数 | 类型 | 说明 |
|---|---|---|
| `key` ★ | string | 设置键名，如 screen_off_timeout、adb_enabled；支持 ${var} |
| `namespace` | system / global / secure | 设置表（默认 system） |
| `output_var` | string | 结果变量名（默认 setting_value） |

输出：`setting_value` `setting_key` `setting_namespace` `setting_success` `setting_error`

> 值总是字符串；键不存在或无权读取时 setting_success=false（新版 Android 限制读取部分 secure/global 键）。

### ACTION_RETRY

失败重试：反复执行目标节点（链），直到成功或次数用完

| 参数 | 类型 | 说明 |
|---|---|---|
| `target` ★ | string | 要重试的第一个节点 id |
| `times` | string_or_number | 总尝试次数（含第一次，至少 1）（默认 3） |
| `interval_ms` | string_or_number | 两次尝试的间隔毫秒（默认 1000） |

> 目标链最后一个节点的 next 要指回本节点；链上有节点失败就重试，成功或用完次数后走本节点 next。多数节点出错只是 xxx_success=false，要给它加 require（如 require=file_success）才算失败。

### ACTION_ASSERT

断言：条件为假时让本节点失败，进入错误处理

| 参数 | 类型 | 说明 |
|---|---|---|
| `condition` ★ | string | 条件：变量名 运算符 值（如 x == 6），或布尔变量名 |
| `message` | string | 失败时的错误信息（写入 last_error） |

> 失败按节点出错处理，可被 on_error、ACTION_TRY_CATCH、ACTION_RETRY 捕获。运算符 ==、!=、>、<、>=、<=（无 contains）；两边先按变量名取值，取不到当字面值；不支持 ${var}，值不加引号。

### ACTION_TOAST

弹出一条短提示

| 参数 | 类型 | 说明 |
|---|---|---|
| `message` | string | 提示文字，支持 ${var} |

### ACTION_VIBRATE

振动一次

| 参数 | 类型 | 说明 |
|---|---|---|
| `duration` | string_or_number | 振动时长毫秒（1–10000）（默认 200）（也认：ms） |
| `amplitude` | string_or_number | 振动强度（1–255）（默认 128） |

### ACTION_CLICK

点击：按坐标、界面文字或 resource-id 点击目标

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `target` ★ | string | "x,y" 像素坐标；或界面文字（不区分大小写的子串匹配）；或 "id:包名:id/xxx" 按 resource-id 匹配。支持 ${var} |
| `region` | string | 仅文字/id 匹配：限定目标中心所在区域，top/bottom/left/right/center 或归一化 "x1,y1,x2,y2"（如 "0,0.5,1,1"=下半屏）；解析不了则不限定 |
| `occurrence` | string_or_number | 仅文字/id 匹配：region 过滤后取第几个（屏幕从上到下，0 起；0=第一个可见的）；越界直接失败（默认 0） |
| `offset_x` | string_or_number | 仅文字/id 匹配：点击点相对目标中心的水平偏移像素（默认 0） |
| `offset_y` | string_or_number | 仅文字/id 匹配：点击点相对目标中心的垂直偏移像素（向下为正）（默认 0） |

输出：`click_success`

> 同一文字可能多处出现（多个「确定」「发送」）：用 region 限定区域或 occurrence 取第 N 个，避免点错。

### ACTION_SWIPE

滑动：从 (x1,y1) 直线滑到 (x2,y2)

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `x1` ★ | string_or_number | 起点 x（像素） |
| `y1` ★ | string_or_number | 起点 y（像素） |
| `x2` ★ | string_or_number | 终点 x（像素） |
| `y2` ★ | string_or_number | 终点 y（像素） |
| `duration` | string_or_number | 滑动时长毫秒，限制 1-60000（默认 400） |

输出：`swipe_success`

### ACTION_LONG_PRESS

长按：按坐标或界面文字 / viewId 长按目标

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `target` ★ | string | "x,y" 像素坐标；或界面文字 / 完整 viewId（如 com.pkg:id/xxx，不加 id: 前缀）。支持 ${var} |
| `duration` | string_or_number | 按住时长毫秒（坐标模式和手势兜底时生效），限制 1-60000（默认 800） |

输出：`long_press_success`

> target 含逗号一律按坐标解析，解析失败直接失败（不像 ACTION_CLICK 会退回文字匹配）；不支持 region/occurrence。

### ACTION_INPUT_TEXT

输入文字：写入输入框，可选输入后回车提交

前置：需无障碍（mode=shell/ime 需 Shizuku 或 root）

| 参数 | 类型 | 说明 |
|---|---|---|
| `text` | string | 要输入的文字，支持 ${var}；为空时须 submit=true（只提交）或 allow_empty=true（清空） |
| `mode` | a11y / shell / ime | a11y=无障碍写入（未生效时退回剪贴板粘贴，中文可用）；shell/ime=shell `input text`（默认 a11y） |
| `target` | string | "x,y" 坐标：定位包含该点的输入框并先聚焦（a11y 写入和 submit 用，shell/ime 写入不看它）；不传用当前焦点输入框。支持 ${var} |
| `submit` | bool | 输入后触发 IME 回车（搜索/发送）；优先无障碍 IME_ENTER，不支持时退回 shell keyevent 66（默认 false） |
| `allow_empty` | bool | 允许 text 为空时把输入框设为空串（清空）（默认 false） |

输出：`input_success`

> a11y 模式会读回输入框核对内容。shell/ime 的 input text 打不出中文等非 ASCII 字符。QQ/微信等输入框点过后常不报告焦点，传 target 坐标才写得进去。

### ACTION_GLOBAL_KEY

全局按键：返回、桌面、最近任务、通知栏、回车等

前置：需 Shizuku/root 或无障碍（见 key 说明）

| 参数 | 类型 | 说明 |
|---|---|---|
| `key` ★ | back / home / recents / notifications / screenshot_toggle / enter / delete / space / tab / quick_settings / power_dialog | 按键。back/home/recents/notifications：先 shell keyevent，失败退回无障碍；quick_settings/power_dialog：仅无障碍；screenshot_toggle/enter/delete/space/tab：仅 shell |

输出：`key_success`

### ACTION_FIND_ELEMENT

找元素：限时轮询查找界面元素，输出是否找到、边界和文字

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `selector` ★ | string | 要找的文字 / 完整 viewId / 类名 / 内容描述（按 search_mode）。支持 ${var} |
| `search_mode` | text / id / class / content_desc | text=界面文字（不区分大小写的子串）、id=完整 viewId、class=类名子串、content_desc=内容描述子串；主方式找不到会自动换其它方式再找（默认 text） |
| `region` | string | 限定元素中心所在区域：top/bottom/left/right/center 或归一化 "x1,y1,x2,y2"；解析不了直接失败并写 element_error |
| `timeout_ms` | string_or_number | 最长查找毫秒（每 100ms 重试一次）（默认 3000） |
| `output_var` | string | 可选：把元素详情 map（found/bounds/text/clickable/contentDescription/className/packageName）存到这个变量 |

输出：`element_found` `element_error` `element_bounds` `element_text` `element_clickable`

> element_bounds 是 "left,top,right,bottom" 四个数，不能直接当 ACTION_CLICK 的 target（会点到左上角）。

### ACTION_GLOBAL_NAV

无障碍全局动作：返回、桌面、最近任务、通知栏、快捷设置、电源菜单、锁屏、截屏

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | back / home / recents / notifications / quick_settings / power_dialog / lock_screen / take_screenshot | 全局动作（纯无障碍 performGlobalAction，不需要 Shizuku） |

输出：`nav_success` `nav_error`

### ACTION_WAIT_FOR_ELEMENT

等元素：每 500ms 轮询，等待界面元素出现或消失

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `selector` ★ | string | 关键字 / 完整 viewId（如 com.pkg:id/xxx）/ 类名（按 mode）。支持 ${var} |
| `mode` | text / id / class | text=可见文字或内容描述包含关键字（不区分大小写）、id=完整 viewId、class=类名子串（默认 text） |
| `wait_for` | appear / disappear | 等出现还是等消失（默认 appear） |
| `timeout_ms` | string_or_number | 最长等待毫秒（默认 10000）（也认：timeout） |
| `output_var` | string | 结果变量名（默认 element_found） |

输出：`element_found`

> 结果为 true 表示条件满足：wait_for=disappear 时 true 是「已消失」。只查当前活动窗口。

### ACTION_SHELL

执行 shell 命令，输出命令结果

前置：需 Shizuku 或 root

| 参数 | 类型 | 说明 |
|---|---|---|
| `command` ★ | string | shell 命令；其中的 ${var} 替换后自动加单引号转义（作为一个完整参数），不要再给它加引号 |
| `mode` | auto / shizuku / root | root=以 root 执行；auto/shizuku=由 shell 执行器决定通道（Habitat 默认 Shizuku）（默认 auto） |
| `raw_interpolation` | bool | true=${var} 原样拼入命令不转义（有注入风险）（默认 false） |
| `timeout_ms` | string_or_number | 超时毫秒，只在宿主没提供 shell 执行器、走本地 sh 兜底时生效（默认 60000） |

输出：`shell_output` `shell_success`

> 失败时 shell_output 以 "Error:" 开头（非 0 退出码、超时、Shizuku 不可用），shell_success 据此判断。

### ACTION_LAUNCH_APP

打开应用，或打开系统设置的某一页

| 参数 | 类型 | 说明 |
|---|---|---|
| `package_name` | string | 应用包名，支持 ${var}；不给 intent_action 时必填 |
| `activity` | string | 可选：完整 Activity 类名，直接启动该页面 |
| `intent_action` | string | 打开系统设置页，只允许 android.settings.*（如 android.settings.BLUETOOTH_SETTINGS）或 android.intent.action.POWER_USAGE_SUMMARY；给了就忽略 package_name |

输出：`launch_success` `launch_error` `launch_has_overlay` `launch_package`

> Android 10+ 后台启动可能被系统静默拦截而 launch_success 仍为 true；launch_has_overlay 表示是否有悬浮窗权限（后台启动的豁免条件之一）。

### ACTION_FORCE_STOP_APP

强制停止应用（am force-stop）

前置：需 Shizuku 或 root

| 参数 | 类型 | 说明 |
|---|---|---|
| `package_name` ★ | string | 要停止的应用包名（须为合法包名），支持 ${var} |
| `as_root` | bool | 以 root 执行（默认 false） |

输出：`force_stop_success`

### ACTION_SCREEN_WAKE

亮屏、息屏、亮屏并解锁

前置：sleep 和解锁需 Shizuku 或 root；wake 无 shell 时退回 WakeLock

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | wake / sleep / wake_and_unlock | wake=亮屏；sleep=息屏；wake_and_unlock=亮屏后发菜单键划开锁屏，给了 password 再输入并回车 |
| `password` | string | 可选：wake_and_unlock 的锁屏密码，只能是 1-64 位字母数字（格式不对整个节点直接失败） |

输出：`screen_wake_success`

> wake_and_unlock 不核对是否真的解锁（按键序列发完即算成功）；图案锁解不了。

### ACTION_WIFI

WiFi 开关、切换、查询状态

前置：开关需 Shizuku 或 root（Android 10+ 系统 API 对普通应用无效）；status 不需要

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | on / off / toggle / status | on/off/toggle 开关；status 查询（只有 status 写 wifi_enabled/wifi_state） |

输出：`wifi_success` `wifi_enabled` `wifi_state`

> wifi_state 是 WifiManager 数字码：0=关闭中 1=已关 2=开启中 3=已开 4=未知；开启中也算 wifi_enabled=true。

### ACTION_BLUETOOTH

蓝牙开关、切换、查询状态

前置：开关需 Shizuku 或 root（Android 13+ 系统 API 对普通应用无效）；status 不需要

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | on / off / toggle / status | on/off/toggle 开关；status 查询（只有 status 写 bluetooth_enabled） |

输出：`bluetooth_success` `bluetooth_enabled`

### ACTION_VOLUME

音量：设置、读取、增减、静音

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | set / get / up / down / mute / unmute | set=按百分比设置；get=读取；up/down=调一档；mute/unmute=静音/取消 |
| `stream` | music / ring / alarm / notification / system / call / voice_call / dtmf / accessibility | 音频流；call 与 voice_call 相同，无法识别的值按 music（默认 music） |
| `value` | string_or_number | 音量百分比 0-100（超出截断），action=set 时必填 |

输出：`volume_success` `volume_current` `volume_max`

> value 是百分比，而 volume_current/volume_max 是系统档位（如 0-15），两者不能直接比较。

### ACTION_BRIGHTNESS

屏幕亮度：设置、读取、开关自动亮度

前置：set/auto_on/auto_off 需「修改系统设置」权限（WRITE_SETTINGS）；get 不需要

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | set / get / auto_on / auto_off | set=设置亮度；get=读取亮度和模式；auto_on/auto_off=开/关自动亮度 |
| `value` | string_or_number | 亮度 0-255（超出截断），action=set 时必填 |

输出：`brightness_success` `brightness_error` `brightness_value` `brightness_mode`

> 自动亮度开着时 set 的值可能被系统改掉，要固定亮度先 auto_off。brightness_mode 为 auto/manual。

### ACTION_FLASHLIGHT

手电筒开关

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | on / off / toggle | on/off/toggle |

输出：`flashlight_success` `flashlight_on`

> 系统没有同步查询手电状态的 API：toggle 按本次运行记录的 flashlight_on 取反，没记录时一律打开。

### ACTION_SET_ALARM

设闹钟：打开系统时钟的新建闹钟（带时间和标签）

| 参数 | 类型 | 说明 |
|---|---|---|
| `hour` ★ | string_or_number | 小时 0-23 |
| `minute` ★ | string_or_number | 分钟 0-59 |
| `label` | string | 可选：闹钟标签，支持 ${var} |

输出：`alarm_success` `alarm_error`

> 通过 Intent 调起系统时钟应用（不是静默创建），后台启动受限时可能没弹出来。

### ACTION_DND_MODE

免打扰：查询或切换模式

前置：on/off/priority/alarms 需免打扰权限（ACCESS_NOTIFICATION_POLICY，授了通知使用权也算）；get 不需要

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | get / query / on / off / priority / alarms | get/query=只查询；on=完全静音；off=全部允许；priority=仅优先；alarms=仅闹钟（默认 get） |

输出：`dnd_success` `dnd_error` `dnd_state`

> dnd_state 取值 off/on/priority/alarms/unknown（off=未开免打扰）。

### ACTION_SHARE

分享文字：弹出系统分享面板

| 参数 | 类型 | 说明 |
|---|---|---|
| `text` ★ | string | 要分享的文字，支持 ${var} |
| `title` | string | 分享面板标题，支持 ${var}（默认 分享） |
| `type` | string | MIME 类型（默认 text/plain） |
| `subject` | string | 可选：主题（如邮件标题），支持 ${var} |

输出：`share_success` `share_error`

> 只通过 EXTRA_TEXT 分享文字，不附带文件；改 type 也发不了图片。

### ACTION_SEND_BROADCAST

发送广播 Intent

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` ★ | string | 广播 action，支持 ${var} |
| `package` | string | 可选：限定接收的应用包名，支持 ${var} |
| `component` | string | 可选：指定接收组件 "包名/类名"（类名可写 .Xxx），支持 ${var} |
| `extras` | any | 可选：附加数据，JSON 对象或 JSON 字符串（如 {"key":"v","num":5,"flag":true}）；字符串值支持 ${var}，解析失败则忽略 extras |

输出：`broadcast_success` `broadcast_error`

> Android 8+ 静态注册的接收器收不到隐式广播，发给第三方应用一般要给 package 或 component。broadcast_success 只表示已发出，不代表有人接收。

### ACTION_MEDIA_CONTROL

媒体控制：向当前播放的应用发送播放/暂停/切歌等媒体键

前置：需 Shizuku 或 root

| 参数 | 类型 | 说明 |
|---|---|---|
| `cmd` | play / pause / play_pause / next / prev / previous / stop | 媒体键；play_pause=播放/暂停切换，prev 与 previous 相同（默认 play） |

输出：`media_success` `media_error`

### ACTION_CALL_PHONE

打开系统拨号界面并填好号码（不拨出，需用户自己点拨打）

| 参数 | 类型 | 说明 |
|---|---|---|
| `phone_number` ★ | string | 电话号码，支持 ${var} |

输出：`call_success`

> 要直接拨出请用 ACTION_CALL_DIRECT（推荐），不要用 CALL_PHONE + 点击拨打键模拟。

### ACTION_CALL_DIRECT

直接拨出电话（TelecomManager.placeCall，无需点 UI，后台可用）

前置：需 CALL_PHONE 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `phone_number` ★ | string | 电话号码，支持 ${var}；只允许数字和 +*#,;()- 空格，最多 40 位 |
| `sim` | string_or_number | 双卡选卡：auto=系统默认，1=卡1，2=卡2（默认 auto） |

输出：`call_success` `call_error`

> 拨号优先用本节点。提交拨号即返回，不等接通；要等通话结束接 ACTION_WAIT_CALL_END。

### ACTION_WAIT_CALL_END

阻塞等待当前通话挂断（每秒轮询通话状态）

前置：需 READ_PHONE_STATE 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `timeout_ms` | string_or_number | 最长等待毫秒，超时 call_ended=false（默认 600000） |

输出：`call_ended` `call_error`

> 进入时没有通话且 3 秒内也没出现通话，直接返回 call_ended=true（视为已结束）。

### ACTION_WAIT_CALL_ANSWER

阻塞等待通话进入通话中（OFFHOOK）

前置：需 READ_PHONE_STATE 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `timeout_ms` | string_or_number | 最长等待毫秒，超时 call_answered=false（默认 120000） |

输出：`call_answered` `call_error`

> 5 秒内既没响铃也没进入通话就返回 false，不会等满超时。去电一拨出系统即为 OFFHOOK，不代表对方已接听；来电则表示已接起。

### ACTION_GET_CALL_STATE

读取当前电话状态

前置：需 READ_PHONE_STATE 权限

输出：`call_state` `call_state_name` `call_success` `call_error`

> call_state：0=空闲 1=响铃 2=通话中；call_state_name：idle/ringing/offhook。

### ACTION_GET_SIM_INFO

读取 SIM 卡数量、运营商、号码

前置：需 READ_PHONE_STATE 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | 概要字符串存到的变量名（默认 sim_summary） |

输出：`sim_count` `sim_carriers` `sim_numbers` `sim_summary` `sim_success` `sim_error`

> sim_carriers / sim_numbers 为逗号分隔；很多 SIM 卡不存本机号码，sim_numbers 常为空。

### ACTION_READ_CALL_LOG

读最近一条通话记录的号码

前置：需 READ_CALL_LOG 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `direction` | incoming / outgoing / all | incoming=已接来电，outgoing=去电，all=全部（含未接/拒接）（默认 incoming） |
| `output_var` | string | 号码另存到的变量名（call_log_number 总会写）（默认 call_log_number） |

输出：`call_log_found` `call_log_number` `call_log_type` `call_log_date`

> 通话记录一般在通话结束后才写入，响铃时多半查不到。call_log_type：1=来电 2=去电 3=未接 5=拒接；call_log_date 为毫秒时间戳。

### ACTION_SCREENSHOT

截取当前屏幕（shell screencap），输出 base64 JPEG 或文件路径

前置：需 Shizuku 或 root（screencap）

| 参数 | 类型 | 说明 |
|---|---|---|
| `format` | base64 / file | base64=JPEG 的 base64 字符串，file=PNG 文件绝对路径（默认 base64） |
| `quality` | string_or_number | base64 时的 JPEG 质量 1–100（默认 80） |
| `output_var` | string | 结果存到的变量名（format=file 时存的是路径）（默认 screenshot_base64） |

输出：`screenshot_success` `screenshot_error` `screenshot_width` `screenshot_height` `screenshot_base64`

> 截图文件写在 DCIM/habitat_screenshot_<时间戳>.png，不会自动删除。节点必须带 params（可为 {}），缺 params 字段时什么都不做。

### ACTION_TEXT_TO_SPEECH

用系统 TTS 朗读文本，读完才继续

前置：需系统已装 TTS 引擎

| 参数 | 类型 | 说明 |
|---|---|---|
| `text` ★ | string | 要朗读的文本，支持 ${var} |
| `language` | zh / en / ja / ko / fr / de / es / pt / ru / it | 语言代码，支持 ${var}；不填或不支持时用系统语言 |
| `pitch` | string_or_number | 音调，1.0 正常，范围 0.1–4.0（默认 1.0） |
| `speed` | string_or_number | 语速，1.0 正常，范围 0.1–4.0（默认 1.0） |
| `timeout_ms` | string_or_number | 初始化+朗读总超时毫秒（最小 1000）（默认 120000） |

输出：`tts_success` `tts_error`

### ACTION_PLAY_MEDIA

播放本地媒体文件（只出声），播完或超时才继续

| 参数 | 类型 | 说明 |
|---|---|---|
| `path` ★ | string | 媒体文件路径，支持 ${var} |
| `output_var` | string | 成功时额外写 true 的变量名 |
| `loop` | bool | 循环播放（默认 false） |
| `timeout_ms` | string_or_number | 等待播放结束的毫秒数（最小 1000）；不填=文件时长+3 秒，时长未知时 60 秒 |

输出：`play_success` `play_error` `play_completed` `play_duration_ms`

> 到超时就停止播放。play_success 只表示开始播放成功，是否自然播完看 play_completed；loop=true 时永远播不完，到 timeout_ms 停止且 play_completed=false。

### ACTION_RECORD_AUDIO

用麦克风录一段 m4a 音频，录完才继续

前置：需 RECORD_AUDIO 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `duration` | string_or_number | 录制毫秒数，范围 500–60000（默认 10000） |
| `path` | string | 输出文件路径，支持 ${var}；不填存应用私有目录 habitat_record_<时间戳>.m4a |
| `output_var` | string | 文件路径另存到的变量名（默认 record_path） |

输出：`record_path` `record_success` `record_error`

> 只录麦克风，不是通话录音。

### ACTION_CAPTURE_PHOTO

用相机静默拍一张 JPEG（无预览）

前置：需 CAMERA 权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `path` | string | 输出文件路径，支持 ${var}；不填存应用外部私有目录 habitat_photo_<时间戳>.jpg |
| `output_var` | string | 文件路径另存到的变量名（默认 photo_path） |

输出：`photo_success` `photo_error` `photo_path`

> 用第一个摄像头（通常是后置）；相机被其他应用占用时失败。

### ACTION_SET_WALLPAPER

把图片文件设为壁纸

| 参数 | 类型 | 说明 |
|---|---|---|
| `path` ★ | string | 图片文件路径，支持 ${var} |

输出：`wallpaper_success` `wallpaper_error`

### ACTION_SAY_TO_FILE

用系统 TTS 把文本合成为 wav 文件（不出声）

前置：需系统已装 TTS 引擎

| 参数 | 类型 | 说明 |
|---|---|---|
| `text` ★ | string | 要合成的文本，支持 ${var} |
| `language` | zh / en / ja / ko | 语言代码；其他值用系统语言（默认 zh） |
| `path` | string | 输出 .wav 路径，支持 ${var}；只能写本应用目录或共享存储；不填存应用私有目录 tts_<时间戳>.wav |
| `output_var` | string | 文件路径存到的变量名（默认 tts_file） |

输出：`say_success` `say_error` `tts_file`

> 合成最多等 30 秒，超长文本可能超时失败。

### ACTION_HTTP_REQUEST

发送 HTTP 请求，响应体存入变量

前置：需网络

| 参数 | 类型 | 说明 |
|---|---|---|
| `url` ★ | string | 请求 URL，支持 ${var} |
| `method` | string | GET / POST / PUT / DELETE / PATCH 等（PATCH 以 POST + X-HTTP-Method-Override 发送）（默认 GET） |
| `headers` | string | 请求头字符串：JSON 对象字符串（如 {"Authorization":"Bearer x"}）或 Key:Value;Key2:Value2，支持 ${var} |
| `body` | string | 请求体字符串，支持 ${var}；只在 POST/PUT/PATCH 发送，未设 Content-Type 时按 application/json |
| `output_var` | string | 响应体另存到的变量名（http_response 总会写） |
| `timeout` | string_or_number | 连接/读取超时毫秒（最小 1000）（默认 30000） |
| `max_response_bytes` | string_or_number | 响应体上限字节，超出截断并置 http_truncated=true（默认 5242880） |

输出：`http_success` `http_error` `http_status_code` `http_response` `http_truncated`

> headers / body 要写成字符串（JSON 转义成字符串）；写成嵌套 JSON 对象会被转成 {k=v} 文本而失效。请求失败不抛错：看 http_success（状态码 200–399 为 true），要按节点出错处理可加 require=http_success。对不稳定的第三方服务设合理 timeout（失败默认跳过继续）。

### ACTION_NETWORK_STATUS

查询是否联网、网络类型，可选查 IP

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | status / ip / all | status=只查连接与类型；ip / all=另外写 wifi_ip、mobile_ip（默认 status） |

输出：`network_connected` `network_type` `wifi_ip` `mobile_ip` `network_success` `network_error`

> network_type：wifi / cellular / ethernet / other / none。mobile_ip 实为第一个非回环 IPv4 地址，连着 WiFi 时可能就是 WiFi 地址。

### ACTION_GET_WEATHER

按经纬度查当前天气（Open-Meteo，免 key）

前置：需网络

| 参数 | 类型 | 说明 |
|---|---|---|
| `latitude` ★ | string_or_number | 纬度 -90–90，支持 ${var} |
| `longitude` ★ | string_or_number | 经度 -180–180，支持 ${var} |
| `output_var` | string | 原始响应 JSON 存到的变量名（默认 weather_json） |

输出：`weather_success` `weather_error` `weather_temperature` `weather_wind_speed` `weather_wind_direction` `weather_code` `weather_json`

> 温度 ℃、风速 km/h；连接/读取超时固定各 10 秒。weather_code 为 WMO 代码：0 晴，1–3 多云/阴，45/48 雾，51–67 毛毛雨/雨，71–77 雪，80–82 阵雨，85/86 阵雪，95–99 雷暴。

### ACTION_GEOCODE

地址与经纬度互查（OpenStreetMap Nominatim，免 key）

前置：需网络

| 参数 | 类型 | 说明 |
|---|---|---|
| `address` | string | 地址 → 查经纬度，支持 ${var}；与坐标同时给时优先用地址 |
| `latitude` | string_or_number | 纬度 → 逆查地址，支持 ${var}；不给 address 时与 longitude 一起必填 |
| `longitude` | string_or_number | 经度 → 逆查地址，支持 ${var}；不给 address 时与 latitude 一起必填 |

输出：`geocode_success` `geocode_error` `latitude` `longitude` `address`

> 地址查询取第一个结果写 latitude/longitude，逆查写 address，会覆盖同名变量（如 ACTION_GET_LOCATION 写的 latitude/longitude）。境外第三方服务，可能慢或不可达；连接/读取超时固定各 10 秒。

### ACTION_GET_TRAFFIC

读取开机以来整机累计收发流量（字节）

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | 可读汇总字符串存到的变量名（默认 traffic_summary） |

输出：`traffic_rx` `traffic_tx` `traffic_total` `traffic_summary` `traffic_success` `traffic_error`

### ACTION_READ_SCREEN

在当前界面找关键字，或抓取全屏可见文字

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `keyword` | string | 要找的文字（不区分大小写，匹配文本或描述），支持 ${var}；为空时 screen_data 恒为 false |
| `output_var` | string | 给了就把全屏可见文字（换行分隔）存到该变量 |

输出：`screen_data`

> screen_data 是「是否找到 keyword」的布尔值，不是屏幕文字；要文字请设 output_var。

### ACTION_READ_SMS

读收件箱短信：按发件人/内容过滤，可提取验证码

前置：需 READ_SMS 权限（没有时回退 Shizuku/root shell）

| 参数 | 类型 | 说明 |
|---|---|---|
| `filter_by` | latest / sender / content / both | latest=最新一条，sender=按发件人，content=按内容，both=两者都要匹配（默认 latest） |
| `sender` | string | 发件人包含的文字（不区分大小写），支持 ${var}；filter_by=sender/both 时生效 |
| `content` | string | 内容包含的文字（不区分大小写），支持 ${var}；filter_by=content/both 时生效；extract_code=true 时不用 |
| `max_scan` | string_or_number | 最多扫描最近几条，范围 1–500（默认 20） |
| `extract_code` | bool | true=只接受能提取到验证码的短信（4–8 位数字，或含数字的 6–12 位字母数字）（默认 false） |
| `within_minutes` | string_or_number | 只看最近 N 分钟内的短信；不填不限 |

输出：`sms_found` `sms_error` `sms_date` `sms_sender` `sms_content` `verification_code`

> 给了 sender/content 必须同时设 filter_by=sender/content/both，默认 latest 会忽略它们。extract_code=true 时「内容匹配」改为「提取到验证码」。sms_date 为毫秒时间戳。

### ACTION_GET_APP_INFO

读取前台应用包名和 Activity（经无障碍）

前置：需无障碍

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | 包名存到的变量名（默认 current_app） |
| `activity_var` | string | Activity 类名存到的变量名（默认 current_activity） |

输出：`current_app` `current_activity`

> 无障碍未开时写 unknown；需要 shell 兜底请用 ACTION_FOREGROUND_APP。

### ACTION_FOREGROUND_APP

获取前台应用包名/Activity（先无障碍，拿不到再走 shell dumpsys）

前置：需无障碍或 Shizuku/root

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | 包名另存到的变量名（默认 foreground_package） |
| `activity_var` | string | Activity 类名另存到的变量名（默认 foreground_activity） |
| `use_shell` | bool | true=跳过无障碍，直接用 shell（默认 false） |

输出：`foreground_package` `foreground_success` `foreground_activity` `foreground_class`

> 拿不到时写 unknown 且 foreground_success=false；foreground_class 为短类名。节点必须带 params（可为 {}），缺 params 字段时什么都不做。

### ACTION_APP_SEARCH

搜索已安装应用，结果为 JSON 数组

| 参数 | 类型 | 说明 |
|---|---|---|
| `query` | string | 匹配包名或应用名（不区分大小写），支持 ${var}；不填返回全部 |
| `include_system` | bool | 是否包含系统应用（只认 true / "true"）（默认 false） |
| `output_var` | string | 结果另存到的变量名（app_list 总会写）（默认 app_list） |
| `max_results` | int | 最多返回条数（默认 100） |
| `sort_by` | name / package / install_time | name=按应用名，package=按包名，install_time=按安装时间（新的在前）（默认 name） |

输出：`app_list` `app_count` `app_search_success` `app_search_error`

> 每项含 package、name、is_system、version_name、version_code。Android 11+ 受包可见性限制，没有桌面图标的应用可能搜不到。节点必须带 params（可为 {}），缺 params 字段时什么都不做。

### ACTION_GET_LOCATION

读取系统最后一次已知位置（GPS/网络取较新者）

前置：需定位权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | "纬度,经度" 字符串存到的变量名（默认 location_summary） |

输出：`location_success` `location_error` `latitude` `longitude` `location_accuracy` `location_time` `location_summary`

> 不主动定位：从没定位过会失败，结果可能是旧位置（看 location_time 毫秒时间戳）。location_accuracy 单位米。

### ACTION_READ_NOTIFICATIONS

读取通知栏当前通知列表（JSON 数组）

前置：需通知使用权

| 参数 | 类型 | 说明 |
|---|---|---|
| `output_var` | string | JSON 另存到的变量名（notifications_json 总会写）（默认 notifications_json） |
| `limit` | string_or_number | 最多条数，范围 1–50（默认 20） |
| `package` | string | 只要该包名的通知（精确匹配），支持 ${var} |

输出：`notifications_success` `notification_success` `notifications_json` `notification_count` `notification_error`

> 每项含 package、title、text、post_time。notification_success 是 notifications_success 的旧名，值相同。宿主未注入通知监听源时只能读到本应用自己的通知，并按失败处理。

### ACTION_AI_CHAT

调用端侧本地大模型对话，回复存入变量

前置：需宿主注入 LLM 服务且本地模型已加载

| 参数 | 类型 | 说明 |
|---|---|---|
| `prompt` ★ | string | 提示词，支持 ${var} |
| `output_var` | string | 回复存到的变量名（默认 llm_result） |

输出：`llm_success` `llm_result` `llm_error`

> 模型未加载时 llm_success=false 并写 llm_error；推理抛错时错误文本也会写进 output_var，判断成败看 llm_success。Habitat 自带实现推理失败会返回以 "Error:" 开头的文本且 llm_success 仍为 true。

### ACTION_SEND_NOTIFICATION

发一条系统通知

前置：宿主 App 注册；Android 13+ 需通知权限（POST_NOTIFICATIONS）

| 参数 | 类型 | 说明 |
|---|---|---|
| `title` | string | 通知标题（默认 Habitat 通知） |
| `message` | string | 通知正文（默认 这是一条来自 Habitat 的消息。） |

> title / message 原样显示，不做 ${var} 插值。不写任何输出变量。

### ACTION_DYNAMIC_ISLAND

屏幕顶部弹出灵动岛样式的胶囊提示，到时自动消失

前置：宿主 App 注册；需悬浮窗权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `message` ★ | string | 显示的文字（单行，过长截断），支持 ${var} |
| `duration_ms` | int | 显示毫秒数（默认 3000） |

> duration_ms 必须是数字，写成字符串会被忽略按 3000。不阻塞，显示后立即执行下一节点；没有悬浮窗权限时静默失败，不写任何变量。

### ACTION_MINI_SCREEN

悬浮小屏：给用户实时显示文字、图片、进度

前置：宿主 App 注册；需开启 Habitat 无障碍服务或悬浮窗权限

| 参数 | 类型 | 说明 |
|---|---|---|
| `action` | show / append / progress / hide / clear | show=替换内容，append=追加一段，progress=只更新进度（和标题），hide=隐藏但保留内容，clear=隐藏并清空（默认 show） |
| `title` | string | 标题，支持 ${var}，最多 60 字 |
| `text` | string | 正文，支持 ${var}，最多 4000 字 |
| `image` | string | 图片：绝对路径（经路径守卫）或 base64（可带 data:image/...;base64, 前缀），支持 ${var} |
| `progress` | string_or_number | 进度 0–100；action=progress 时必填 |
| `position` | top / center / bottom | 位置；不填沿用上次（初始 top），用户拖动过则按用户位置 |
| `duration_ms` | string_or_number | 大于 0 时到时自动隐藏；0=一直显示（默认 0） |

输出：`mini_screen_success` `mini_screen_shown` `mini_screen_error`

> show/append 时 text、image、title、progress 至少给一个。不阻塞。用户在本次运行中关掉小屏后，后续写入只更新内容不再弹出（mini_screen_shown=false）。来源标签由系统标注，不能用参数指定。
