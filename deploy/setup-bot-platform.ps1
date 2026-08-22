<#
    Cau hinh nen tang cho bot Teams: nhom tai lieu -> ACL -> bot -> gan nhom -> gan Team.
    Chay mot lan sau khi da bat BOT_ENABLED. Chay lai duoc (idempotent): cai gi da co thi
    dung lai, khong tao trung.

    Tat ca chuoi trong file nay khong dau: PowerShell 5.1 doc .ps1 khong BOM theo ANSI
    nen tieng Viet co dau se bi hong. Ten hien thi co dau thi truyen qua tham so.

    Vi du:
      .\setup-bot-platform.ps1 `
        -BaseUrl https://chatbot-uat.bsc.com.vn/rag `
        -AdminApiKey $env:RAG_ADMIN_API_KEY `
        -CollectionSlug chung -CollectionName "Tai lieu chung" `
        -AclGroupIds 8f4e1c2a-1111-2222-3333-444455556666 `
        -BotSlug tro-ly -BotName "Tro ly tai lieu"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]   $BaseUrl,
    [Parameter(Mandatory = $true)] [string]   $AdminApiKey,

    [Parameter(Mandatory = $true)] [string]   $CollectionSlug,
    [string]   $CollectionName = "",
    [string[]] $AclGroupIds = @(),
    [switch]   $ChannelAllowed,

    [Parameter(Mandatory = $true)] [string]   $BotSlug,
    [string]   $BotName = "",
    [string]   $Greeting = "",
    [string]   $PersonaPrompt = "",

    # Chi truyen khi muon MO bot cho moi nguoi da xac thuc. Khong truyen thi khong
    # dung den doi tuong su dung dang co - tranh vo tinh noi rong quyen.
    [switch]   $OpenToEveryone,

    # aadGroupId cua Team (Teams -> ... -> Get link to team -> groupId). Chi can khi
    # muon bot tra loi trong kenh cua Team do.
    [string]   $TeamAadGroupId = ""
)

$ErrorActionPreference = 'Stop'
$root = $BaseUrl.TrimEnd('/') + '/api/v1/rag/admin'
$headers = @{ 'X-API-Key' = $AdminApiKey }

function Say($text)  { Write-Host $text }
function Ok($text)   { Write-Host "  [OK]   $text"   -ForegroundColor Green }
function Warn($text) { Write-Host "  [!]    $text"   -ForegroundColor Yellow }
function Step($text) { Write-Host "`n=== $text" -ForegroundColor Cyan }

# ConvertTo-Json cua PS 5.1 lam sap mang mot phan tu thanh scalar, ma cac endpoint
# nay nhan mang that -> dung JSON tu dung tay cho chac.
function JStr($value) {
    if ($null -eq $value) { return 'null' }
    $escaped = ([string]$value).Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", '\n')
    return '"' + $escaped + '"'
}
function JArr($values) {
    if (-not $values -or $values.Count -eq 0) { return '[]' }
    return '[' + (($values | ForEach-Object { JStr $_ }) -join ',') + ']'
}

function Call($method, $path, $jsonBody) {
    $uri = $root + $path
    try {
        if ($null -eq $jsonBody) {
            return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers
        }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
        return Invoke-RestMethod -Method $method -Uri $uri -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes
    } catch {
        $status = ''
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        throw "$method $path that bai (HTTP $status): $($_.Exception.Message)"
    }
}

Say "Muc tieu : $root"
Say "Nhom     : $CollectionSlug"
Say "Bot      : $BotSlug"

# ---------------------------------------------------------------- 0. Tinh trang hien tai
Step "Buoc 0 - doc tinh trang hien tai"
$platform = Call GET '/platform' $null
Ok "Da ket noi. Dang co $($platform.collections.Count) nhom tai lieu, $($platform.bots.Count) bot."

# ------------------------------------------------------------------- 1. Nhom tai lieu
Step "Buoc 1 - nhom tai lieu '$CollectionSlug'"
$collection = $platform.collections | Where-Object { $_.slug -eq $CollectionSlug } | Select-Object -First 1

