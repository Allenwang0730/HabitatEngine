package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** CORE 模块：逻辑控制、变量与数据、文件、通用查询、异常处理、基础 UI。 */
internal object CoreSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.CONDITION_SWITCH, "按条件表达式走 true / false 分支",
            params = listOf(
                ParamSpec("expression", ParamType.STRING, "条件：变量名 运算符 值（如 battery_level > 20），或单个布尔变量名", required = true),
            ),
            notes = "运算符 ==、!=、>、<、>=、<=、contains、startswith、endswith；== / != 按字符串比较，> < >= <= 按数字比较（非数字当 0），" +
                "contains/startswith/endswith 不区分大小写。左边是变量名（不要写 \${}），右边是字面值，值不加引号；> 和 < 两侧必须有空格；" +
                "未定义变量按字符串 null 比较。分支写在节点的 branches（true/false → 节点 id），不走 next，缺的分支即该路径结束。",
        ),
        NodeSpec(
            F.CONDITION_ADVANCED_SWITCH, "高级条件分支：表达式先插值，两边都可以是变量或字面值",
            params = listOf(
                ParamSpec("expression", ParamType.STRING, "条件表达式，支持 \${var}，如 \${score} > 10、is_daytime；不填则用 var/operator/value 组装"),
                ParamSpec("var", ParamType.STRING, "拆分写法：左侧变量名"),
                ParamSpec(
                    "operator", ParamType.STRING, "拆分写法：运算符",
                    enum = listOf("==", "!=", ">", "<", ">=", "<=", "contains", "startswith", "endswith"), aliases = listOf("op"),
                ),
                ParamSpec("value", ParamType.STRING_OR_NUMBER, "拆分写法：右侧值"),
            ),
            notes = "插值后每一侧若是已定义的变量名就取其值，否则当字面值；数字比较只认数字类型变量，字符串变量请写成 \${var}。" +
                "contains/startswith/endswith 不区分大小写且两侧要有空格；内置 is_daytime / is_nighttime（8:00–17:59 为白天）。" +
                "分支写在 branches（true/false → 节点 id），不走 next。",
        ),
        NodeSpec(
            F.ACTION_DELAY, "暂停指定毫秒数",
            params = listOf(
                ParamSpec("millis", ParamType.STRING_OR_NUMBER, "延迟毫秒数，上限 60000（超出截断），支持 \${var}", default = "0", aliases = listOf("ms")),
            ),
            notes = "超过 60 秒的等待用 ACTION_WAIT_UNTIL_TIME。",
        ),
        NodeSpec(
            F.ACTION_LOOP, "计数或条件循环：每次进入判断是跳进循环体还是走 next 结束",
            params = listOf(
                ParamSpec("mode", ParamType.STRING, "count=计数循环，while=条件循环", enum = listOf("count", "while"), default = "count"),
                ParamSpec("times", ParamType.STRING_OR_NUMBER, "count 模式：循环次数，支持 \${var}", default = "1"),
                ParamSpec("counter_var", ParamType.STRING, "count 模式：当前轮次（从 1 起）写到的变量名", default = "loop_index"),
                ParamSpec("condition_expr", ParamType.STRING, "while 模式：条件，只支持 变量名 == 值 / 变量名 != 值"),
                ParamSpec("max_iterations", ParamType.STRING_OR_NUMBER, "while 模式：最多迭代次数", default = "100"),
                ParamSpec("body_node", ParamType.STRING, "循环体第一个节点的 id", required = true, nodeRef = true),
            ),
            outputs = listOf("loop_index"),
            notes = "循环体最后一个节点的 next 要指回本循环节点；本节点的 next 是循环结束后的去向。count 模式结束时 counter_var 重置为 0。",
        ),
        NodeSpec(
            F.ACTION_TRY_CATCH, "按上一个节点是否失败走 success / error 分支",
            params = listOf(
                ParamSpec("catch_var", ParamType.STRING, "出错时写入错误信息的变量名", default = "exception_msg"),
            ),
            outputs = listOf("exception_msg"),
            notes = "只看紧挨着的上一个节点：失败指抛异常、超时或 require 不满足；多数节点出错只是 xxx_success=false，" +
                "要在上一节点加 require（如 file_success）才算失败。分支写在 branches（success/error → 节点 id），不走 next，success 分支也要写，缺的分支即该路径结束。",
        ),
        NodeSpec(
            F.ACTION_LOG, "输出一条调试日志",
            params = listOf(
                ParamSpec("message", ParamType.STRING, "日志内容，支持 \${var}"),
                ParamSpec("level", ParamType.STRING, "日志级别", enum = listOf("debug", "info", "warn", "error"), default = "info"),
            ),
            notes = "只写 Logcat（tag HabitatLog），不进工作流运行日志；要让用户看到请用 ACTION_TOAST 或 ACTION_WORKSPACE。",
        ),
        NodeSpec(
            F.ACTION_SET_VARIABLE, "设置一个变量",
            params = listOf(
                ParamSpec("name", ParamType.STRING, "变量名", required = true, aliases = listOf("key")),
                ParamSpec("value", ParamType.ANY, "变量值，任意 JSON 值；字符串支持 \${var}；为 null 或转换失败时删除该变量"),
                ParamSpec(
                    "type", ParamType.STRING, "类型转换，auto=保持原类型",
                    enum = listOf("auto", "string", "int", "bool", "float"), default = "auto",
                ),
            ),
            notes = "含 \${var} 的值插值后是字符串；后续要做数字运算/比较时设 type=int 或 float。",
        ),
        NodeSpec(
            F.ACTION_TEXT_OPERATION, "文本处理：替换、截取、拆分、大小写、去空白、拼接",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING, "操作类型", required = true,
                    enum = listOf("replace", "substring", "split", "uppercase", "lowercase", "trim", "append", "prepend"),
                    aliases = listOf("operation"),
                ),
                ParamSpec("input", ParamType.STRING, "输入：变量名（取其值）或字面文本（支持 \${var}）", aliases = listOf("source_var")),
                ParamSpec("old", ParamType.STRING, "replace：要被替换的文字，支持 \${var}", aliases = listOf("search")),
                ParamSpec("new", ParamType.STRING, "replace：替换成的文字，支持 \${var}", aliases = listOf("replace_with")),
                ParamSpec("start", ParamType.STRING_OR_NUMBER, "substring：起始下标（含）", default = "0"),
                ParamSpec("end", ParamType.STRING_OR_NUMBER, "substring：结束下标（不含），默认到末尾"),
                ParamSpec("delimiter", ParamType.STRING, "split：分隔符", default = ","),
                ParamSpec("text", ParamType.STRING, "append/prepend：要拼接的文字，支持 \${var}"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "text_output"),
            ),
            outputs = listOf("text_output"),
            notes = "split 的结果是用换行连接的一个字符串，不是数组；要真正的数组用 ACTION_ARRAY（action=set）。",
        ),
        NodeSpec(
            F.ACTION_MATH, "两个数的四则、取模、乘方运算",
            params = listOf(
                ParamSpec(
                    "operation", ParamType.STRING, "运算类型", required = true,
                    enum = listOf("add", "subtract", "multiply", "divide", "modulo", "power"),
                ),
                ParamSpec("a", ParamType.STRING_OR_NUMBER, "第一个操作数：数字、\${var} 或数字变量名", default = "0", aliases = listOf("operand1_var")),
                ParamSpec(
                    "b", ParamType.STRING_OR_NUMBER, "第二个操作数：数字、\${var} 或数字变量名", default = "0",
                    aliases = listOf("operand2", "operand2_var"),
                ),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "math_result"),
            ),
            outputs = listOf("math_result"),
            notes = "结果总是小数（如 5.0），CONDITION_SWITCH 用 == 比较时要写 5.0，或改用 > / <；除数为 0 时结果为 0；无法解析的操作数按 0。",
        ),
        NodeSpec(
            F.ACTION_CLIPBOARD, "读取或写入系统剪贴板",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "get=读取，set=写入", required = true, enum = listOf("get", "set")),
                ParamSpec("text", ParamType.STRING, "要写入的文字（action=set 时必填，支持 \${var}）"),
                ParamSpec("output_var", ParamType.STRING, "读取结果存到的变量名", default = "clipboard_content"),
            ),
            outputs = listOf("clipboard_content", "clipboard_success", "clipboard_error"),
            notes = "Android 10+ 应用在后台时系统禁止读剪贴板，get 可能得到空串而 clipboard_success 仍为 true。",
        ),
        NodeSpec(
            F.ACTION_PARSE_JSON, "解析 JSON 字符串并按路径取值",
            params = listOf(
                ParamSpec("json", ParamType.STRING, "JSON 文本（支持 \${var}）或存着 JSON 的变量名", required = true),
                ParamSpec("path", ParamType.STRING, "取值路径，如 data.items[0].name；不填返回整个 JSON"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "parsed_json"),
            ),
            outputs = listOf("parsed_json", "parsed_value", "parse_success", "parse_error"),
            notes = "结果都是字符串（对象/数组为格式化 JSON）；路径不存在时结果是字符串 null，但 parse_success 仍为 true。",
        ),
        NodeSpec(
            F.ACTION_PARSE_XML, "解析 XML 字符串，按标签路径取元素文本或属性",
            params = listOf(
                ParamSpec("xml", ParamType.STRING, "XML 文本（支持 \${var}）或存着 XML 的变量名", required = true),
                ParamSpec("xpath", ParamType.STRING, "点分标签路径，如 root.item.name（不是真正的 XPath）；不填返回根元素全部文本"),
                ParamSpec("attribute", ParamType.STRING, "取该属性值而不是元素文本"),
                ParamSpec("output_var", ParamType.STRING, "结果另存到的变量名（xml_parsed_value 总会写）"),
            ),
            outputs = listOf("xml_parsed_value", "xml_success", "xml_error"),
            notes = "匹配到多个元素时结果是 JSON 数组字符串。",
        ),
        NodeSpec(
            F.ACTION_BASE64, "Base64 编码或解码（UTF-8 文本）",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "encode=编码，decode=解码", required = true, enum = listOf("encode", "decode")),
                ParamSpec("data", ParamType.STRING, "要编码/解码的文字（支持 \${var}）", required = true),
                ParamSpec("output_var", ParamType.STRING, "结果存到的变量名", default = "base64_result"),
            ),
            outputs = listOf("base64_result", "base64_success"),
        ),
        NodeSpec(
            F.ACTION_FILE_OPERATION, "文件读、写、复制、删除、存在检查、列目录",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING, "操作类型", required = true,
                    enum = listOf("read", "write", "copy", "delete", "exists", "list"),
                ),
                ParamSpec("path", ParamType.STRING, "文件或目录路径，支持 \${var}", required = true),
                ParamSpec("content", ParamType.STRING, "write：要写入的内容，支持 \${var}"),
                ParamSpec("append", ParamType.BOOL, "write：true=追加，false=覆盖", default = "false"),
                ParamSpec("dest", ParamType.STRING, "copy 时必填：目标路径；path 是目录时为目标目录，复制其中最新修改的文件"),
                ParamSpec("recursive", ParamType.BOOL, "delete 目录时必须设为 true", default = "false"),
                ParamSpec("output_var", ParamType.STRING, "read 的内容 / list 的 JSON 数组另存到的变量名（file_result 总会写）"),
            ),
            outputs = listOf(
                "file_result", "file_exists", "file_is_directory", "file_is_file", "file_list", "file_count",
                "file_copied_from", "file_success", "file_error",
            ),
            requires = "访问共享存储（/sdcard）需「所有文件访问」权限",
            notes = "路径只允许本应用目录和共享存储；系统目录、其它应用私有目录被拒绝，存储根与一级公共目录（DCIM、Download…）不能整体删除。" +
                "list 结果每项形如 [F] a.txt / [D] dir。",
        ),
        NodeSpec(
            F.ACTION_WORKSPACE, "读写本机工作区条目（结果、笔记、图片），用户在 App「工作区」页查看",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "操作类型", enum = listOf("write", "append", "read", "list", "delete"), default = "write"),
                ParamSpec("name", ParamType.STRING, "条目显示名（write/append 必填；read/delete 可用它或 id 定位）"),
                ParamSpec("id", ParamType.STRING, "read/delete：条目 id，优先于 name"),
                ParamSpec("content", ParamType.STRING, "write/append：内容，支持 \${var}；kind=json 时须为合法 JSON，kind=image 时为 base64"),
                ParamSpec(
                    "kind", ParamType.STRING, "write：条目类型 text（默认）/ json / image；list：按类型筛选，可多一个 record；其余为别名",
                    enum = listOf("text", "json", "image", "record", "txt", "markdown", "md", "img", "png", "jpg", "jpeg"),
                ),
                ParamSpec("image_path", ParamType.STRING, "kind=image：图片文件路径（代替 content），上限 8MB"),
                ParamSpec("tags", ParamType.STRING, "write/append：标签，逗号分隔"),
                ParamSpec("overwrite", ParamType.BOOL, "write：同名同类时覆盖；false 则自动改名为「名字 (2)」", default = "true"),
                ParamSpec("tag", ParamType.STRING, "list：按标签筛选"),
                ParamSpec("limit", ParamType.STRING_OR_NUMBER, "list：最多返回条数（1–200）", default = "20"),
                ParamSpec("output_var", ParamType.STRING, "read 的内容 / list 的 JSON 数组存到的变量名（默认 workspace_content / workspace_list）"),
            ),
            outputs = listOf(
                "workspace_id", "workspace_kind", "workspace_content", "workspace_path", "workspace_list", "workspace_count",
                "workspace_success", "workspace_error",
            ),
        ),
        NodeSpec(
            F.ACTION_SQLITE_QUERY, "对 .db 文件执行一条 SQL",
            params = listOf(
                ParamSpec("database", ParamType.STRING, "数据库文件路径，支持 \${var}", required = true),
                ParamSpec("query", ParamType.STRING, "一条 SQL 语句，支持 \${var}", required = true),
                ParamSpec("args", ParamType.ANY, "绑定到 ? 占位符的参数，JSON 数组字符串如 [\"a\",1]（不做 \${var} 插值）"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "sql_result"),
                ParamSpec("limit", ParamType.STRING_OR_NUMBER, "SELECT 最多返回行数（1–5000）", default = "500"),
                ParamSpec("create", ParamType.BOOL, "数据库文件不存在时是否创建", default = "false"),
            ),
            outputs = listOf("sql_result", "sql_rows", "sql_success", "sql_error"),
            requires = "访问共享存储（/sdcard）需「所有文件访问」权限",
            notes = "只有以 SELECT 开头的语句返回行（JSON 数组字符串）；其它语句（含 PRAGMA、WITH）结果为 OK，sql_rows 为受影响行数。" +
                "路径只允许本应用目录和共享存储。",
        ),
        NodeSpec(
            F.ACTION_ZIP, "ZIP 压缩、解压、列出条目",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "zip=压缩，unzip=解压，list=列出条目", enum = listOf("zip", "unzip", "list"), default = "zip"),
                ParamSpec("source", ParamType.STRING, "zip：要压缩的文件或目录；unzip/list：zip 文件路径。支持 \${var}", required = true),
                ParamSpec("dest", ParamType.STRING, "zip：输出 .zip 路径（默认 source 后加 .zip）；unzip：输出目录（默认与 zip 同名的目录）"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名：zip/unzip 为目标路径，list 为条目名 JSON 数组", default = "zip_result"),
                ParamSpec("delete_source", ParamType.BOOL, "成功后删除源（zip 只删单个文件，不删目录）", default = "false"),
                ParamSpec("max_unzip_bytes", ParamType.STRING_OR_NUMBER, "解压总大小上限（字节），超出即中止", default = "1073741824"),
            ),
            outputs = listOf("zip_result", "zip_count", "zip_success", "zip_error"),
            requires = "访问共享存储（/sdcard）需「所有文件访问」权限",
        ),
        NodeSpec(
            F.ACTION_CRYPTO, "用口令对文件做 AES-GCM 加密或解密",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "encrypt=加密，decrypt=解密", enum = listOf("encrypt", "decrypt"), default = "encrypt"),
                ParamSpec("path", ParamType.STRING, "输入文件路径，支持 \${var}", required = true),
                ParamSpec("password", ParamType.STRING, "口令，支持 \${var}", required = true),
                ParamSpec("output_path", ParamType.STRING, "输出路径；默认加密为 path 后加 .enc，解密去掉 .enc（不以 .enc 结尾则加 .dec）；不能与输入相同"),
                ParamSpec("output_var", ParamType.STRING, "保存输出路径的变量名", default = "crypto_result"),
            ),
            outputs = listOf("crypto_result", "crypto_success", "crypto_error"),
            requires = "访问共享存储（/sdcard）需「所有文件访问」权限",
            notes = "密文格式为本节点专用（PBKDF2 派生密钥 + AES-256-GCM），只能用本节点和同一口令解密；口令错误时解密失败，不会覆盖已有文件。",
        ),
        NodeSpec(
            F.ACTION_ARRAY, "数组变量操作：建立、追加、弹出、取值、长度、拼接、清空、删除、包含",
            params = listOf(
                ParamSpec(
                    "action", ParamType.STRING, "操作类型", required = true,
                    enum = listOf("set", "push", "pop", "get", "length", "join", "clear", "remove", "contains"),
                ),
                ParamSpec("var", ParamType.STRING, "数组变量名（不要写 \${}）", required = true),
                ParamSpec("value", ParamType.STRING, "set：按 delimiter 分隔的文本，或 JSON 数组字符串"),
                ParamSpec("item", ParamType.STRING, "push/remove/contains：元素"),
                ParamSpec("index", ParamType.STRING_OR_NUMBER, "get/remove：下标，负数从末尾数；get 默认 0，remove 不填则按 item 删除"),
                ParamSpec("delimiter", ParamType.STRING, "set/join：分隔符", default = ","),
                ParamSpec(
                    "output_var", ParamType.STRING,
                    "结果变量名：set/push/clear/length 为长度，pop/get 为元素，remove 按下标为元素、按 item 为布尔，join 为字符串，contains 为布尔",
                    default = "array_result",
                ),
            ),
            outputs = listOf("array_result"),
            notes = "value、item、index 不做 \${var} 插值；元素一律按字符串存。数组本体存回 var。",
        ),
        NodeSpec(
            F.ACTION_PARSE_DATETIME, "取当前时间、解析或格式化日期时间，可加减偏移",
            params = listOf(
                ParamSpec("action", ParamType.STRING, "now=当前时间，parse=解析字符串，format=格式化", enum = listOf("now", "parse", "format"), default = "now"),
                ParamSpec("input", ParamType.STRING_OR_NUMBER, "parse/format 时必填：日期时间字符串，format 也可给毫秒时间戳；支持 \${var}"),
                ParamSpec("input_format", ParamType.STRING, "input 的格式（java.time 模式）", default = "yyyy-MM-dd HH:mm:ss"),
                ParamSpec("format", ParamType.STRING, "输出格式（java.time 模式）", default = "yyyy-MM-dd HH:mm:ss"),
                ParamSpec("add_days", ParamType.STRING_OR_NUMBER, "加减天数（可为负）", default = "0"),
                ParamSpec("add_hours", ParamType.STRING_OR_NUMBER, "加减小时（可为负）", default = "0"),
                ParamSpec("add_minutes", ParamType.STRING_OR_NUMBER, "加减分钟（可为负）", default = "0"),
                ParamSpec("add_seconds", ParamType.STRING_OR_NUMBER, "加减秒数（可为负）", default = "0"),
                ParamSpec("output_var", ParamType.STRING, "格式化结果变量名", default = "datetime_result"),
            ),
            outputs = listOf(
                "datetime_result", "datetime_epoch", "datetime_year", "datetime_month", "datetime_day", "datetime_hour",
                "datetime_minute", "datetime_second", "datetime_weekday", "datetime_success", "datetime_error",
            ),
            notes = "按 LocalDateTime 解析，input_format 必须含时间部分（纯日期如 yyyy-MM-dd 会失败）；datetime_weekday 1=周一…7=周日。",
        ),
        NodeSpec(
            F.ACTION_GET_TIME, "获取当前时间及年月日时分秒",
            params = listOf(
                ParamSpec("format", ParamType.STRING, "时间格式（SimpleDateFormat 模式）", default = "yyyy-MM-dd HH:mm:ss"),
                ParamSpec("output_var", ParamType.STRING, "格式化时间另存到的变量名（current_time 总会写）", default = "current_time"),
                ParamSpec("timestamp", ParamType.BOOL, "目前不影响输出；毫秒时间戳总写在 current_timestamp", default = "false"),
            ),
            outputs = listOf(
                "current_time", "current_timestamp", "current_year", "current_month", "current_day", "current_hour",
                "current_minute", "current_second", "current_weekday", "time_success",
            ),
            notes = "current_weekday 0=周日…6=周六（与 ACTION_PARSE_DATETIME 的 1=周一不同）。",
        ),
        NodeSpec(
            F.ACTION_RANDOM, "生成随机整数、小数或布尔值",
            params = listOf(
                ParamSpec("min", ParamType.STRING_OR_NUMBER, "最小值（只取整数部分）", default = "0"),
                ParamSpec("max", ParamType.STRING_OR_NUMBER, "最大值（只取整数部分）", default = "100"),
                ParamSpec("type", ParamType.STRING, "结果类型", enum = listOf("int", "float", "boolean"), default = "int"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "random_value"),
            ),
            outputs = listOf("random_value", "random_success"),
            notes = "int 含两端，float 为 [min, max)；min > max 时自动交换。",
        ),
        NodeSpec(
            F.ACTION_WAIT_UNTIL_TIME, "等待到指定时刻再继续（每秒轮询）",
            params = listOf(
                ParamSpec("time", ParamType.STRING_OR_NUMBER, "目标时刻：clock 模式为 HH:mm 或 HH:mm:ss，timestamp 模式为毫秒时间戳；支持 \${var}", required = true),
                ParamSpec("mode", ParamType.STRING, "clock=钟点，timestamp=毫秒时间戳", enum = listOf("clock", "timestamp"), default = "clock"),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "最长等待毫秒（至少 1000）；默认 29 分钟，设了 node_timeout_ms 时默认 12 小时"),
                ParamSpec("output_var", ParamType.STRING, "是否等到的结果变量名（time_reached 总会写）", default = "time_reached"),
            ),
            outputs = listOf("time_reached", "wait_until_time"),
            notes = "执行器默认单节点超时 30 分钟：等待超过 29 分钟需同时设 node_timeout_ms=0（或足够大），否则 timeout_ms 会被截到 29 分钟。" +
                "clock 模式下今天已过的时刻顺延到明天；超时后 time_reached=false 并继续 next。",
        ),
        NodeSpec(
            F.ACTION_GET_BATTERY, "读取电量和充电状态",
            params = listOf(
                ParamSpec("output_var", ParamType.STRING, "电量另存到的变量名（battery_level 总会写）"),
            ),
            outputs = listOf("battery_level", "battery_charging", "battery_success", "battery_error"),
            notes = "battery_level 为 0–100；充满时 battery_charging 也为 true。",
        ),
        NodeSpec(
            F.ACTION_GET_DEVICE_INFO, "读取设备型号、Android 版本和屏幕尺寸",
            outputs = listOf(
                "device_model", "device_manufacturer", "device_brand", "device_android_version", "device_sdk",
                "device_screen_width", "device_screen_height", "device_density", "device_success", "device_error",
            ),
        ),
        NodeSpec(
            F.ACTION_GET_STORAGE_INFO, "读取内部存储空间（字节）",
            outputs = listOf(
                "storage_total", "storage_available", "storage_used", "storage_available_percent", "storage_success", "storage_error",
            ),
        ),
        NodeSpec(
            F.ACTION_GET_SENSOR, "读取一次光线、距离或加速度传感器的值",
            params = listOf(
                ParamSpec("type", ParamType.STRING, "传感器类型", enum = listOf("light", "proximity", "accel", "accelerometer"), default = "light"),
                ParamSpec("output_var", ParamType.STRING, "读数另存到的变量名（sensor_value 总会写）", default = "sensor_value"),
            ),
            outputs = listOf("sensor_value", "sensor_type", "sensor_success", "sensor_error"),
            notes = "只取首个读数的第一个分量（加速度为 X 轴）；3 秒内没有读数即失败。",
        ),
        NodeSpec(
            F.ACTION_GET_SCREEN_STATE, "读取屏幕是否亮着、是否锁屏",
            outputs = listOf("screen_on", "screen_locked", "screen_state_success", "screen_state_error"),
        ),
        NodeSpec(
            F.ACTION_GET_AUDIO_STATE, "读取是否在播放音乐、是否连着耳机",
            outputs = listOf("music_active", "headset_connected", "audio_state_success", "audio_state_error"),
            notes = "headset_connected 含有线/USB 耳机和蓝牙 A2DP。",
        ),
        NodeSpec(
            F.ACTION_GET_SETTING, "读取一项系统设置（只读）",
            params = listOf(
                ParamSpec("key", ParamType.STRING, "设置键名，如 screen_off_timeout、adb_enabled；支持 \${var}", required = true),
                ParamSpec("namespace", ParamType.STRING, "设置表", enum = listOf("system", "global", "secure"), default = "system"),
                ParamSpec("output_var", ParamType.STRING, "结果变量名", default = "setting_value"),
            ),
            outputs = listOf("setting_value", "setting_key", "setting_namespace", "setting_success", "setting_error"),
            notes = "值总是字符串；键不存在或无权读取时 setting_success=false（新版 Android 限制读取部分 secure/global 键）。",
        ),
        NodeSpec(
            F.ACTION_RETRY, "失败重试：反复执行目标节点（链），直到成功或次数用完",
            params = listOf(
                ParamSpec("target", ParamType.STRING, "要重试的第一个节点 id", required = true, nodeRef = true),
                ParamSpec("times", ParamType.STRING_OR_NUMBER, "总尝试次数（含第一次，至少 1）", default = "3"),
                ParamSpec("interval_ms", ParamType.STRING_OR_NUMBER, "两次尝试的间隔毫秒", default = "1000"),
            ),
            notes = "目标链最后一个节点的 next 要指回本节点；链上有节点失败就重试，成功或用完次数后走本节点 next。" +
                "多数节点出错只是 xxx_success=false，要给它加 require（如 require=file_success）才算失败。",
        ),
        NodeSpec(
            F.ACTION_ASSERT, "断言：条件为假时让本节点失败，进入错误处理",
            params = listOf(
                ParamSpec("condition", ParamType.STRING, "条件：变量名 运算符 值（如 x == 6），或布尔变量名", required = true),
                ParamSpec("message", ParamType.STRING, "失败时的错误信息（写入 last_error）"),
            ),
            notes = "失败按节点出错处理，可被 on_error、ACTION_TRY_CATCH、ACTION_RETRY 捕获。运算符 ==、!=、>、<、>=、<=（无 contains）；" +
                "两边先按变量名取值，取不到当字面值；不支持 \${var}，值不加引号。",
        ),
        NodeSpec(
            F.ACTION_TOAST, "弹出一条短提示",
            params = listOf(
                ParamSpec("message", ParamType.STRING, "提示文字，支持 \${var}"),
            ),
        ),
        NodeSpec(
            F.ACTION_VIBRATE, "振动一次",
            params = listOf(
                ParamSpec("duration", ParamType.STRING_OR_NUMBER, "振动时长毫秒（1–10000）", default = "200", aliases = listOf("ms")),
                ParamSpec("amplitude", ParamType.STRING_OR_NUMBER, "振动强度（1–255）", default = "128"),
            ),
        ),
    )
}
