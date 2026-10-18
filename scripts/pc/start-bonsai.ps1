# start-bonsai.ps1 —— 双击 启动Bonsai.bat 运行；也可命令行用 -Model 直接指定
# 菜单会列出 models\ 里的主模型，自动配对同系列的多模态投影器，并把草稿/侧车文件排除掉。

param(
    [string]$Model = "",      # 直接指定主模型文件名（跳过菜单）
    [string]$Projector = "",  # 直接指定投影器文件名；填 none 表示不用视觉
    [string]$Draft = "",      # 直接指定投机解码草稿（DFlash2/dspark/MTP）；none = 不用
    [int]$CtxSize = -1,       # 上下文；不传 = 自动（-1）
    [int]$GpuLayers = -1,     # GPU 层数；不传 = 用配置里的 99
    [int]$ListenPort = -1,    # 端口；不传 = 用配置里的 8081
    [string]$ApiKey = $null,  # 访问令牌；不传 = 用配置里的值，传空串 = 不设
    [switch]$Ask,             # 即使 -Model 也弹出参数询问
    [switch]$NoPrompt,        # 不询问，直接用配置/命令行参数
    [switch]$NoMenu,
    [switch]$Diagnose,        # 只打印每个文件的类型判定，方便排查
    [switch]$DryRun           # 只打印将要执行的命令，不真正启动
)

# ================= 可调参数（改这里，改完保存即可） =================
$PORT           = 8081        # 端口：别和你常驻的 llama-server 撞
$CTX            = 0           # 上下文：0 = 按模型大小自动（4GB 显存档位，见下）；写具体数字则强制
$CTX_MAX        = 131072       # 自动模式的上限
$NGL            = 99          # 送多少层到显卡：99=全部，0=纯 CPU
$THREADS        = 8           # CPU 线程数，一般填物理核数
$HOST_ADDR      = "0.0.0.0"   # 127.0.0.1=只本机；0.0.0.0=局域网（手机）可连
$APIKEY         = "twgdh"     # HOST 设 0.0.0.0 时建议设置；留空=谁都能用
$MMPROJ_ON_CPU  = 1           # 1=投影器放内存（显存紧张推荐），0=放显存
$USE_DRAFT      = 1           # 1=自动给主模型配上同尺寸的投机解码草稿（DFlash2/dspark/MTP）
$DRAFT_N_MAX    = 4           # 每轮最多预测几个 token（投机解码，4~8 比较稳）
$USE_MODEL_MTP  = 1           # 1=主模型自带 MTP 头时启用它投机（没有独立草稿文件时才有意义）

# 名字对不上的投影器在这里手动配对（默认按"同系列名前缀"自动配）
$ProjectorOverride = @{
    # "MiniCPM-V-4_7-1B-F16.gguf" = "mmproj-model-f16-4x.gguf"
}
# 投机解码草稿同理：默认按同尺寸自动配，特殊情况在这里手动指定
$DraftOverride = @{
    # "Ternary-Bonsai-2-9B-Q2_0.gguf" = "Ternary-Bonsai-2-9B-DFlash2-Q8_0.gguf"
}
# ===================================================================

$ErrorActionPreference = "Continue"
$root      = $PSScriptRoot
$llamaDir  = Join-Path $root "llama"
$modelsDir = Join-Path $root "models"
$exe       = Join-Path $llamaDir "llama-server.exe"
$lastFile  = Join-Path $root "last-run.txt"

function Fail($msg) {
    Write-Host ""
    Write-Host "  [错误] $msg" -ForegroundColor Red
    Write-Host ""
    # 双击启动时窗口可能一闪而过/看不到，落一份到 error.log 便于排查
    try { Add-Content -Path (Join-Path $PSScriptRoot "error.log") -Value ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), ($msg -replace "`n", " ")) } catch { }
    Read-Host "按回车退出"
    exit 1
}

