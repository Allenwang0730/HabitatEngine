package com.ailun.habitat.spec

import com.ailun.habitat.NodeHandlerFactory as F

/** MEDIA：截图、朗读、播放、录音、拍照、壁纸、语音合成到文件。 */
internal object MediaSpecs {
    val all: List<NodeSpec> = listOf(
        NodeSpec(
            F.ACTION_SCREENSHOT, "截取当前屏幕（shell screencap），输出 base64 JPEG 或文件路径",
            params = listOf(
                ParamSpec("format", ParamType.STRING, "base64=JPEG 的 base64 字符串，file=PNG 文件绝对路径", enum = listOf("base64", "file"), default = "base64"),
                ParamSpec("quality", ParamType.STRING_OR_NUMBER, "base64 时的 JPEG 质量 1–100", default = "80"),
                ParamSpec("output_var", ParamType.STRING, "结果存到的变量名（format=file 时存的是路径）", default = "screenshot_base64"),
            ),
            outputs = listOf("screenshot_success", "screenshot_error", "screenshot_width", "screenshot_height", "screenshot_base64"),
            requires = "需 Shizuku 或 root（screencap）",
            notes = "截图文件写在 DCIM/habitat_screenshot_<时间戳>.png，不会自动删除。节点必须带 params（可为 {}），缺 params 字段时什么都不做。",
        ),
        NodeSpec(
            F.ACTION_TEXT_TO_SPEECH, "用系统 TTS 朗读文本，读完才继续",
            params = listOf(
                ParamSpec("text", ParamType.STRING, "要朗读的文本，支持 \${var}", required = true),
                ParamSpec(
                    "language", ParamType.STRING, "语言代码，支持 \${var}；不填或不支持时用系统语言",
                    enum = listOf("zh", "en", "ja", "ko", "fr", "de", "es", "pt", "ru", "it"),
                ),
                ParamSpec("pitch", ParamType.STRING_OR_NUMBER, "音调，1.0 正常，范围 0.1–4.0", default = "1.0"),
                ParamSpec("speed", ParamType.STRING_OR_NUMBER, "语速，1.0 正常，范围 0.1–4.0", default = "1.0"),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "初始化+朗读总超时毫秒（最小 1000）", default = "120000"),
            ),
            outputs = listOf("tts_success", "tts_error"),
            requires = "需系统已装 TTS 引擎",
        ),
        NodeSpec(
            F.ACTION_PLAY_MEDIA, "播放本地媒体文件（只出声），播完或超时才继续",
            params = listOf(
                ParamSpec("path", ParamType.STRING, "媒体文件路径，支持 \${var}", required = true),
                ParamSpec("output_var", ParamType.STRING, "成功时额外写 true 的变量名"),
                ParamSpec("loop", ParamType.BOOL, "循环播放", default = "false"),
                ParamSpec("timeout_ms", ParamType.STRING_OR_NUMBER, "等待播放结束的毫秒数（最小 1000）；不填=文件时长+3 秒，时长未知时 60 秒"),
            ),
            outputs = listOf("play_success", "play_error", "play_completed", "play_duration_ms"),
            notes = "到超时就停止播放。play_success 只表示开始播放成功，是否自然播完看 play_completed；loop=true 时永远播不完，到 timeout_ms 停止且 play_completed=false。",
        ),
        NodeSpec(
            F.ACTION_RECORD_AUDIO, "用麦克风录一段 m4a 音频，录完才继续",
            params = listOf(
                ParamSpec("duration", ParamType.STRING_OR_NUMBER, "录制毫秒数，范围 500–60000", default = "10000"),
                ParamSpec("path", ParamType.STRING, "输出文件路径，支持 \${var}；不填存应用私有目录 habitat_record_<时间戳>.m4a"),
                ParamSpec("output_var", ParamType.STRING, "文件路径另存到的变量名", default = "record_path"),
            ),
            outputs = listOf("record_path", "record_success", "record_error"),
            requires = "需 RECORD_AUDIO 权限",
            notes = "只录麦克风，不是通话录音。",
        ),
        NodeSpec(
            F.ACTION_CAPTURE_PHOTO, "用相机静默拍一张 JPEG（无预览）",
            params = listOf(
                ParamSpec("path", ParamType.STRING, "输出文件路径，支持 \${var}；不填存应用外部私有目录 habitat_photo_<时间戳>.jpg"),
                ParamSpec("output_var", ParamType.STRING, "文件路径另存到的变量名", default = "photo_path"),
            ),
            outputs = listOf("photo_success", "photo_error", "photo_path"),
            requires = "需 CAMERA 权限",
            notes = "用第一个摄像头（通常是后置）；相机被其他应用占用时失败。",
        ),
        NodeSpec(
            F.ACTION_SET_WALLPAPER, "把图片文件设为壁纸",
            params = listOf(
                ParamSpec("path", ParamType.STRING, "图片文件路径，支持 \${var}", required = true),
            ),
            outputs = listOf("wallpaper_success", "wallpaper_error"),
        ),
        NodeSpec(
            F.ACTION_SAY_TO_FILE, "用系统 TTS 把文本合成为 wav 文件（不出声）",
            params = listOf(
                ParamSpec("text", ParamType.STRING, "要合成的文本，支持 \${var}", required = true),
                ParamSpec("language", ParamType.STRING, "语言代码；其他值用系统语言", enum = listOf("zh", "en", "ja", "ko"), default = "zh"),
                ParamSpec("path", ParamType.STRING, "输出 .wav 路径，支持 \${var}；只能写本应用目录或共享存储；不填存应用私有目录 tts_<时间戳>.wav"),
                ParamSpec("output_var", ParamType.STRING, "文件路径存到的变量名", default = "tts_file"),
            ),
            outputs = listOf("say_success", "say_error", "tts_file"),
            requires = "需系统已装 TTS 引擎",
            notes = "合成最多等 30 秒，超长文本可能超时失败。",
        ),
    )
}
