#!/usr/bin/env bash
# Remove the systemd service and the application files. Reads the same deploy.env
# as install.sh, so it always targets the service that was installed.
#
#     sudo ./uninstall.sh              remove service and application directory
#     sudo ./uninstall.sh --keep-work  same, but move work/ aside first
#     sudo ./uninstall.sh --keep-files remove the service only
#     sudo ./uninstall.sh -y           no confirmation prompt
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

KEEP_FILES=false
KEEP_WORK=false
ASSUME_YES=false
ENV_FILE="$SCRIPT_DIR/deploy.env"

usage() { sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --env-file)   ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
      --keep-files) KEEP_FILES=true; shift ;;
      --keep-work)  KEEP_WORK=true; shift ;;
      -y|--yes)     ASSUME_YES=true; shift ;;
      -h|--help)    usage ;;
      *) die "Unknown argument: $1 (see ./uninstall.sh --help)" ;;
    esac
  done
}

is_safe_path() {
  local p="${1%/}"
  case "$p" in
    ''|/|/app|/usr|/usr/*|/etc|/etc/*|/var|/var/log|/opt|/home|/root|/bin|/sbin|/lib|/boot|/srv|/tmp)
      return 1 ;;
  esac
  [[ "$p" != "${HOME:-/nonexistent}" ]] || return 1
  local depth; depth=$(awk -F/ '{print NF-1}' <<<"$p")
  (( depth >= 2 ))
}

guard_path() {
  is_safe_path "$1" || die "Refusing to delete '$1' (system or too shallow a path)."
}

# LOG_DIR / CONFIG_DIR when deploy.env puts them outside APP_DIR.
outside_dirs() {
  local d
  for d in "$LOG_DIR" "$CONFIG_DIR"; do
    [[ -d "$d" && "$d" != "$APP_DIR" && "$d" != "$APP_DIR"/* ]] || continue
    if is_safe_path "$d"; then echo "$d"; else warn "Keeping '$d' (unsafe to delete)."; fi
  done
  return 0
}

show_plan() {
  echo
  log "Plan:"
  echo "  - stop and disable  : $SERVICE_NAME"
  echo "  - remove unit file  : $UNIT_FILE"
  echo "  - remove drop-ins   : $DROPIN_DIR"
  if $KEEP_FILES; then
    echo "  - keep directory    : $APP_DIR"
  else
    echo "  - DELETE directory  : $APP_DIR"
    if $KEEP_WORK; then
      echo "      work/ moved to ${APP_DIR}.work-backup, everything else deleted"
    else
      echo "      includes work/, deploy/, config/, logs/, lib/ - NOT recoverable"
    fi
    local d
    while IFS= read -r d; do
      [[ -n "$d" ]] && echo "  - DELETE directory  : $d"
    done < <(outside_dirs)
    if [[ "${UNINSTALL_REMOVE_USER,,}" == "true" && "$SERVICE_USER" != "root" ]]; then
      echo "  - remove user       : $SERVICE_USER"
    fi
  fi
}

confirm() {
  $ASSUME_YES && return 0
  local reply
  echo
  read -r -p "Proceed? [y/N] " reply || reply=""
  [[ "$reply" =~ ^([yY]|[yY][eE][sS])$ ]] || die "Aborted; nothing changed."
}

remove_service() {
  if ! service_known && ! service_unit_exists; then
    warn "Service '$SERVICE_NAME' not found; may already be removed."
  else
    stop_service_if_running
    if service_is_enabled; then
      $SUDO systemctl disable "$SERVICE_NAME.service" >/dev/null 2>&1 \
        && ok "Disabled $SERVICE_NAME." || warn "Cannot disable $SERVICE_NAME."
    fi
  fi

  [[ -f "$UNIT_FILE" ]] && { $SUDO rm -f "$UNIT_FILE"; ok "Removed $UNIT_FILE"; }
  [[ -d "$DROPIN_DIR" ]] && { $SUDO rm -rf "$DROPIN_DIR"; ok "Removed $DROPIN_DIR"; }
  $SUDO find /etc/systemd/system -maxdepth 2 -name "$SERVICE_NAME.service" -type l -delete 2>/dev/null || true

  $SUDO systemctl daemon-reload
  $SUDO systemctl reset-failed "$SERVICE_NAME.service" 2>/dev/null || true

  ! service_is_active || die "$SERVICE_NAME is still active; check systemctl status."
  ok "Service $SERVICE_NAME removed."
}

remove_user() {
  $KEEP_FILES && return 0
  [[ "${UNINSTALL_REMOVE_USER,,}" == "true" ]] || return 0
  [[ "$SERVICE_USER" != "root" ]] || return 0
  id -u "$SERVICE_USER" >/dev/null 2>&1 || return 0

  if $SUDO grep -rlE "^User=$SERVICE_USER\$" /etc/systemd/system/*.service >/dev/null 2>&1; then
    warn "Another service still uses $SERVICE_USER; keeping the user."
    return 0
  fi
  $SUDO userdel "$SERVICE_USER" && ok "Removed user $SERVICE_USER" || warn "Cannot remove user $SERVICE_USER"
}

remove_files() {
  if $KEEP_FILES; then
    log "Keeping $APP_DIR"
    return 0
  fi
  guard_path "$APP_DIR"

  local d
  while IFS= read -r d; do
    [[ -n "$d" ]] || continue
    $SUDO rm -rf "$d"
    ok "Removed $d"
  done < <(outside_dirs)

  if $KEEP_WORK && [[ -d "$APP_DIR/work" ]]; then
    local backup="${APP_DIR}.work-backup"
    $SUDO rm -rf "$backup"
    $SUDO mv "$APP_DIR/work" "$backup"
    ok "Moved work/ to $backup"
  fi

  cd /
  $SUDO rm -rf "$APP_DIR"
  ok "Removed $APP_DIR"
}

main() {
  parse_args "$@"
  need_root
  require_systemd
  load_env "$ENV_FILE"
  resolve_config

  show_plan
  confirm

  remove_service
  remove_user
  remove_files

  echo
  ok "Done."
  if $KEEP_FILES; then
    echo "  Reinstall: sudo $SCRIPT_DIR/install.sh"
  elif $KEEP_WORK; then
    echo "  work/ kept at ${APP_DIR}.work-backup"
  fi
  return 0
}

# main() is called last so bash parses the whole file first: deleting APP_DIR
# removes this script while it runs.
main "$@"