if (-not (Test-Path $exe))     { Fail "找不到 $exe`n         请确认本脚本与 llama\ 目录在同一层。" }
if (-not (Test-Path $modelsDir)) { Fail "找不到 models\ 目录" }

# 全程记录到 run.log：出问题把这份发出来就行（Write-Host 不进 stdout，只能靠 transcript）
try {
    $logFile = Join-Path $root "run.log"
    if (Test-Path $logFile) { Remove-Item $logFile -Force -ErrorAction SilentlyContinue }
    Start-Transcript -Path $logFile -Force | Out-Null
} catch { }

# ---------- 读 GGUF 头，判断文件类型（主模型 / 投影器 / 草稿…） ----------
# 做法：不按 KV 顺序逐个解析（不同版本/工具写的文件顺序和类型不一致，容易错位），
# 而是直接在头部字节里搜 "general.architecture" 这类键名，再读它后面的值。
function Get-GgufMeta([string]$path) {
    $arch = ""; $type = ""
    try {
        $fs = [IO.File]::OpenRead($path)
        try {
            # 注意：$fs.Length 是 Int64，[Math]::Min 必须两边都转 Int64，否则重载解析失败会抛异常
            $len = [int][Math]::Min([int64]2097152, [int64]$fs.Length)
            $buf = New-Object byte[] $len
            $got = 0
            while ($got -lt $len) {
                $n = $fs.Read($buf, $got, $len - $got)
                if ($n -le 0) { break }
                $got += $n
            }
        } finally { $fs.Close() }
        $len = $got
        if ($len -lt 64 -or [Text.Encoding]::ASCII.GetString($buf, 0, 4) -ne "GGUF") {
            return @{ arch = ""; type = "" }
        }
        # Latin1：1 字符 = 1 字节，所以字符下标就是字节偏移
        $s = [Text.Encoding]::GetEncoding(28591).GetString($buf)

        $readValue = {
            param([string]$key)
            $i = $s.IndexOf($key, [StringComparison]::Ordinal)
            if ($i -lt 0) { return "" }
            $p = $i + $key.Length
            if ($p + 12 -gt $buf.Length) { return "" }
            $t = [BitConverter]::ToUInt32($buf, $p); $p += 4
            if ($t -ne 8) { return "" }                      # 不是字符串（8）就跳过
            $vlen = [int][BitConverter]::ToUInt64($buf, $p); $p += 8
            if ($vlen -le 0 -or $vlen -gt 64 -or $p + $vlen -gt $buf.Length) { return "" }
            return [Text.Encoding]::UTF8.GetString($buf, $p, $vlen)
        }

        $arch = & $readValue "general.architecture"
        $type = & $readValue "general.type"
        $size = & $readValue "general.size_label"
    } catch { }
    return @{ arch = $arch; type = $type; size = $size }
}

function Get-GgufKind([string]$path) {
    $m = Get-GgufMeta $path
    $a = "$($m.arch)".ToLower(); $t = "$($m.type)".ToLower()
    if ($t -eq "mmproj" -or $a -eq "clip" -or $a -eq "mmproj") { return "mmproj" }
    if ($a -in @("dflash", "dspark", "eagle3", "draft", "mtp")) { return "draft" }
    if ($t -in @("adapter", "lora")) { return "adapter" }
    if (-not $a) { return "unknown" }
    return "main"
}

# 家族前缀：砍掉量化/精度/投影器/侧车那些尾巴，用来把主模型和投影器、草稿配对
function Get-Base([string]$name) {
    $n = $name -replace '(?i)\.gguf$', ''
    $n = $n -replace '(?i)[-_.]mmproj.*$', ''
    $n = $n -replace '(?i)[-_.](dflash2?|dspark|eagle3|mtp)([-_.].*)?$', ''
    $n = $n -replace '(?i)[-_.](UD[-_])?(PQ2_0|PTQ1_0|BF16|F16|F32)([-_.].*)?$', ''
    $n = $n -replace '(?i)[-_.](Q|IQ)\d+([-_][A-Z0-9]+)*$', ''
    return $n
}

