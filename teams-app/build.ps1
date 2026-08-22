<#
.SYNOPSIS
  Builds the Teams app package for the RAG assistant bot.

.DESCRIPTION
  Substitutes the manifest placeholders and produces a FLAT zip. Every check here maps to a
  failure Teams reports as "manifest khong hop le" with no reason given: a folder wrapped
  around the three files, a wrong icon size, an unsubstituted placeholder, a non-GUID bot id.

.EXAMPLE
  .\build.ps1 -BotAppId 37529ae5-6fa5-4831-a521-5b3db104faab -AppHost chatbot-uat.bsc.com.vn
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BotAppId,
    [Parameter(Mandatory = $true)][string] $AppHost,
    # Them scope 'team': bot vao duoc CHANNEL cua Team. Group chat khong can co nay.
    [switch] $IncludeChannel,
    [string] $OutFile
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $OutFile) { $OutFile = Join-Path (Split-Path -Parent $here) 'bsc-rag-assistant.zip' }

function Fail($msg) { Write-Host "LOI: $msg" -ForegroundColor Red; exit 1 }

# --- dau vao -------------------------------------------------------------------
$guid = [ref]([guid]::Empty)
if (-not [guid]::TryParse($BotAppId, $guid)) {
    Fail "BotAppId '$BotAppId' khong phai GUID. Lay o Azure Bot -> Configuration -> Microsoft App ID."
}
if ($AppHost -match '^https?://') {
    Fail "AppHost phai la ten mien tran, khong co scheme. Dung 'chatbot-uat.bsc.com.vn'."
}
if ($AppHost -match '/') { Fail "AppHost khong duoc chua duong dan. Bo phan '/rag'." }

# --- icon ----------------------------------------------------------------------
function Get-PngInfo($path) {
    if (-not (Test-Path $path)) { Fail "Thieu file $path" }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -lt 33) { Fail "$path qua ngan, khong phai PNG" }
    $sig = @(137, 80, 78, 71, 13, 10, 26, 10)
    for ($i = 0; $i -lt 8; $i++) {
        if ($bytes[$i] -ne $sig[$i]) { Fail "$path khong phai PNG that (chi doi ten duoi file?)" }
    }
    # IHDR: width/height big-endian tai offset 16..23, color type tai 25
    $w = ($bytes[16] -shl 24) + ($bytes[17] -shl 16) + ($bytes[18] -shl 8) + $bytes[19]
    $h = ($bytes[20] -shl 24) + ($bytes[21] -shl 16) + ($bytes[22] -shl 8) + $bytes[23]
    return @{ Width = $w; Height = $h; ColorType = $bytes[25] }
}

$color = Get-PngInfo (Join-Path $here 'color.png')
if ($color.Width -ne 192 -or $color.Height -ne 192) {
    Fail ("color.png phai 192x192, dang la {0}x{1}" -f $color.Width, $color.Height)
}
$outline = Get-PngInfo (Join-Path $here 'outline.png')
if ($outline.Width -ne 32 -or $outline.Height -ne 32) {
    Fail ("outline.png phai 32x32, dang la {0}x{1}" -f $outline.Width, $outline.Height)
}
# 4 = gray+alpha, 6 = RGBA. Thieu alpha => Teams hien o vuong dac tren thanh ben.
if ($outline.ColorType -ne 4 -and $outline.ColorType -ne 6) {
    Fail "outline.png khong co kenh alpha (color type $($outline.ColorType)). Phai la trang tren nen trong suot."
}

# --- manifest ------------------------------------------------------------------
$src = Join-Path $here 'manifest.json'
$text = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)
$text = $text.Replace('{{BOT_APP_ID}}', $BotAppId).Replace('{{APP_HOST}}', $AppHost)
if ($text -match '\{\{') { Fail "Con placeholder chua thay trong manifest: $($Matches[0])" }

try { $parsed = $text | ConvertFrom-Json } catch { Fail "manifest sau khi thay the khong phai JSON hop le: $_" }
if ($parsed.bots[0].botId -ne $BotAppId) { Fail 'botId trong manifest khong khop BotAppId' }