function CollectionBody($name) {
    return '{"slug":' + (JStr $CollectionSlug) +
           ',"name":' + (JStr $name) +
           ',"channelAllowed":' + $(if ($ChannelAllowed) { 'true' } else { 'false' }) + '}'
}

if (-not $collection) {
    $name = $(if ($CollectionName) { $CollectionName } else { $CollectionSlug })
    $created = Call POST '/collections' (CollectionBody $name)
    $collectionId = $created.id
    Ok "Da tao nhom '$CollectionSlug' (id=$collectionId)."
} else {
    $collectionId = $collection.id
    if ($ChannelAllowed -and -not $collection.channelAllowed) {
        # Keep the name already in the database when the caller did not pass one.
        $name = $(if ($CollectionName) { $CollectionName } else { $collection.name })
        Call PUT "/collections/$collectionId" (CollectionBody $name) | Out-Null
        Ok "Da bat 'tra loi trong kenh' cho nhom '$CollectionSlug'."
    } else {
        Ok "Nhom '$CollectionSlug' da co (id=$collectionId), giu nguyen."
    }
}
Warn "Tai lieu phai duoc nap voi category = '$CollectionSlug' moi thuoc nhom nay."

# ------------------------------------------------------------------------------ 2. ACL
Step "Buoc 2 - quyen doc (ACL) cua nhom"
if ($AclGroupIds.Count -gt 0) {
    $acl = '{"groupIds":' + (JArr $AclGroupIds) + '}'
    $r = Call PUT "/collections/$collectionId/acl" $acl
    Ok $r.message
    Warn "Tu luc co ACL trong CSDL, rag.entra.group-departments trong file cau hinh bi BO QUA hoan toan."
} else {
    Warn "Khong truyen -AclGroupIds nen giu nguyen ACL hien tai."
    Warn "ACL rong = DONG: khong ai doc duoc nhom nay (tru quan tri). Bot se tu choi tra loi."
}

# ------------------------------------------------------------------------------ 3. Bot
Step "Buoc 3 - bot '$BotSlug'"
$bot = $platform.bots | Where-Object { $_.slug -eq $BotSlug } | Select-Object -First 1
if (-not $bot) {
    $botBody = '{"slug":' + (JStr $BotSlug) +
               ',"displayName":' + (JStr ($(if ($BotName) { $BotName } else { $BotSlug }))) + '}'
    $created = Call POST '/bots' $botBody
    $botId = $created.id
    Ok "Da tao bot '$BotSlug' (id=$botId)."
    $bot = $null
} else {
    $botId = $bot.id
    Ok "Bot '$BotSlug' da co (id=$botId)."
}

if ($Greeting -or $PersonaPrompt) {
    $upd = '{"slug":' + (JStr $BotSlug) +
           ',"displayName":' + (JStr ($(if ($BotName) { $BotName } elseif ($bot) { $bot.displayName } else { $BotSlug }))) +
           ',"greeting":' + (JStr $Greeting) +
           ',"personaPrompt":' + (JStr $PersonaPrompt) + '}'
    Call PUT "/bots/$botId" $upd | Out-Null
    Ok "Da cap nhat loi chao / giong dieu."
    if ($PersonaPrompt) {
        Warn "Giong dieu duoc chen TRUOC cac quy tac bat buoc trong prompt, nen mot cau persona khong the vo hieu quy tac 'chi tra loi theo tai lieu'."
    }
}

# --------------------------------------------------------------- 4. Gan nhom cho bot
Step "Buoc 4 - gan nhom tai lieu cho bot"
$slugs = @($CollectionSlug)
if ($bot -and $bot.collectionSlugs) { $slugs = @($bot.collectionSlugs) + $CollectionSlug }
$slugs = $slugs | Where-Object { $_ } | Sort-Object -Unique
$r = Call PUT "/bots/$botId/collections" (JArr $slugs)
Ok "$($r.message) [$($slugs -join ', ')]"