# 草稿文件对应的 --spec-type 取值（看 architecture）
function Get-DraftSpecType([string]$path) {
    $a = "$((Get-GgufMeta $path).arch)".ToLower()
    switch ($a) {
        "dflash" { return "draft-dflash" }
        "dspark" { return "draft-dspark" }
        "eagle3" { return "draft-eagle3" }
        "mtp"    { return "draft-mtp" }
        default  { return "draft-simple" }
    }
}

Write-Host ""
Write-Host "  扫描 models\ ..." -ForegroundColor DarkGray
$mains = @(); $projs = @(); $drafts = @(); $skipped = @()
foreach ($f in (Get-ChildItem $modelsDir -Filter *.gguf -File)) {
    $kind = Get-GgufKind $f.FullName
    if ($kind -eq "main") { $mains += $f }
    elseif ($kind -eq "mmproj") { $projs += $f }
    elseif ($kind -eq "draft") { $drafts += $f }
    else { $skipped += ("{0}（{1}）" -f $f.Name, $kind) }
}
$mains = @($mains | Sort-Object Name)
$projs = @($projs | Sort-Object Name)
$drafts = @($drafts | Sort-Object Name)

if ($Diagnose) {
    Write-Host ""
    Write-Host "  models\ 里的文件判定：" -ForegroundColor Cyan
    foreach ($f in (Get-ChildItem $modelsDir -Filter *.gguf -File)) {
        $m = Get-GgufMeta $f.FullName
        $k = Get-GgufKind $f.FullName
        Write-Host ("   {0,-46} kind={1,-8} arch={2,-10} type={3}" -f $f.Name, $k, $m.arch, $m.type)
    }
    Write-Host ""
    Read-Host "按回车退出"
    exit 0
}

function Find-Projector([string]$modelName) {
    if ($ProjectorOverride.ContainsKey($modelName)) {
        $hit = $projs | Where-Object { $_.Name -ieq $ProjectorOverride[$modelName] } | Select-Object -First 1
        if ($hit) { return $hit }
    }
    $b = Get-Base $modelName
    return $projs | Where-Object { (Get-Base $_.Name) -ieq $b } | Select-Object -First 1
}

function Find-Draft([string]$modelName) {
    if ($DraftOverride.ContainsKey($modelName)) {
        $hit = $drafts | Where-Object { $_.Name -ieq $DraftOverride[$modelName] } | Select-Object -First 1
        if ($hit) { return $hit }
    }
    $b = Get-Base $modelName
    return $drafts | Where-Object { (Get-Base $_.Name) -ieq $b } | Select-Object -First 1
}

# ---------- 选择模型 ----------
$chosen = $null; $chosenProj = $null
$wantPrompt = $false
if ($Model) {
    $chosen = $mains | Where-Object { $_.Name -ieq $Model } | Select-Object -First 1
    if (-not $chosen) { Fail "models\ 里没有主模型：$Model" }
} elseif ($NoMenu -and $mains.Count -gt 0) {
    $chosen = $mains[0]
} else {
    if ($mains.Count -eq 0) {
        Fail "models\ 里没有可用的主模型（*.gguf）。`n         注意：带 mmproj 的是投影器，DFlash/dspark/MTP 侧车不能当主模型。"
    }
    Write-Host ""
    $wantPrompt = $true
    Write-Host "  可选主模型：" -ForegroundColor Cyan
    $i = 0
    foreach ($m in $mains) {
        $i++
        $gb = [math]::Round($m.Length / 1GB, 2)
        $p = Find-Projector $m.Name
        $d = if ($USE_DRAFT -eq 1) { Find-Draft $m.Name } else { $null }
        $vis = if ($p) { "视觉：" + $p.Name } else { "视觉：无" }
        if ($d) { $vis += "   草稿可选：" + $d.Name }
        Write-Host ("   [{0}] {1}" -f $i, $m.Name)
        Write-Host ("       {0} GB   {1}" -f $gb, $vis) -ForegroundColor DarkGray
    }
    if ($skipped.Count -gt 0) {
        Write-Host ""
        Write-Host "   已跳过（不能当主模型）：" -ForegroundColor DarkYellow
        $skipped | ForEach-Object { Write-Host "     - $_" -ForegroundColor DarkYellow }
    }
    $last = if (Test-Path $lastFile) { (Get-Content $lastFile -Raw).Trim() } else { "1" }
    Write-Host ""
    $pick = Read-Host ("   输入序号（回车 = 上次的 {0}，q 退出）" -f $last)
    if ($pick -eq "q" -or $pick -eq "Q") { exit 0 }
    if (-not $pick) { $pick = $last }
    $n = 0
    if (-not [int]::TryParse($pick, [ref]$n) -or $n -lt 1 -or $n -gt $mains.Count) {
        Fail "序号无效：$pick"
    }
    $chosen = $mains[$n - 1]
}

