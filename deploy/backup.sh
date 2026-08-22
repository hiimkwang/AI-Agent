#!/usr/bin/env bash
# Back up the AI-Agent database in pg_dump custom format (-Fc): compressed,
# restorable per table, encoding-safe across machines.
#   ./backup.sh
#   15 2 * * * DB_USER=rag /app/aiagent/deploy/backup.sh >> /app/aiagent/logs/backup.log 2>&1
set -euo pipefail

# Everything stays under the application directory: this script lives in
# <APP_DIR>/deploy, so backups land in <APP_DIR>/work/backup.
APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/work/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DB_NAME="${DB_NAME:-rag_db}"
DB_USER="${DB_USER:-admin}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
# Empty if Postgres is installed directly on the host.
DOCKER_CONTAINER="${DOCKER_CONTAINER:-rag-postgres}"

CONTAINER_CLI="${CONTAINER_CLI:-}"
if [ -z "${CONTAINER_CLI}" ]; then
    for _cli in docker podman; do
        if command -v "${_cli}" >/dev/null 2>&1; then CONTAINER_CLI="${_cli}"; break; fi
    done
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="${BACKUP_DIR}/rag_db-${STAMP}.dump"

mkdir -p "${BACKUP_DIR}"

echo "[$(date '+%F %T')] Bat dau sao luu -> ${TARGET}"

if [ -n "${DOCKER_CONTAINER}" ] && [ -n "${CONTAINER_CLI}" ] \
   && "${CONTAINER_CLI}" ps --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"; then
    echo "  Nguon: container '${DOCKER_CONTAINER}' (${CONTAINER_CLI})"
    "${CONTAINER_CLI}" exec "${DOCKER_CONTAINER}" \
        pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc > "${TARGET}"
else
    echo "  Nguon: Postgres tai ${DB_HOST}:${DB_PORT}"
    # PGPASSWORD comes from the environment.
    pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -Fc > "${TARGET}"
fi

# A wrong password still produces a file, just a tiny one.
SIZE=$(stat -c%s "${TARGET}" 2>/dev/null || stat -f%z "${TARGET}")
if [ "${SIZE}" -lt 4096 ]; then
    echo "  LOI: ban sao luu chi ${SIZE} byte - gan nhu chac chan that bai." >&2
    rm -f "${TARGET}"
    exit 1
fi

chmod 600 "${TARGET}"
echo "  Xong: $(du -h "${TARGET}" | cut -f1)"

# Read the table of contents back. A container-only host has no pg_restore,
# so fall back to the container rather than failing a good backup.
if command -v pg_restore >/dev/null 2>&1; then
    VERIFY_OK=$(pg_restore --list "${TARGET}" >/dev/null 2>&1 && echo yes || echo no)
elif [ -n "${CONTAINER_CLI}" ]; then
    VERIFY_OK=$("${CONTAINER_CLI}" exec -i "${DOCKER_CONTAINER}" \
        pg_restore --list < "${TARGET}" >/dev/null 2>&1 && echo yes || echo no)
else
    VERIFY_OK=skip
fi

case "${VERIFY_OK}" in
    yes)  echo "  Kiem tra: doc duoc muc luc, ban dump hop le." ;;
    skip) echo "  CANH BAO: khong co pg_restore lan container de kiem tra ban dump." >&2 ;;
    *)    echo "  LOI: khong doc duoc muc luc cua ban dump - file co the hong." >&2
          exit 1 ;;
esac

DELETED=$(find "${BACKUP_DIR}" -name 'rag_db-*.dump' -mtime "+${KEEP_DAYS}" -print -delete | wc -l)
echo "  Da xoa ${DELETED} ban sao luu cu hon ${KEEP_DAYS} ngay."

echo "[$(date '+%F %T')] Hoan tat."
