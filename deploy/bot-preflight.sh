#!/usr/bin/env bash
# Kiem tra chuoi lien ket cua bot Teams, theo dung thu tu phu thuoc.
#
# Ly do ton tai: moi mat khau cua duong nay deu IM LANG. Endpoint sai -> bot khong phan hoi,
# khong log. Secret het han -> bot khong phan hoi, log mot dong duy nhat. Chua gan nhom tai
# lieu -> bot tra loi "khong co quyen" ma nghe nhu loi phan quyen. Script chay tu tren xuong
# va dung ngay o mat khau dau tien bi dut.
#
#   ./bot-preflight.sh                       # doc RAG_ADMIN_API_KEY tu moi truong
#   BASE=http://127.0.0.1:8080/rag ./bot-preflight.sh

set -u

BASE="${BASE:-http://127.0.0.1:8080/rag}"
PUBLIC_HOST="${PUBLIC_HOST:-chatbot-uat.bsc.com.vn}"
KEY="${RAG_ADMIN_API_KEY:-}"

pass() { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mLOI\033[0m  %s\n' "$1"; }
warn() { printf '  \033[33mLUU Y\033[0m %s\n' "$1"; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

json_get() {
    # $1 = json, $2 = bieu thuc jq. Khong co jq thi in nguyen van de doc tay.
    if command -v jq >/dev/null 2>&1; then
        printf '%s' "$1" | jq -r "$2" 2>/dev/null
    else
        printf '__NO_JQ__'
    fi
}

# ---------------------------------------------------------------- 1. ung dung
step "1. Ung dung con song"
health=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/actuator/health" 2>/dev/null)
if [ "$health" = "200" ]; then
    pass "$BASE/actuator/health -> 200"
else
    fail "$BASE/actuator/health -> ${health:-khong ket noi duoc}"
    echo "       systemctl status aiagent; journalctl -u aiagent -n 50"
    exit 1
fi

# ------------------------------------------------------ 2. endpoint /api/messages
step "2. Endpoint /api/messages ton tai va tu choi request khong co token"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
       -X POST "$BASE/api/messages" -H 'Content-Type: application/json' \
       -d '{"type":"message"}' 2>/dev/null)
case "$code" in
    401) pass "-> 401 (dung: BotAuthenticator tu choi vi thieu token)" ;;
    404) fail "-> 404. Hoac BOT_ENABLED chua bat (khong co controller), hoac sai SERVER_CONTEXT_PATH."
         echo "       grep -E 'BOT_ENABLED|SERVER_CONTEXT_PATH' /app/aiagent/config/aiagent.env"
         exit 1 ;;
    400) warn "-> 400: body khong phai JSON. Loi cach goi curl, khong phai loi he thong." ;;
    *)   fail "-> ${code:-khong ket noi duoc} (khong mong doi)"; exit 1 ;;
esac

# ------------------------------------------------------------- 3. bot-status
step "3. Trang thai bot phia ung dung"
if [ -z "$KEY" ]; then
    warn "Thieu RAG_ADMIN_API_KEY -> bo qua buoc nay."
    echo "       export RAG_ADMIN_API_KEY=... roi chay lai de kiem readiness + token chieu ra."
else
    body=$(curl -s --max-time 25 -H "X-API-Key: $KEY" \
           "$BASE/api/v1/rag/admin/bot-status?probeToken=true" 2>/dev/null)
    if [ -z "$body" ]; then
        fail "Khong doc duoc /admin/bot-status"
    elif printf '%s' "$body" | grep -q '"status":40'; then
        fail "bot-status tra loi tu choi: $(printf '%s' "$body" | head -c 200)"
    else
        ready=$(json_get "$body" '.readiness | length')
        if [ "$ready" = "__NO_JQ__" ]; then
            warn "Khong co jq tren may nay, in nguyen van de doc tay:"
            printf '%s\n' "$body"
        elif [ "$ready" = "0" ]; then
            pass "readiness rong: phia ung dung khong con vuong gi"
        else
            fail "$ready diem chua san sang:"
            json_get "$body" '.readiness[]' | sed 's/^/       - /'
        fi

        tok=$(json_get "$body" '.outboundToken.ok')
        case "$tok" in
            true)  pass "xin duoc token chieu ra: BOT_APP_ID/PASSWORD/APP_TYPE dung" ;;
            false) fail "KHONG xin duoc token chieu ra -> bot se nhan cau hoi nhung im lang."
                   json_get "$body" '.outboundToken' | sed 's/^/       /'
                   echo "       Thuong la: secret sai/het han, hoac nham BOT_APP_TYPE"
                   echo "       (multi-tenant xin token o botframework.com, khong phai tenant cong ty)." ;;
            *)     warn "khong doc duoc ket qua token probe" ;;
        esac
    fi
fi

# --------------------------------------------------- 4. duong tu Internet vao
step "4. Azure goi vao duoc hay khong (buoc quyet dinh)"
warn "Khong the ket luan tu trong may chu: DNS noi bo thuong tra ve IP noi bo."
echo "       Chay lenh nay tu mot may NGOAI mang BSC (4G, may nha):"
echo ""
echo "         curl -i -X POST https://$PUBLIC_HOST/rag/api/messages \\"
echo "           -H 'Content-Type: application/json' -d '{\"type\":\"message\"}'"
echo ""
echo "       401 = xong. Timeout = chua publish ra Internet. Loi TLS = Azure se tu choi cert."

step "Tom tat"
echo "  Thu tu phu thuoc: (1) app song -> (2) endpoint dung -> (3) cau hinh + secret ->"
echo "  (4) duong mang -> cai Teams app -> nhan tin rieng cho bot."
echo "  Chi tiet tung buoc: docs/TEAMS-BOT-GOLIVE.md"