if ($Projector -eq "none") {
    $chosenProj = $null
} elseif ($Projector) {
    $chosenProj = $projs | Where-Object { $_.Name -ieq $Projector } | Select-Object -First 1
    if (-not $chosenProj) { Fail "models\ 里没有投影器：$Projector" }
} else {
    $chosenProj = Find-Projector $chosen.Name
}

# 投机解码：
#   -Draft <文件>  → 用指定草稿（必须是同一血统的主模型配套，否则会报 invalid vector subscript）
#   -Draft mtp     → 只用主模型自带的 MTP 头
#   -Draft none    → 全关（纯基线，用于对比）
#   默认(auto)     → 主模型自带 MTP 就开 MTP；外部草稿文件只在菜单里提示，不自动启用
#                    （实测：9B 的 DFlash2 草稿配 PQ2_0-MTP 主模型会加载失败）
$chosenDraft = $null
$useMtp = $false
$draftHint = $null
if ($Draft -eq "none" -or $USE_DRAFT -eq 0) {
    # 显式关闭
} elseif ($Draft -ieq "mtp") {
    $useMtp = $true
} elseif ($Draft) {
    $chosenDraft = $drafts | Where-Object { $_.Name -ieq $Draft } | Select-Object -First 1
    if (-not $chosenDraft) { Fail "models\ 里没有草稿文件：$Draft（用 -Draft none 表示不用，-Draft mtp 用内置 MTP）" }
} else {
    if ($USE_MODEL_MTP -eq 1 -and $chosen.Name -match '(?i)\bmtp\b') { $useMtp = $true }
    $maybe = Find-Draft $chosen.Name
    if ($maybe) { $draftHint = $maybe.Name }
}

Set-Content -Path $lastFile -Value ($mains.IndexOf($chosen) + 1) -NoNewline

# ---------- 上下文：$CTX=0 时按模型大小自动（4GB 显存档位） ----------
$ctxUse = $CTX
$ctxSource = "手动指定"
if ($ctxUse -le 0) {
    $sizeGb = 0.0
    $label = "$((Get-GgufMeta $chosen.FullName).size)"
    if ($label -match '(?i)^([0-9.]+)\s*B') { [void][double]::TryParse($Matches[1], [ref]$sizeGb) }
    $ctxUse = if ($sizeGb -ge 20) { 2048 }
              elseif ($sizeGb -ge 8) { 4096 }
              elseif ($sizeGb -ge 3) { 16384 }
              else { $CTX_MAX }
    if ($ctxUse -gt $CTX_MAX) { $ctxUse = $CTX_MAX }
    $ctxSource = "按模型大小自动"
}

# ---------- 命令行参数覆盖配置（注意：参数名不能和下面的 $CTX/$NGL/$PORT 同名，
#            PowerShell 变量不分大小写，同名会被配置赋值覆盖掉） ----------
if ($CtxSize -ge 0) { $ctxUse = [Math]::Max(512, $CtxSize); $ctxSource = "手动指定" }
if ($GpuLayers -ge 0) { $NGL = $GpuLayers }
if ($ListenPort -ge 0) { $PORT = $ListenPort }
if ($null -ne $ApiKey) { $APIKEY = $ApiKey }

