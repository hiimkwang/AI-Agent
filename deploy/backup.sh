#!/usr/bin/env bash
# =====================================================================
# Sao luu CSDL cua AI-Agent.
#
# Mat CSDL nay khong chi mat lich su: mat toan bo vector da nhung, tuc la
# phai TRA TIEN nap lai ca kho tai lieu, va mat luon nhat ky kiem toan -
# dung thu can de giai trinh.
#
# Dung dinh dang custom (-Fc) chu khong phai SQL van ban:
#   - nen san, nho hon nhieu lan (cot embedding rat nang)
#   - pg_restore khoi phuc duoc TUNG BANG, khong phai tat ca hoac khong gi
#   - khong bi hong vi van de bang ma khi di qua may khac
#
# Chay tay:  ./backup.sh
# Chay theo lich (2h15 sang hang ngay), them vao crontab cua user aiagent:
#   15 2 * * * /opt/aiagent/deploy/backup.sh >> /var/log/aiagent-backup.log 2>&1
# =====================================================================
set -euo pipefail

# --------------------------------------------------- Cau hinh
BACKUP_DIR="${BACKUP_DIR:-/var/backups/aiagent}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DB_NAME="${DB_NAME:-rag_db}"
DB_USER="${DB_USER:-admin}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
# Ten container Postgres khi chay bang Docker; de trong neu Postgres cai truc tiep.
DOCKER_CONTAINER="${DOCKER_CONTAINER:-rag-postgres}"

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="${BACKUP_DIR}/rag_db-${STAMP}.dump"

mkdir -p "${BACKUP_DIR}"

echo "[$(date '+%F %T')] Bat dau sao luu -> ${TARGET}"

if [ -n "${DOCKER_CONTAINER}" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"; then
    echo "  Nguon: container Docker '${DOCKER_CONTAINER}'"
    docker exec "${DOCKER_CONTAINER}" \
        pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc > "${TARGET}"
else
    echo "  Nguon: Postgres tai ${DB_HOST}:${DB_PORT}"
    # PGPASSWORD lay tu moi truong (vd EnvironmentFile cua systemd); khong dat o day.
    pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -Fc > "${TARGET}"
fi

# Ban sao luu rong hoac qua nho gan nhu chac chan la that bai am tham (vd sai mat
# khau nhung pg_dump van tao file). Bat o day chu khong de den luc can khoi phuc.
SIZE=$(stat -c%s "${TARGET}" 2>/dev/null || stat -f%z "${TARGET}")
if [ "${SIZE}" -lt 4096 ]; then
    echo "  LOI: ban sao luu chi ${SIZE} byte - gan nhu chac chan that bai." >&2
    rm -f "${TARGET}"
    exit 1
fi

chmod 600 "${TARGET}"
echo "  Xong: $(du -h "${TARGET}" | cut -f1)"

# --------------------------------------------------- Kiem tra doc lai duoc
# Mot ban sao luu chua bao gio duoc doc thu khong phai la ban sao luu.
if pg_restore --list "${TARGET}" > /dev/null 2>&1; then
    echo "  Kiem tra: doc duoc muc luc, ban dump hop le."
else
    echo "  LOI: khong doc duoc muc luc cua ban dump - file co the hong." >&2
    exit 1
fi

# --------------------------------------------------- Don ban cu
DELETED=$(find "${BACKUP_DIR}" -name 'rag_db-*.dump' -mtime "+${KEEP_DAYS}" -print -delete | wc -l)
echo "  Da xoa ${DELETED} ban sao luu cu hon ${KEEP_DAYS} ngay."

echo "[$(date '+%F %T')] Hoan tat."
