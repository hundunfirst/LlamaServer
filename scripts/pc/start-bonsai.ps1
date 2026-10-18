# start-bonsai.ps1 —— 双击 启动Bonsai.bat 运行；也可命令行用 -Model 直接指定
# 菜单会列出 models\ 里的主模型，自动配对同系列的多模态投影器，并把草稿/侧车文件排除掉。

param(
    [string]$Model = "",      # 直接指定主模型文件名（跳过菜单）
    [string]$Projector = "",  # 直接指定投影器文件名；填 none 表示不用视觉
    [switch]$NoMenu,
    [switch]$Diagnose,        # 只打印每个文件的类型判定，方便排查
    [switch]$DryRun           # 只打印将要执行的命令，不真正启动
)

# ================= 可调参数（改这里，改完保存即可） =================
$PORT           = 8081        # 端口：别和你常驻的 llama-server 撞
$CTX            = 8192        # 上下文：4GB 显存建议 8192，最多 16384
$NGL            = 99          # 送多少层到显卡：99=全部，0=纯 CPU
$THREADS        = 8           # CPU 线程数，一般填物理核数
$HOST_ADDR      = "0.0.0.0"   # 127.0.0.1=只本机；0.0.0.0=局域网（手机）可连
$APIKEY         = "twgdh"     # HOST 设 0.0.0.0 时建议设置；留空=谁都能用
$MMPROJ_ON_CPU  = 1           # 1=投影器放内存（显存紧张推荐），0=放显存

# 名字对不上的投影器在这里手动配对（默认按"同系列名前缀"自动配）
$ProjectorOverride = @{
    # "MiniCPM-V-4_7-1B-F16.gguf" = "mmproj-model-f16-4x.gguf"
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
function Get-GgufMeta([string]$path) {
    try {
        $fs = [IO.File]::OpenRead($path)
        try {
            $len = [Math]::Min(524288, $fs.Length)
            $buf = New-Object byte[] $len
            [void]$fs.Read($buf, 0, $len)
        } finally { $fs.Close() }
        if ($len -lt 32 -or [Text.Encoding]::ASCII.GetString($buf, 0, 4) -ne "GGUF") {
            return @{ arch = ""; type = "" }
        }
        $pos = 4
        $ver = [BitConverter]::ToUInt32($buf, $pos); $pos += 4
        $nT  = [BitConverter]::ToUInt64($buf, $pos); $pos += 8
        $nKV = [BitConverter]::ToUInt64($buf, $pos); $pos += 8
        $arch = ""; $type = ""
        for ($i = 0; $i -lt [int]$nKV; $i++) {
            $klen = [int][BitConverter]::ToUInt64($buf, $pos); $pos += 8
            $key  = [Text.Encoding]::UTF8.GetString($buf, $pos, $klen); $pos += $klen
            $t    = [BitConverter]::ToUInt32($buf, $pos); $pos += 4
            if ($t -eq 8) {
                $vlen = [int][BitConverter]::ToUInt64($buf, $pos); $pos += 8
                $val  = [Text.Encoding]::UTF8.GetString($buf, $pos, $vlen); $pos += $vlen
                if ($key -eq "general.architecture") { $arch = $val }
                elseif ($key -eq "general.type")     { $type = $val }
            } elseif ($t -eq 9) {
                $et = [BitConverter]::ToUInt32($buf, $pos); $pos += 4
                $n  = [int][BitConverter]::ToUInt64($buf, $pos); $pos += 8
                if ($arch -or $n -gt 4096) { break }   # 词表那种大数组不必读完
                for ($j = 0; $j -lt $n; $j++) {
                    $sl = [int][BitConverter]::ToUInt64($buf, $pos); $pos += 8 + $sl
                }
            } else {
                $pos += switch ($t) { 0 {1} 1 {1} 7 {1} 2 {2} 3 {2} 4 {4} 5 {4} 6 {4} 10 {8} 11 {8} 12 {8} default {0} }
            }
            if ($arch -and $type) { break }
        }
        return @{ arch = $arch; type = $type }
    } catch { return @{ arch = ""; type = "" } }
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

# 家族前缀：砍掉量化/精度/投影器那些尾巴，用来把主模型和投影器配对
function Get-Base([string]$name) {
    $n = $name -replace '(?i)\.gguf$', ''
    $n = $n -replace '(?i)[-_.]mmproj.*$', ''
    $n = $n -replace '(?i)[-_.](UD[-_])?(PQ2_0|PTQ1_0|BF16|F16|F32)([-_.].*)?$', ''
    $n = $n -replace '(?i)[-_.](Q|IQ)\d+([-_][A-Z0-9]+)*$', ''
    return $n
}

Write-Host ""
Write-Host "  扫描 models\ ..." -ForegroundColor DarkGray
$mains = @(); $projs = @(); $skipped = @()
foreach ($f in (Get-ChildItem $modelsDir -Filter *.gguf -File)) {
    $kind = Get-GgufKind $f.FullName
    if ($kind -eq "main") { $mains += $f }
    elseif ($kind -eq "mmproj") { $projs += $f }
    else { $skipped += ("{0}（{1}）" -f $f.Name, $kind) }
}
$mains = @($mains | Sort-Object Name)
$projs = @($projs | Sort-Object Name)

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

# ---------- 选择模型 ----------
$chosen = $null; $chosenProj = $null
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
    Write-Host "  可选主模型：" -ForegroundColor Cyan
    $i = 0
    foreach ($m in $mains) {
        $i++
        $gb = [math]::Round($m.Length / 1GB, 2)
        $p = Find-Projector $m.Name
        $vis = if ($p) { "视觉：" + $p.Name } else { "视觉：无" }
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

Set-Content -Path $lastFile -Value ($mains.IndexOf($chosen) + 1) -NoNewline

# ---------- 组装参数 ----------
$argList = @(
    "-m", $chosen.FullName,
    "-c", "$CTX",
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

Write-Host ""
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host ("   主模型 : {0}" -f $chosen.Name)
Write-Host ("   视觉   : {0}" -f $(if ($chosenProj) { $chosenProj.Name + $(if ($MMPROJ_ON_CPU -eq 1) { "（放内存）" } else { "（放显存）" }) } else { "不使用" }))
Write-Host ("   端口   : {0}    上下文: {1}    GPU层数: {2}    线程: {3}" -f $PORT, $CTX, $NGL, $THREADS)
Write-Host ("   监听   : {0}" -f $HOST_ADDR)
if ($APIKEY) { Write-Host "   令牌   : 已设置（客户端带 Authorization: Bearer ...）" }
Write-Host ("   接口   : http://127.0.0.1:{0}/v1    界面: http://127.0.0.1:{0}/" -f $PORT)
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host "   关闭这个窗口即可停止服务。" -ForegroundColor DarkGray
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
    & $exe @argList 2>&1 | Tee-Object -FilePath (Join-Path $root "server.log")
    $code = $LASTEXITCODE
} finally { Pop-Location }

Write-Host ""
Write-Host ("  [服务已退出，退出码 {0}]" -f $code)
Write-Host "  常见原因：端口被占用 / 显存不够（把 CTX 或 NGL 调小）/ 模型与投影器不配套" -ForegroundColor DarkGray
Write-Host ""
Read-Host "按回车关闭窗口"
