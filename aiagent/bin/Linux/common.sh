#!/usr/bin/env bash
# Shared by install.sh and uninstall.sh. Not executable on its own.

if [[ -t 1 ]]; then
  _C_RED=$'\033[0;31m'; _C_YEL=$'\033[0;33m'; _C_GRN=$'\033[0;32m'
  _C_CYA=$'\033[0;36m'; _C_OFF=$'\033[0m'
else
  _C_RED=''; _C_YEL=''; _C_GRN=''; _C_CYA=''; _C_OFF=''
fi

log()  { echo "${_C_CYA}[ INFO  ]${_C_OFF} $*"; }
ok()   { echo "${_C_GRN}[  OK   ]${_C_OFF} $*"; }
warn() { echo "${_C_YEL}[ WARN  ]${_C_OFF} $*" >&2; }
err()  { echo "${_C_RED}[ ERROR ]${_C_OFF} $*" >&2; }
die()  { err "$*"; exit 1; }

SUDO=""
need_root() {
  if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || die "Root required. Run: su - -c '$0'"
    SUDO="sudo"
    $SUDO -v >/dev/null 2>&1 || die "Cannot acquire sudo."
  fi
}

require_systemd() {
  command -v systemctl >/dev/null 2>&1 || die "systemctl not found; systemd only."
}

# CRLF from Windows would append \r to every value and break paths.
strip_crlf() {
  local f="$1"
  if LC_ALL=C grep -qU $'\r' "$f" 2>/dev/null; then
    warn "$f has CRLF line endings; converting to LF."
    ${SUDO} sed -i 's/\r$//' "$f" || die "Cannot fix CRLF in $f"
  fi
}

load_env() {
  local f="$1"
  [[ -f "$f" ]] || die "Config file not found: $f"
  strip_crlf "$f"

  local bad
  bad=$(grep -nE '^[[:space:]]*export[[:space:]]' "$f" || true)
  [[ -z "$bad" ]] || die "Remove 'export' from $f:"$'\n'"$bad"

  # Unquoted whitespace makes 'source' run the tail as a command.
  bad=$(grep -nE "^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=[^\"'#]*[[:space:]]+[^[:space:]]" "$f" || true)
  [[ -z "$bad" ]] || die "Single-quote values containing spaces in $f:"$'\n'"$bad"

  set -a
  # shellcheck disable=SC1090
  source "$f"
  set +a
  ENV_FILE_ABS="$(cd "$(dirname "$f")" && pwd)/$(basename "$f")"
  log "Config: $ENV_FILE_ABS"
}

