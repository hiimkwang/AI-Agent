#!/usr/bin/env bash
# Restore the AI-Agent database from a backup. Overwrites the target database.
#   ./restore.sh /var/backups/aiagent/rag_db-20260817-021500.dump
# FORCE=1 skips the confirmation prompt.
# Stop the application first - Flyway runs at startup.
set -euo pipefail

DUMP="${1:-}"
if [ -z "${DUMP}" ] || [ ! -f "${DUMP}" ]; then
    echo "Cach dung: $0 <duong-dan-toi-file.dump>" >&2
    echo "Cac ban sao luu hien co:" >&2
    APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
    ls -lh "${BACKUP_DIR:-$APP_DIR/work/backup}"/rag_db-*.dump 2>/dev/null >&2 || echo "  (khong co)" >&2
    exit 1
fi

DB_NAME="${DB_NAME:-rag_db}"
DB_USER="${DB_USER:-admin}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-rag-postgres}"

CONTAINER_CLI="${CONTAINER_CLI:-}"
if [ -z "${CONTAINER_CLI}" ]; then
    for _cli in docker podman; do
        if command -v "${_cli}" >/dev/null 2>&1; then CONTAINER_CLI="${_cli}"; break; fi
    done
fi

# Validate before --clean wipes a working database. pg_restore may be absent on
# a container-only host, so fall back to running it inside the container.
if command -v pg_restore >/dev/null 2>&1; then
    pg_restore --list "${DUMP}" > /dev/null 2>&1 \
        || { echo "LOI: '${DUMP}' khong phai ban dump hop le." >&2; exit 1; }
elif [ -n "${CONTAINER_CLI}" ]; then
    "${CONTAINER_CLI}" exec -i "${DOCKER_CONTAINER}" pg_restore --list < "${DUMP}" > /dev/null 2>&1 \
        || { echo "LOI: '${DUMP}' khong phai ban dump hop le." >&2; exit 1; }
else
    echo "LOI: khong co pg_restore tren host lan container de kiem tra ban dump." >&2
    exit 1
fi

echo "Sap KHOI PHUC '${DUMP}' vao CSDL '${DB_NAME}'."
echo "Toan bo du lieu hien tai trong CSDL do se BI THAY THE."
if [ "${FORCE:-0}" != "1" ]; then
    read -r -p "Go 'KHOI PHUC' de xac nhan: " answer
    [ "${answer}" = "KHOI PHUC" ] || { echo "Da huy."; exit 1; }
fi

# No --single-transaction: with extensions and HNSW indexes it can exceed
# memory limits and fail at the very end.
RESTORE_ARGS=(--clean --if-exists --no-owner --dbname "${DB_NAME}")

if [ -n "${DOCKER_CONTAINER}" ] && [ -n "${CONTAINER_CLI}" ] \
   && "${CONTAINER_CLI}" ps --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"; then
    echo "Dich: container '${DOCKER_CONTAINER}' (${CONTAINER_CLI})"
    "${CONTAINER_CLI}" exec -i "${DOCKER_CONTAINER}" \
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
