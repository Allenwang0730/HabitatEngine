# Agent WebSocket 工具调用客户端（演示用）
# 默认经 adb forward 连手机：adb forward tcp:8931 tcp:8931
# 用法：
#   $env:AGENT_TOKEN = "<手机 Agent 页的令牌>"
#   .\scripts\agent_ws.ps1 -Tool screen
#   .\scripts\agent_ws.ps1 -Tool ACTION_LAUNCH_APP -Params '{"package_name":"com.android.settings"}'
#   .\scripts\agent_ws.ps1 -Tool ACTION_READ_SCREEN -Params '{"keyword":""}' -Token <令牌>
param(
    [string]$DeviceIp = "127.0.0.1",
    [int]$Port = 8931,
    [string]$Tool = "screen",
    [string]$Params = "{}",
    [string]$Id = "1",
    [string]$Token = $env:AGENT_TOKEN,
    [int]$TimeoutSec = 90
)

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Host "缺少令牌：传 -Token 或设置环境变量 AGENT_TOKEN（手机 Agent 页可查看）" -ForegroundColor Red
    exit 2
}

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ws.Options.SetRequestHeader("Authorization", "Bearer $Token")
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
$ct = $cts.Token
$uri = "ws://${DeviceIp}:${Port}/ws"
try {
    $null = $ws.ConnectAsync([uri]$uri, $ct).GetAwaiter().GetResult()

    $msg = @{ id = $Id; tool = $Tool } | ConvertTo-Json -Compress
    if ($Params -ne "{}") {
        $p = $Params | ConvertFrom-Json
        $msg = @{ id = $Id; tool = $Tool; params = $p } | ConvertTo-Json -Compress -Depth 5
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $null = $ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).GetAwaiter().GetResult()

    # 循环读取直到 EndOfMessage（大响应会分多帧）
    $buf = New-Object byte[] 65536
    $ms = [System.IO.MemoryStream]::new()
    do {
        $recv = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $ct).GetAwaiter().GetResult()
        if ($recv.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            $reason = $ws.CloseStatusDescription
            Write-Host "服务端关闭连接：$reason（令牌错误时为 unauthorized）" -ForegroundColor Red
            exit 1
        }
        $ms.Write($buf, 0, $recv.Count)
    } while (-not $recv.EndOfMessage)
    $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
} finally {
    $ws.Dispose()
    $cts.Dispose()
}

# 美化输出
$json = $text | ConvertFrom-Json
Write-Host ("id={0} ok={1}" -f $json.id, $json.ok) -ForegroundColor $(if ($json.ok) { 'Green' } else { 'Red' })
if ($json.error) { Write-Host "error: $($json.error)" -ForegroundColor Red }
if ($json.outputs) {
    $json.outputs.PSObject.Properties | ForEach-Object {
        $v = $_.Value
        if ($v -is [string] -and $v.Length -gt 500) { $v = $v.Substring(0, 500) + "..." }
        Write-Host ("  {0} = {1}" -f $_.Name, $v)
    }
}