resolve_config() {
  if [[ -z "${APP_DIR:-}" ]]; then
    APP_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
  else
    APP_DIR="${APP_DIR%/}"
  fi
  [[ -d "$APP_DIR" ]] || die "APP_DIR does not exist: $APP_DIR"
  # systemd splits ExecStart on whitespace, so no path in it may contain any.
  [[ "$APP_DIR" != *[[:space:]]* ]] || die "APP_DIR must not contain spaces: $APP_DIR"

  SERVICE_NAME="${SERVICE_NAME:-$(basename "$APP_DIR")}"
  [[ "$SERVICE_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || die "Invalid SERVICE_NAME: '$SERVICE_NAME'"
  SERVICE_DESC="${SERVICE_DESC:-$SERVICE_NAME}"

  DEPLOY_DIR="${DEPLOY_DIR:-$APP_DIR/deploy}"
  CONFIG_DIR="${CONFIG_DIR:-$APP_DIR/config}"
  LOG_DIR="${LOG_DIR:-$APP_DIR/logs}"
  WORK_DIR="${WORK_DIR:-$APP_DIR/work}"
  local var path
  for var in DEPLOY_DIR CONFIG_DIR LOG_DIR WORK_DIR; do
    path="${!var}"
    path="${path%/}"
    [[ "$path" = /* ]] || path="$APP_DIR/$path"       # relative to APP_DIR, not to cwd
    [[ "$path" != *[[:space:]]* ]] || die "$var must not contain spaces: $path"
    printf -v "$var" '%s' "$path"
  done

  UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
  DROPIN_DIR="/etc/systemd/system/${SERVICE_NAME}.service.d"

  SERVICE_USER="${SERVICE_USER:-root}"
  SERVICE_GROUP="${SERVICE_GROUP:-$SERVICE_USER}"

  XMS="${XMS:-512m}"
  XMX="${XMX:-2g}"
  JAVA_OPTS="${JAVA_OPTS:-}"
  RESTART_SEC="${RESTART_SEC:-20}"
  START_TIMEOUT_SEC="${START_TIMEOUT_SEC:-60}"
  JMX_ENABLED="${JMX_ENABLED:-false}"
  JMX_PORT="${JMX_PORT:-1093}"
  HEALTH_URL="${HEALTH_URL:-}"
  UNINSTALL_REMOVE_USER="${UNINSTALL_REMOVE_USER:-false}"

  # Secrets loaded at runtime via EnvironmentFile. Empty = not used.
  RUNTIME_ENV_FILE="${RUNTIME_ENV_FILE:-}"
  if [[ -n "$RUNTIME_ENV_FILE" ]]; then
    [[ "$RUNTIME_ENV_FILE" = /* ]] || RUNTIME_ENV_FILE="$APP_DIR/$RUNTIME_ENV_FILE"
    [[ "$RUNTIME_ENV_FILE" != *[[:space:]]* ]] \
      || die "RUNTIME_ENV_FILE must not contain spaces: $RUNTIME_ENV_FILE"
  fi

  # Extra units to order after, space separated. Written to both After= and
  # Wants=: After alone only orders, it does not pull the unit in.
  AFTER_UNITS="${AFTER_UNITS:-}"

}

resolve_jar() {
  if [[ -n "${APP_JAR:-}" ]]; then
    APP_JAR_PATH="$DEPLOY_DIR/${APP_JAR##*/}"
    [[ -f "$APP_JAR_PATH" ]] || die "Jar not found: $APP_JAR_PATH"
    return
  fi

  local jars=()
  while IFS= read -r line; do jars+=("$line"); done < <(
    find "$DEPLOY_DIR" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | sort
  )
  case ${#jars[@]} in
    0) die "No *.jar in $DEPLOY_DIR" ;;
    1) APP_JAR_PATH="${jars[0]}" ;;
    *) die "${#jars[@]} jars in $DEPLOY_DIR; set APP_JAR in deploy.env:"$'\n'"$(printf '  - %s\n' "${jars[@]##*/}")" ;;
  esac
  APP_JAR="$(basename "$APP_JAR_PATH")"
  log "Jar: $APP_JAR"
}

check_exec_paths() {
  local p
  for p in "$APP_JAR_PATH" "$JAVA_BIN" "$LOGGING_CONFIG"; do
    [[ -z "$p" || "$p" != *[[:space:]]* ]] || die "Path must not contain spaces: $p"
  done
}

resolve_java() {
  if [[ -n "${JDK_DIR:-}" ]]; then
    JAVA_BIN="${JDK_DIR%/}/bin/java"
    [[ -x "$JAVA_BIN" ]] || die "JDK_DIR=$JDK_DIR but $JAVA_BIN is missing"
  else
    JAVA_BIN=""
    local d
    for d in "$APP_DIR"/lib/jdk*; do
      if [[ -x "$d/bin/java" ]]; then JAVA_BIN="$d/bin/java"; JDK_DIR="$d"; break; fi
    done
    if [[ -z "$JAVA_BIN" ]]; then
      JAVA_BIN="$(command -v java || true)"
      [[ -n "$JAVA_BIN" ]] || die "No Java found. Put a JDK in $APP_DIR/lib/ or set JDK_DIR."
      JDK_DIR="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
    fi
  fi

  JAVA_VERSION_LINE="$("$JAVA_BIN" -version 2>&1 | head -1)"
  [[ -n "${REQUIRED_JAVA_MAJOR:-}" ]] || return 0

  # Wrong major version means a crash loop on UnsupportedClassVersionError.
  local major
  major="$(sed -nE 's/.*version "?([0-9]+).*/\1/p' <<<"$JAVA_VERSION_LINE")"
  [[ -n "$major" ]] || die "Cannot parse Java version from: $JAVA_VERSION_LINE"
  # 1.8.0_x style: the major version is the second component.
  if [[ "$major" == "1" ]]; then
    major="$(sed -nE 's/.*version "?1\.([0-9]+).*/\1/p' <<<"$JAVA_VERSION_LINE")"
    [[ -n "$major" ]] || die "Cannot parse Java version from: $JAVA_VERSION_LINE"
  fi
  (( major >= REQUIRED_JAVA_MAJOR )) || die \
"Java $major ($JAVA_BIN) is below the required $REQUIRED_JAVA_MAJOR."$'\n'\
"  Unpack JDK $REQUIRED_JAVA_MAJOR into $APP_DIR/lib/ or set JDK_DIR in deploy.env."
}

resolve_logging_config() {
  if [[ -n "${LOGGING_CONFIG:-}" ]]; then
    [[ "$LOGGING_CONFIG" = /* ]] || LOGGING_CONFIG="$APP_DIR/$LOGGING_CONFIG"
    [[ -f "$LOGGING_CONFIG" ]] || die "Logging config not found: $LOGGING_CONFIG"
    return
  fi
  local f
  for f in logback-spring.xml logback.xml log4j2.xml log4j2-spring.xml log4j.xml; do
    if [[ -f "$CONFIG_DIR/$f" ]]; then LOGGING_CONFIG="$CONFIG_DIR/$f"; return; fi
  done
  LOGGING_CONFIG=""
}

service_unit_exists() { [[ -f "$UNIT_FILE" ]]; }
service_is_active()   { $SUDO systemctl is-active --quiet "$SERVICE_NAME.service"; }
service_is_enabled()  { $SUDO systemctl is-enabled --quiet "$SERVICE_NAME.service" 2>/dev/null; }
service_known()       { $SUDO systemctl list-unit-files "$SERVICE_NAME.service" --no-legend 2>/dev/null | grep -q .; }

stop_service_if_running() {
  if service_is_active; then
    $SUDO systemctl stop "$SERVICE_NAME.service" || die "Cannot stop $SERVICE_NAME."
    ok "Stopped $SERVICE_NAME."
  else
    log "$SERVICE_NAME is not running."
  fi
}

show_recent_log() {
  echo "----- last 40 log lines ($SERVICE_NAME) -----"
  $SUDO journalctl -u "$SERVICE_NAME.service" -n 40 --no-pager 2>/dev/null || true
  echo "---------------------------------------------"
}
