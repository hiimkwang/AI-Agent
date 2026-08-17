#!/usr/bin/env bash
# =====================================================================
# Khoi phuc CSDL cua AI-Agent tu mot ban sao luu.
#
# Cach dung:
#   ./restore.sh /var/backups/aiagent/rag_db-20260817-021500.dump
#
# GHI DE toan bo CSDL hien tai. Script hoi lai mot lan truoc khi lam - dat
# FORCE=1 de bo qua (chi dung trong script tu dong).
#
# QUAN TRONG: DUNG ung dung truoc khi khoi phuc. Flyway chay luc khoi dong,
# va mot ung dung dang chay tren CSDL vua bi thay the se hanh xu kho luong.
# =====================================================================
set -euo pipefail

DUMP="${1:-}"
if [ -z "${DUMP}" ] || [ ! -f "${DUMP}" ]; then
    echo "Cach dung: $0 <duong-dan-toi-file.dump>" >&2
    echo "Cac ban sao luu hien co:" >&2
    ls -lh "${BACKUP_DIR:-/var/backups/aiagent}"/rag_db-*.dump 2>/dev/null >&2 || echo "  (khong co)" >&2
    exit 1
fi

DB_NAME="${DB_NAME:-rag_db}"
DB_USER="${DB_USER:-admin}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-rag-postgres}"

# --------------------------------------------------- Kiem tra truoc
if ! pg_restore --list "${DUMP}" > /dev/null 2>&1; then
    echo "LOI: '${DUMP}' khong phai ban dump hop le." >&2
    exit 1
fi

echo "Sap KHOI PHUC '${DUMP}' vao CSDL '${DB_NAME}'."
echo "Toan bo du lieu hien tai trong CSDL do se BI THAY THE."
if [ "${FORCE:-0}" != "1" ]; then
    read -r -p "Go 'KHOI PHUC' de xac nhan: " answer
    [ "${answer}" = "KHOI PHUC" ] || { echo "Da huy."; exit 1; }
fi

# --------------------------------------------------- Khoi phuc
# --clean --if-exists: xoa doi tuong cu truoc khi tao lai, khong loi khi chua co.
# --no-owner: khoi phuc duoc sang may co ten chu so huu khac.
# KHONG dung --single-transaction o day: voi CSDL lon co extension va index HNSW,
# mot transaction duy nhat de vuot gioi han bo nho va do o phut cuoi.
RESTORE_ARGS=(--clean --if-exists --no-owner --dbname "${DB_NAME}")

if [ -n "${DOCKER_CONTAINER}" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"; then
    echo "Dich: container Docker '${DOCKER_CONTAINER}'"
    docker exec -i "${DOCKER_CONTAINER}" \
        pg_restore -U "${DB_USER}" "${RESTORE_ARGS[@]}" < "${DUMP}"
else
    echo "Dich: Postgres tai ${DB_HOST}:${DB_PORT}"
    pg_restore -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" "${RESTORE_ARGS[@]}" < "${DUMP}"
fi

echo
echo "Khoi phuc xong. Hai viec PHAI lam tiep:"
echo "  1. Khoi dong lai ung dung (Flyway se doi chieu schema luc khoi dong)."
echo "  2. Kiem tra so chieu vector khop cau hinh:"
echo "     curl -H \"X-API-Key: \$RAG_ADMIN_API_KEY\" http://localhost:8080/api/v1/rag/admin/stats"
echo "     Neu 'embeddingDimensions' khac rag.embedding.dimensions thi TIM KIEM SE SAI"
echo "     ma khong bao loi - phai nap lai toan bo tai lieu."
