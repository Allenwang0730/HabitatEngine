# 验证 DeepSeek 生成 Habitat 工作流的正确率
# 用 WorkflowAIGenerator 的完整系统提示 + 结构校验（对齐 HabitatJson.validate + 引用检查）
param([string]$ApiKey = "[REMOVED]")

$sys = @'
你是 Habitat 安卓自动化引擎的工作流生成器。根据用户需求，生成一个 Habitat 工作流 JSON。
【工作流格式】{"name":"简短名称","description":"一句话说明","workflow":{"start_node_id":"起始id","nodes":{"节点id":{"id":"节点id","type":"节点类型","params":{...},"next":"下一id"},...}}}
规则：start_node_id 必须指向已存在节点；节点有 id/type/params/next；变量用 ${var} 引用；节点 id 唯一；只输出上面这一个 JSON 对象，不要多余文字。可选顶层 trigger（自动触发）。
【可用节点】（只用这些 type）
ACTION_SET_VARIABLE(name,value,type), ACTION_LOG(message), ACTION_DELAY(millis), CONDITION_SWITCH(expression,branches={"true":id,"false":id}), ACTION_TOAST(message), ACTION_GET_TIME(format,output_var), ACTION_MATH(operation,a,b,output_var), ACTION_TEXT_OPERATION(action,input,output_var), ACTION_CLIPBOARD(action,text,output_var), ACTION_PARSE_JSON(json,path,output_var), ACTION_HTTP_REQUEST(url,method,output_var), ACTION_LAUNCH_APP(package_name), ACTION_CLICK(target), ACTION_INPUT_TEXT(text), ACTION_READ_SCREEN(keyword,output_var), ACTION_FIND_ELEMENT(selector,search_mode,timeout_ms), ACTION_GLOBAL_KEY(key), ACTION_SHELL(command), ACTION_GET_BATTERY(output_var), ACTION_NETWORK_STATUS(action), ACTION_GET_DEVICE_INFO, ACTION_SCREENSHOT(format,output_var), ACTION_VIBRATE(duration), ACTION_TEXT_TO_SPEECH(text,language), ACTION_FILE_OPERATION(action,path,content), ACTION_AI_CHAT(prompt,output_var)
【示例】需求"读取网络状态，wifi则提示当前WiFi否则提示没有网络"→{"name":"网络检查","description":"读网络类型判断","workflow":{"start_node_id":"n1","nodes":{"n1":{"id":"n1","type":"ACTION_NETWORK_STATUS","params":{"action":"all"},"next":"n2"},"n2":{"id":"n2","type":"CONDITION_SWITCH","params":{"expression":"network_type == wifi"},"branches":{"true":"n3","false":"n4"}},"n3":{"id":"n3","type":"ACTION_TOAST","params":{"message":"当前WiFi"}},"n4":{"id":"n4","type":"ACTION_TOAST","params":{"message":"没有网络"}}}}}
只输出 JSON。
'@

# 白名单节点类型
$whitelist = @('ACTION_SET_VARIABLE','ACTION_LOG','ACTION_DELAY','CONDITION_SWITCH','ACTION_TOAST','ACTION_GET_TIME','ACTION_MATH','ACTION_TEXT_OPERATION','ACTION_CLIPBOARD','ACTION_PARSE_JSON','ACTION_HTTP_REQUEST','ACTION_LAUNCH_APP','ACTION_CLICK','ACTION_INPUT_TEXT','ACTION_READ_SCREEN','ACTION_FIND_ELEMENT','ACTION_GLOBAL_KEY','ACTION_SHELL','ACTION_GET_BATTERY','ACTION_NETWORK_STATUS','ACTION_GET_DEVICE_INFO','ACTION_SCREENSHOT','ACTION_VIBRATE','ACTION_TEXT_TO_SPEECH','ACTION_FILE_OPERATION','ACTION_AI_CHAT')