# ------------------------------------------------------------------ 5. Bot mac dinh
Step "Buoc 5 - dat bot mac dinh"
$r = Call POST "/bots/$botId/default" $null
Ok $r.message

# --------------------------------------------------------------- 6. Doi tuong su dung
Step "Buoc 6 - doi tuong su dung bot"
if ($OpenToEveryone) {
    $r = Call PUT "/bots/$botId/audience" '{"groupIds":[],"userIds":[]}'
    Ok $r.message
} else {
    Warn "Bo qua (khong truyen -OpenToEveryone). Doi tuong RONG = MO cho moi nguoi da xac thuc; day la mac dinh cua bot moi tao."
}

# ------------------------------------------------------------------- 7. Gan Team (tuy chon)
Step "Buoc 7 - gan bot cho Team (kenh)"
if ($TeamAadGroupId) {
    $exists = $platform.channelBindings | Where-Object {
        $_.teamAadGroupId -eq $TeamAadGroupId -and $_.botId -eq $botId }
    if ($exists) {
        Ok "Team nay da gan cho bot '$BotSlug', giu nguyen."
    } else {
        $bind = '{"botId":' + $botId + ',"teamAadGroupId":' + (JStr $TeamAadGroupId) + '}'
        $r = Call POST '/bot-channels' $bind
        Ok $r.message
    }
    if (-not $ChannelAllowed) {
        Warn "Da gan Team nhung chua nhom nao bat 'tra loi trong kenh' -> bot van tu choi trong kenh. Chay lai voi -ChannelAllowed neu that su muon ca kenh doc duoc nhom nay."
    }
} else {
    Ok "Bo qua - chi cau hinh chat rieng. Chat rieng khong can gan Team."
}

# ---------------------------------------------------------------------- 8. Kiem tra lai
Step "Buoc 8 - kiem tra lai"
$after = Call GET '/platform' $null
$myBot = $after.bots | Where-Object { $_.slug -eq $BotSlug } | Select-Object -First 1
Say ("  bot            : " + $myBot.slug + " (mac dinh=" + $myBot.isDefault + ", trang thai=" + $myBot.status + ")")
Say ("  nhom cua bot   : " + ($myBot.collectionSlugs -join ', '))
Say ("  ACL da cau hinh: " + $after.aclConfigured)
if ($after.botsWithoutCollections.Count -gt 0) {
    Warn ("Bot chua co nhom tai lieu: " + ($after.botsWithoutCollections -join ', '))
}
if ($after.orphanCategories.Count -gt 0) {
    Warn ("Category co tai lieu nhung chua khai bao thanh nhom: " + ($after.orphanCategories -join ', '))
}

try {
    $status = Call GET '/bot-status?probeToken=true' $null
    Say "`n  --- bot-status ---"
    Say ("  app id            : " + $status.azure.appId)
    Say ("  app type / tenant : " + $status.azure.appType + " / " + $status.azure.tokenTenant)
    Say ("  co app password   : " + $status.azure.appPasswordConfigured)
    Say ("  Entra / Graph     : " + $status.identity.entraEnabled + " / " + $status.identity.graphReady)
    Say ("  token chieu ra    : " + $status.outboundToken.ok)
    if (-not $status.outboundToken.ok) {
        Warn ("Khong xin duoc token: " + $status.outboundToken.error)
        Warn $status.outboundToken.hint
    }
    if ($status.readiness.Count -gt 0) {
        Say "`n  --- Con thieu ---"
        $status.readiness | ForEach-Object { Warn $_ }
    } else {
        Ok "bot-status khong bao thieu gi."
    }
} catch {
    Warn "Khong doc duoc /bot-status: $($_.Exception.Message)"
    Warn "Thuong la BOT_ENABLED chua bat (endpoint chi ton tai khi bot duoc bat)."
}

Say "`nXong. Buoc tiep theo: nhan rieng cho bot trong Teams mot cau hoi co trong tai lieu."
