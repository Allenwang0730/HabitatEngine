# Agent WebSocket 工具调用客户端（演示用）
# 用法：
#   .\scripts\agent_ws.ps1 -Tool screen
#   .\scripts\agent_ws.ps1 -Tool ACTION_LAUNCH_APP -Params '{"package_name":"com.android.settings"}'
#   .\scripts\agent_ws.ps1 -Tool ACTION_READ_SCREEN -Params '{"keyword":""}'
param(
    [string]$DeviceIp = "127.0.0.1",
    [int]$Port = 8931,
    [string]$Tool = "screen",
    [string]$Params = "{}",
    [string]$Id = "1"
)

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ct = [System.Threading.CancellationToken]::None
$uri = "ws://${DeviceIp}:${Port}/ws"
$ws.ConnectAsync([uri]$uri, $ct).GetAwaiter().GetResult()

$msg = @{ id = $Id; tool = $Tool } | ConvertTo-Json -Compress
if ($Params -ne "{}") {
    $p = $Params | ConvertFrom-Json
    $msg = @{ id = $Id; tool = $Tool; params = $p } | ConvertTo-Json -Compress -Depth 5
}
$bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
$ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).GetAwaiter().GetResult()

$buf = New-Object byte[] 131072
$recv = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $ct).GetAwaiter().GetResult()
$text = [System.Text.Encoding]::UTF8.GetString($buf, 0, $recv.Count)
$ws.Dispose()

# 美化输出
$json = $text | ConvertFrom-Json
Write-Host ("id={0} ok={1} duration={2}ms" -f $json.id, $json.ok, $json.duration_ms) -ForegroundColor $(if ($json.ok) { 'Green' } else { 'Red' })
if ($json.error) { Write-Host "error: $($json.error)" -ForegroundColor Red }
if ($json.outputs) {
    $json.outputs.PSObject.Properties | ForEach-Object {
        $v = $_.Value
        if ($v -is [string] -and $v.Length -gt 500) { $v = $v.Substring(0, 500) + "..." }
        Write-Host ("  {0} = {1}" -f $_.Name, $v)
    }
}
