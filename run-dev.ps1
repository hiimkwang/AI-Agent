# =====================================================================
# Chay he thong o may dev.
#
# Mac dinh chay CHE DO OFFLINE: khong can API key nao, dung embedding LOCAL
# (all-MiniLM ONNX chay trong tien trinh) va tat rerank.
#
# GIOI HAN CUA CHE DO NAY - phai biet truoc khi thu:
#   * Hoi dap KHONG tra loi duoc: buoc sinh cau tra loi can mot LLM.
#   * Sinh bo cau hoi (/eval/cases/generate) khong chay: cung can LLM.
#   * Chat luong tim kiem tieng Viet KEM: all-MiniLM khong duoc huan luyen cho
#     tieng Viet. Chi de kiem tra duong ong, khong danh gia chat luong o day.
#
# Co API key thi chay:  .\run-dev.ps1 -OpenAiKey "sk-..."
# => bat day du: hoi dap, rerank bang LLM, sinh bo cau hoi.
#    Luu y doi embedding sang OpenAI can TAO LAI schema (so chieu 384 -> 1536),
#    nen script chi doi phan CHAT/LLM, giu embedding LOCAL de khong pha DB dang co.
#    Muon doi ca embedding: xem docs/EMBEDDING-UPGRADE.md.
# =====================================================================
param(
    [string] $OpenAiKey    = $env:OPENAI_API_KEY,
    [string] $AnthropicKey = $env:ANTHROPIC_API_KEY,
    [int]    $Port         = 8080,
    # Chi dung o may dev: khong co API key nao van vao duoc voi quyen ADMIN.
    [switch] $RequireApiKey
)

$ErrorActionPreference = 'Stop'

# --- Postgres -------------------------------------------------------
$running = docker ps --filter name=rag-postgres --format "{{.Names}}"
if (-not $running) {
    Write-Host "Khoi dong Postgres..." -ForegroundColor Cyan
    docker compose up -d | Out-Null
    Start-Sleep -Seconds 5
}

# --- Cau hinh chung -------------------------------------------------
$env:SERVER_PORT = "$Port"
$env:RAG_EMBEDDING_PROVIDER = 'LOCAL'
$env:RAG_EMBEDDING_DIM = '384'
$env:RAG_EMBEDDING_TRIAL = 'true'
$env:RAG_EMBEDDING_TRIAL_PROVIDER = 'LOCAL'
$env:RAG_EMBEDDING_TRIAL_DIM = '384'

if ($RequireApiKey) {
    $env:RAG_ALLOW_ANONYMOUS = 'false'
    if (-not $env:RAG_ADMIN_API_KEY) { $env:RAG_ADMIN_API_KEY = 'dev-admin-key' }
    Write-Host "Xac thuc: BAT. Gui header  X-API-Key: $($env:RAG_ADMIN_API_KEY)" -ForegroundColor Yellow
} else {
    $env:RAG_ALLOW_ANONYMOUS = 'true'
    Write-Host "Xac thuc: TAT (che do dev). Dung -RequireApiKey de bat." -ForegroundColor Yellow
}

# --- Chon LLM -------------------------------------------------------
if ($OpenAiKey) {
    $env:OPENAI_API_KEY = $OpenAiKey
    $env:RAG_LLM_PROVIDER = 'OPENAI'; $env:RAG_LLM_MODEL = 'gpt-4o-mini'
    $env:RAG_INTERNAL_PROVIDER = 'OPENAI'; $env:RAG_INTERNAL_MODEL = 'gpt-4o-mini'
    $env:RAG_RERANK_PROVIDER = 'LLM'
    Write-Host "LLM: OpenAI gpt-4o-mini - hoi dap va sinh bo cau hoi CHAY duoc." -ForegroundColor Green
} elseif ($AnthropicKey) {
    $env:ANTHROPIC_API_KEY = $AnthropicKey
    $env:RAG_LLM_PROVIDER = 'ANTHROPIC'; $env:RAG_LLM_MODEL = 'claude-opus-5'
    $env:RAG_INTERNAL_PROVIDER = 'ANTHROPIC'; $env:RAG_INTERNAL_MODEL = 'claude-opus-5'
    $env:RAG_RERANK_PROVIDER = 'LLM'
    Write-Host "LLM: Claude - hoi dap va sinh bo cau hoi CHAY duoc." -ForegroundColor Green
} else {
    $env:RAG_RERANK_PROVIDER = 'NONE'
    Write-Host "LLM: KHONG CO. Hoi dap se bao loi o buoc sinh cau tra loi;" -ForegroundColor Red
    Write-Host "     nap tai lieu, do truy xuat va man quan tri VAN chay binh thuong." -ForegroundColor Red
}

Write-Host ""
Write-Host "Hoi dap:   http://localhost:$Port/"          -ForegroundColor Cyan
Write-Host "Quan tri:  http://localhost:$Port/admin.html" -ForegroundColor Cyan
Write-Host ""

.\mvnw.cmd spring-boot:run