$cases = @(
    @{name="简单线性"; req="打开系统设置，等1秒，弹提示显示已打开"},
    @{name="条件分支"; req="读取电量，如果低于20就弹提示电量不足，否则弹提示电量充足"},
    @{name="循环累加"; req="从1累加到5，每步记录日志，最后弹提示总和"},
    @{name="文件写入读取"; req="写入一个测试文件到sdcard，然后读出来弹提示内容"},
    @{name="HTTP加JSON解析"; req="请求https://api.github.com/repos/ChaoMixian/Habitat，解析stargazers_count字段，弹提示星数"},
    @{name="定时触发器"; req="每5秒定时：读取电量写入日志，重复执行"},
    @{name="UI交互"; req="打开微信，等2秒，按返回键退出"},
    @{name="工具集"; req="生成一个随机数，base64编码，复制到剪贴板并弹提示"},
    @{name="剪贴板触发"; req="监听剪贴板变化，把内容清空格后弹提示"},
    @{name="多步骤AI"; req="读取屏幕文字，如果包含微信二字就点击微信，否则提示未找到"}
)

function Get-JsonObj($text) {
    $start = $text.IndexOf('{'); if ($start -lt 0) { return $null }
    $depth=0;$inStr=$false;$esc=$false;$end=-1
    for($i=$start;$i -lt $text.Length;$i++){
        $ch=$text[$i]
        if($inStr){ if($esc){$esc=$false}elseif($ch -eq '\'){$esc=$true}elseif($ch -eq '"'){$inStr=$false} }
        else { if($ch -eq '"'){$inStr=$true}elseif($ch -eq '{'){$depth++}elseif($ch -eq '}'){$depth--;if($depth -eq 0){$end=$i;break}} }
    }
    if($end -lt 0){ return $null }
    try { return ($text.Substring($start,$end-$start+1) | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Test-Workflow($text) {
    $obj = Get-JsonObj $text
    if($null -eq $obj){ return @($false, "无JSON或解析失败") }
    $wf = if($obj.workflow){ $obj.workflow } else { $obj }
    if($null -eq $wf.start_node_id){ return @($false, "缺 start_node_id") }
    if($null -eq $wf.nodes){ return @($false, "缺 nodes") }
    $nodes = $wf.nodes
    if($nodes.PSObject.Properties.Count -eq 0){ return @($false, "nodes 为空") }
    if($null -eq $nodes.PSObject.Properties[$wf.start_node_id]){ return @($false, "start_node_id '$($wf.start_node_id)' 不存在") }
    $ids = @($nodes.PSObject.Properties.Name)
    foreach($p in $nodes.PSObject.Properties){
        $node=$p.Value
        if([string]::IsNullOrWhiteSpace($node.id)){ return @($false, "节点 '$($p.Name)' 缺 id") }
        if([string]::IsNullOrWhiteSpace($node.type)){ return @($false, "节点 '$($node.id)' 缺 type") }
        if($whitelist -notcontains $node.type){ return @($false, "节点 '$($node.id)' 使用了非白名单 type='$($node.type)'") }
        foreach($r in @($node.next) + @($node.branches.PSObject.Properties.Value)){
            if($r -and ($ids -notcontains $r)){ return @($false, "节点 '$($node.id)' 引用不存在的 '$r'") }
        }
    }
    return @($true, "OK")
}

Write-Host "== 验证 DeepSeek 生成正确率（$($cases.Count) 套）==`n"
$pass=0;$fail=0
foreach($c in $cases){
    $json = @{ model='deepseek-chat'; temperature=0.3; messages=@(@{role='system';content=$sys}, @{role='user';content=("需求：" + $c.req)}) } | ConvertTo-Json -Depth 5
    try {
        $r = Invoke-RestMethod -Uri 'https://api.deepseek.com/chat/completions' -Method Post -Headers @{ Authorization = "Bearer $ApiKey" } -Body $json -ContentType 'application/json' -TimeoutSec 60
        $content = $r.choices[0].message.content
    } catch {
        Write-Host ("[{0}] 调用失败: {1}" -f $c.name, $_.Exception.Message); $fail++; continue
    }
    $res = Test-Workflow $content
    if($res[0]){ $pass++; Write-Host ("[PASS] {0}" -f $c.name) -ForegroundColor Green }
    else { $fail++; Write-Host ("[FAIL] {0}  ← {1}" -f $c.name, $res[1]) -ForegroundColor Red }
}
Write-Host ("`n== 正确率: {0}/{1} = {2:P1} ==" -f $pass, $cases.Count, ($pass/$cases.Count))