# ---------- 交互式询问（双击运行时；回车 = 用默认） ----------
if ((-not $NoPrompt) -and ($wantPrompt -or $Ask)) {
    Write-Host ""
    Write-Host "  ------------------ 参数设置 ------------------" -ForegroundColor Cyan
    Write-Host "  下面每一项直接回车 = 用括号里的默认值" -ForegroundColor DarkGray
    Write-Host ""

    # 1) 上下文
    Write-Host "  1) 上下文长度：1=4K   2=8K   3=16K   4=32K   5=64K   6=128K" -ForegroundColor Gray
    Write-Host "     也可以直接输入数字（例如 24576）" -ForegroundColor DarkGray
    $a = Read-Host ("     请输入（回车 = {0} {1}）" -f $ctxSource, $ctxUse)
    if ($a) {
        $map = @{ "1" = 4096; "2" = 8192; "3" = 16384; "4" = 32768; "5" = 65536; "6" = 131072 }
        $num = 0
        if ($map.ContainsKey($a.Trim())) {
            $ctxUse = $map[$a.Trim()]; $ctxSource = "手动指定"
        } elseif ([int]::TryParse($a.Trim(), [ref]$num) -and $num -ge 512) {
            $ctxUse = $num; $ctxSource = "手动指定"
        } else {
            Write-Host "     输入无效，继续用默认值" -ForegroundColor Yellow
        }
    }

    # 2) GPU 层数
    $b = Read-Host ("  2) GPU 层数（99 = 全部上卡，0 = 纯 CPU；回车 = {0}）" -f $NGL)
    if ($b) {
        $n = 0
        if ([int]::TryParse($b.Trim(), [ref]$n) -and $n -ge 0 -and $n -le 999) { $NGL = $n }
        else { Write-Host "     输入无效，继续用默认值" -ForegroundColor Yellow }
    }

    # 3) 端口
    $c = Read-Host ("  3) 端口（回车 = {0}）" -f $PORT)
    if ($c) {
        $n = 0
        if ([int]::TryParse($c.Trim(), [ref]$n) -and $n -ge 1024 -and $n -le 65535) { $PORT = $n }
        else { Write-Host "     端口必须在 1024-65535 之间，继续用默认值" -ForegroundColor Yellow }
    }

    # 4) 访问令牌
    $d = Read-Host "  4) 访问令牌（回车 = 不设；设了之后客户端要带 Authorization: Bearer <令牌>）"
    $APIKEY = if ($d) { $d.Trim() } else { "" }

    Write-Host "  ----------------------------------------------" -ForegroundColor Cyan
}

# ---------- 冲突/风险提示 ----------
if (Get-NetTCPConnection -LocalPort $PORT -State Listen -ErrorAction SilentlyContinue) {
    Write-Host ("  提示：端口 {0} 已经被占用，启动可能失败；换个端口再试。" -f $PORT) -ForegroundColor DarkYellow
}
if ($HOST_ADDR -eq "0.0.0.0" -and -not $APIKEY) {
    Write-Host "  提示：监听 0.0.0.0 且没设令牌 —— 同网段任何人都能用你的电脑跑模型。" -ForegroundColor DarkYellow
}
if ($NGL -gt 0 -and $ctxUse -gt 32768) {
    Write-Host ("  提示：上下文 {0} 偏大，4GB 显存可能起不来；失败就把上下文调小或 GPU 层数降低。" -f $ctxUse) -ForegroundColor DarkYellow
}