# Gioi han do dai cua Teams, tinh theo KY TU. Vuot nguong la bi tu choi ma khong noi
# truong nao sai - de xay ra khi doi ten bot cho tung phong ban.
$caps = @(
    @{ Name = 'packageName';       Value = $parsed.packageName;            Cap = 64   },
    @{ Name = 'developer.name';    Value = $parsed.developer.name;         Cap = 32   },
    @{ Name = 'name.short';        Value = $parsed.name.short;             Cap = 30   },
    @{ Name = 'name.full';         Value = $parsed.name.full;              Cap = 100  },
    @{ Name = 'description.short'; Value = $parsed.description.short;      Cap = 80   },
    @{ Name = 'description.full';  Value = $parsed.description.full;       Cap = 4000 }
)
foreach ($c in $caps) {
    $v = [string]$c.Value
    if ([string]::IsNullOrWhiteSpace($v)) { Fail ("{0} de rong" -f $c.Name) }
    if ($v.Length -gt $c.Cap) {
        Fail ("{0} dai {1} ky tu, toi da {2}" -f $c.Name, $v.Length, $c.Cap)
    }
    if ($v -ne $v.Trim()) { Fail ("{0} co khoang trang o dau/cuoi" -f $c.Name) }
}
if ($parsed.accentColor -notmatch '^#[0-9a-fA-F]{6}$') {
    Fail "accentColor phai dang #RRGGBB, dang la '$($parsed.accentColor)'"
}
if ($parsed.icons.color -ne 'color.png' -or $parsed.icons.outline -ne 'outline.png') {
    Fail 'icons phai tro dung color.png va outline.png'
}

if ($IncludeChannel) {
    # Sua tren van ban roi parse lai: giu dung thu tu scope va khong phu thuoc
    # cach ConvertTo-Json cua PS 5.1 lam sap mang mot phan tu thanh scalar.
    if ($text -notmatch '"scopes"') { Fail 'Khong tim thay truong scopes trong manifest' }
    $text = $text.Replace('"scopes": ["personal", "groupChat"]',
                          '"scopes": ["personal", "groupChat", "team"]')
    $parsed = $text | ConvertFrom-Json
}

$scopes = @($parsed.bots[0].scopes)
Write-Host ("Scope cua bot: " + ($scopes -join ', '))

if ($scopes -contains 'personal') {
    Write-Host "  personal  : chat rieng - dung quyen ca nhan cua nguoi hoi." -ForegroundColor Gray
}
if ($scopes -contains 'groupChat' -or $scopes -contains 'team') {
    $which = @()
    if ($scopes -contains 'groupChat') { $which += 'group chat' }
    if ($scopes -contains 'team')      { $which += 'channel cua Team' }
    Write-Host ""
    Write-Host ("LUU Y: bot dung duoc trong " + ($which -join ' va ') + ".") -ForegroundColor Yellow
    Write-Host "  Cau tra loi o day hien ra cho MOI nguoi trong cuoc tro chuyen, nen he thong" -ForegroundColor Yellow
    Write-Host "  dung pham vi CHAT HON chat rieng: chi cac nhom tai lieu da bat co 'tra loi" -ForegroundColor Yellow
    Write-Host "  trong kenh', va quyen ADMIN bi ha xuong USER." -ForegroundColor Yellow
    Write-Host "  Chua bat co do cho nhom nao => bot TU CHOI va huong dan nhan rieng." -ForegroundColor Yellow
    Write-Host "  Bat bang: setup-bot-platform.ps1 ... -ChannelAllowed" -ForegroundColor Yellow
}

# --- dong goi ------------------------------------------------------------------
$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("teamsapp-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    # UTF-8 khong BOM: Teams tu choi manifest co BOM.
    $noBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $staging 'manifest.json'), $text, $noBom)
    Copy-Item (Join-Path $here 'color.png')   (Join-Path $staging 'color.png')
    Copy-Item (Join-Path $here 'outline.png') (Join-Path $staging 'outline.png')

    if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($OutFile, 'Create')
    try {
        foreach ($name in @('manifest.json', 'color.png', 'outline.png')) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, (Join-Path $staging $name), $name) | Out-Null
        }
    } finally { $zip.Dispose() }
} finally {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
}

# --- kiem tra lai file zip vua tao ---------------------------------------------
$zip = [System.IO.Compression.ZipFile]::OpenRead($OutFile)
try {
    $entries = @($zip.Entries | ForEach-Object { $_.FullName })
} finally { $zip.Dispose() }

$expected = @('manifest.json', 'color.png', 'outline.png')
if ($entries.Count -ne 3) { Fail ("Zip phai co dung 3 muc, dang co {0}: {1}" -f $entries.Count, ($entries -join ', ')) }
foreach ($e in $entries) {
    # Khong dung regex o day: mot dau gach cheo nguoc trong pattern rat de bi an mat.
    if ([System.IO.Path]::GetFileName($e) -ne $e) {
        Fail "Zip bi boc thu muc ('$e'). Teams se tu choi ma khong noi ly do."
    }
    if ($expected -notcontains $e) { Fail "Muc la trong zip: $e" }
}

Write-Host ""
Write-Host "Da tao: $OutFile" -ForegroundColor Green
Write-Host ("  Noi dung: " + ($entries -join ', '))
Write-Host ""
Write-Host "Buoc tiep: Teams Admin Center -> Teams apps -> Manage apps -> Upload new app"
Write-Host "Nho kiem: Azure Bot -> Channels -> Microsoft Teams da Apply, va Messaging endpoint la"
Write-Host "  https://$AppHost/rag/api/messages"
