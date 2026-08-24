#!/bin/sh
# 提交前密钥扫描（git pre-commit hook 调用 / 也可手动跑）
# 命中疑似密钥 → 输出并 exit 1（拒绝提交），防止再犯硬编码密钥事故。
# 正常退出码 0 = 无敏感信息。
set -u

# 疑似密钥的正则（启发式）：
#  - sk- 前缀 + 长 token（如 DeepSeek/OpenAI）
#  - 长 base64 / hex（>=32 字符随机串）
#  - 私有密钥块、常见 API_KEY 赋值
PATTERNS="sk-[A-Za-z0-9_-]{20,}|-----BEGIN (RSA|OPENSSH|EC|PGP) PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,}|xox[baprs]-[A-Za-z0-9-]{10,}"

# 收集暂存区（stage）的文件
STAGED=$(git diff --cached --name-only --diff-filter=ACM )
if [ -z "$STAGED" ]; then
    exit 0
fi

FOUND=0
for f in $STAGED; do
    # 跳过二进制/无扩展名判断：用 git 确认是文本
    if git show ":$f" 2>/dev/null | grep -I -E "$PATTERNS" >/dev/null 2>&1; then
        echo "❌ 检测到疑似密钥，提交已阻止：$f"
        git show ":$f" 2>/dev/null | grep -I -n -E "$PATTERNS" | head -5
        echo "  → 请把密钥改为环境变量/配置文件（不提交），或从暂存区移除。"
        FOUND=1
    fi
done

if [ "$FOUND" -ne 0 ]; then
    echo "⚠️  提交被拒绝：项目中不允硬编码密钥（曾泄露 DeepSeek key）。"
    exit 1
fi

exit 0