# ---------- 组装参数 ----------
$argList = @(
    "-m", $chosen.FullName,
    "-c", "$ctxUse",
    "-ngl", "$NGL",
    "-t", "$THREADS",
    "--host", $HOST_ADDR,
    "--port", "$PORT"
)
if ($APIKEY) { $argList += @("--api-key", $APIKEY) }
if ($chosenProj) {
    $argList += @("--mmproj", $chosenProj.FullName)
    if ($MMPROJ_ON_CPU -eq 1) { $argList += "--no-mmproj-offload" }
}
if ($chosenDraft) {
    $argList += @("--spec-draft-model", $chosenDraft.FullName,
                  "--spec-type", (Get-DraftSpecType $chosenDraft.FullName),
                  "--spec-draft-n-max", "$DRAFT_N_MAX")
} elseif ($useMtp) {
    # 主模型名里带 MTP = 自带 MTP 头，用模型自己的头做投机（不需要额外草稿文件）
    $argList += @("--spec-type", "draft-mtp", "--spec-draft-n-max", "$DRAFT_N_MAX")
}

Write-Host ""
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host ("   主模型 : {0}" -f $chosen.Name)
Write-Host ("   视觉   : {0}" -f $(if ($chosenProj) { $chosenProj.Name + $(if ($MMPROJ_ON_CPU -eq 1) { "（放内存）" } else { "（放显存）" }) } else { "不使用" }))
Write-Host ("   草稿   : {0}" -f $($(if ($chosenDraft) { $chosenDraft.Name + "（" + (Get-DraftSpecType $chosenDraft.FullName) + "，最多预测 " + $DRAFT_N_MAX + " token）" }
                                 elseif ($useMtp) { "用模型自带的 MTP 头（draft-mtp，最多预测 " + $DRAFT_N_MAX + " token）" }
                                 else { "不使用" })))
Write-Host ("   端口   : {0}    上下文: {1}（{2}）    GPU层数: {3}    线程: {4}" -f $PORT, $ctxUse, $ctxSource, $NGL, $THREADS)
Write-Host ("   监听   : {0}" -f $HOST_ADDR)
if ($APIKEY) { Write-Host "   令牌   : 已设置（客户端带 Authorization: Bearer ...）" }
else { Write-Host "   令牌   : 未设置（同网段可直接调用）" -ForegroundColor DarkGray }
Write-Host ("   接口   : http://127.0.0.1:{0}/v1    界面: http://127.0.0.1:{0}/" -f $PORT)
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host "   关闭这个窗口即可停止服务。" -ForegroundColor DarkGray
if ($draftHint -and -not $chosenDraft) {
    Write-Host ("   提示：同尺寸还有 $draftHint 可以当投机草稿，但必须和主模型同一血统；" +
                "想试就加 -Draft `"$draftHint`"（配不上会直接报错退出）") -ForegroundColor DarkYellow
}
Write-Host ""
Write-Host "  切换模型：关掉窗口，重新双击 启动Bonsai.bat 再选一个。" -ForegroundColor DarkGray
Write-Host ""

Push-Location $llamaDir
if ($DryRun) {
    Write-Host ""
    Write-Host "  [DryRun] 将要执行：" -ForegroundColor Yellow
    Write-Host ("  " + $exe + " " + ($argList -join " ")) -ForegroundColor Yellow
    Pop-Location
    Read-Host "按回车退出"
    exit 0
}
try {
    # 直接执行，不接管它的输出：
    #   一旦写成 "2>&1 | Tee/ForEach"，PowerShell 5.1 会把 llama-server 写到 stderr 的日志
    #   当成 ErrorRecord，整屏变红（像在报错），所以这里保持原样直出，颜色由程序自己决定。
    & $exe @argList
    $code = $LASTEXITCODE
} finally { Pop-Location }

Write-Host ""
Write-Host ("  [服务已退出，退出码 {0}]" -f $code)
Write-Host "  常见原因：端口被占用 / 显存不够（把 CTX 或 NGL 调小）/ 模型与投影器不配套" -ForegroundColor DarkGray
Write-Host ""
Read-Host "按回车关闭窗口"
