#!/usr/bin/env bash
# Install (or reinstall) a Spring Boot application as a systemd service.
#
#     sudo ./install.sh                      install using deploy.env
#     sudo ./install.sh --env-file other.env use another config file
#     sudo ./install.sh --dry-run            print the unit file only
#     sudo ./install.sh --no-start           install and enable, do not start
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

DRY_RUN=false
NO_START=false
ENV_FILE="$SCRIPT_DIR/deploy.env"

usage() { sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --env-file) ENV_FILE="${2:?--env-file needs a path}"; shift 2 ;;
      --dry-run)  DRY_RUN=true; shift ;;
      --no-start) NO_START=true; shift ;;
      -h|--help)  usage ;;
      *) die "Unknown argument: $1 (see ./install.sh --help)" ;;
    esac
  done
}

preflight() {
  resolve_jar
  resolve_java
  resolve_logging_config
  check_exec_paths

  [[ -r "$APP_JAR_PATH" ]] || die "Cannot read $APP_JAR_PATH"

  # Optional by design here: AI-Agent is configured entirely through
  # RUNTIME_ENV_FILE, so an absent application.properties is the normal case.
  [[ -f "$CONFIG_DIR/application.properties" ]] \
    && log "External config: $CONFIG_DIR/application.properties" \
    || log "No application.properties; config comes from the env file and the jar."

  [[ -n "$LOGGING_CONFIG" ]] \
    && log "Logging config: $LOGGING_CONFIG" \
    || warn "No logback/log4j file in $CONFIG_DIR; logs go to journald only."

  # Fail here rather than let systemd report "Failed to load environment files",
  # or worse, let the app start with default credentials.
  if [[ -n "$RUNTIME_ENV_FILE" ]]; then
    [[ -f "$RUNTIME_ENV_FILE" ]] || die \
"RUNTIME_ENV_FILE not found: $RUNTIME_ENV_FILE"$'\n'\
"  cp $CONFIG_DIR/aiagent.env.example $RUNTIME_ENV_FILE && chmod 600 $RUNTIME_ENV_FILE"$'\n'\
"  then fill in POSTGRES_PASSWORD, RAG_ADMIN_API_KEY and OPENAI_API_KEY."
    strip_crlf "$RUNTIME_ENV_FILE"
    # systemd reads KEY=value, not shell: 'export FOO=1' yields a variable
    # literally named 'export FOO'.
    local bad_env
    bad_env=$(grep -nE '^[[:space:]]*export[[:space:]]' "$RUNTIME_ENV_FILE" || true)
    [[ -z "$bad_env" ]] || die "Remove 'export' from $RUNTIME_ENV_FILE:"$'\n'"$bad_env"
    log "Runtime env file: $RUNTIME_ENV_FILE"
  fi

  if [[ "$SERVICE_USER" != "root" ]] && ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    if $DRY_RUN; then
      log "[dry-run] would create system user $SERVICE_USER"
    else
      $SUDO useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER" \
        || die "Cannot create user $SERVICE_USER"
      ok "Created system user $SERVICE_USER."
    fi
  fi

  if [[ "${JMX_ENABLED,,}" == "true" ]]; then
    if [[ -z "${JMX_HOST:-}" ]]; then
      JMX_HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"
      [[ -n "$JMX_HOST" ]] || die "JMX enabled but host IP unknown; set JMX_HOST."
    fi
    warn "JMX is exposed without authentication on port $JMX_PORT."
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":$JMX_PORT "; then
      die "Port $JMX_PORT is already in use; change JMX_PORT."
    fi
  fi

  service_unit_exists && log "$SERVICE_NAME is already installed; reinstalling." || true
}

prepare_dirs() {
  $SUDO mkdir -p "$LOG_DIR" "$DEPLOY_DIR" "$CONFIG_DIR" "$WORK_DIR"
  $SUDO chmod +x "$SCRIPT_DIR"/*.sh 2>/dev/null || true
  [[ -f "$CONFIG_DIR/application.properties" ]] \
    && $SUDO chmod 640 "$CONFIG_DIR/application.properties"
  # 600, not 640: holds the DB password and the LLM provider API key.
  [[ -n "$RUNTIME_ENV_FILE" && -f "$RUNTIME_ENV_FILE" ]] \
    && $SUDO chmod 600 "$RUNTIME_ENV_FILE"
  if [[ "$SERVICE_USER" != "root" ]]; then
    $SUDO chown -R "$SERVICE_USER:$SERVICE_GROUP" "$APP_DIR"
    ok "Owner set to $SERVICE_USER:$SERVICE_GROUP on $APP_DIR."
  fi
}

build_exec_start() {
  local -a cmd=("$JAVA_BIN" "-Xms$XMS" "-Xmx$XMX")

  if [[ "${JMX_ENABLED,,}" == "true" ]]; then
    cmd+=(
      -Dcom.sun.management.jmxremote
      "-Dcom.sun.management.jmxremote.port=$JMX_PORT"
      "-Dcom.sun.management.jmxremote.rmi.port=$JMX_PORT"
      -Dcom.sun.management.jmxremote.local.only=false
      -Dcom.sun.management.jmxremote.authenticate=false
      -Dcom.sun.management.jmxremote.ssl=false
      "-Djava.rmi.server.hostname=$JMX_HOST"
    )
  fi

  # APP_NAME and LOG_DIR let one shared logback.xml name its files per service.
  cmd+=("-DAPP_DIR=$APP_DIR" "-DAPP_NAME=$SERVICE_NAME" "-DLOG_DIR=$LOG_DIR")

  # External config overrides the jar; unset keys keep their packaged defaults.
  cmd+=("-Dspring.config.additional-location=optional:file:$CONFIG_DIR/")
  [[ -n "$LOGGING_CONFIG" ]] && cmd+=("-Dlogging.config=file:$LOGGING_CONFIG")
  [[ -n "${SPRING_PROFILES_ACTIVE:-}" ]] && cmd+=("-Dspring.profiles.active=$SPRING_PROFILES_ACTIVE")

  if [[ -n "$JAVA_OPTS" ]]; then
    # shellcheck disable=SC2206
    local -a extra=($JAVA_OPTS)
    cmd+=("${extra[@]}")
  fi

  cmd+=(-jar "$APP_JAR_PATH")
  # % starts a systemd specifier, so double it to keep the value literal.
  EXEC_START="${cmd[*]//%/%%}"
}

render_unit() {
  cat <<EOF
[Unit]
Description=${SERVICE_DESC//%/%%}
After=network-online.target${AFTER_UNITS:+ $AFTER_UNITS}
Wants=network-online.target${AFTER_UNITS:+ $AFTER_UNITS}
# Misconfiguration kills the app on startup; without a cap systemd would
# restart forever and bury the real cause.
StartLimitBurst=5
StartLimitIntervalSec=300

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_GROUP
WorkingDirectory=$APP_DIR
Environment=APP_DIR=$APP_DIR
Environment=LOG_DIR=$LOG_DIR
Environment=CONFIG_DIR=$CONFIG_DIR
EOF
  # Secrets stay out of the unit file, which is mode 644.
  [[ -n "$RUNTIME_ENV_FILE" ]] && echo "EnvironmentFile=$RUNTIME_ENV_FILE"
  cat <<EOF
ExecStart=$EXEC_START
SuccessExitStatus=143
Restart=always
RestartSec=$RESTART_SEC
TimeoutStopSec=45
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SERVICE_NAME
LimitNOFILE=65535
EOF
  cat <<EOF

[Install]
WantedBy=multi-user.target
EOF
}

write_unit() {
  render_unit | $SUDO tee "$UNIT_FILE" >/dev/null
  $SUDO chmod 644 "$UNIT_FILE"
  $SUDO systemctl daemon-reload
  ok "Wrote $UNIT_FILE"
}

start_service() {
  $SUDO systemctl enable "$SERVICE_NAME.service" >/dev/null

  if $NO_START; then
    warn "--no-start: run 'sudo systemctl start $SERVICE_NAME' when ready."
    return 0
  fi

  $SUDO systemctl start "$SERVICE_NAME.service" \
    || { show_recent_log; die "Cannot start $SERVICE_NAME."; }

  # A bad password or missing key file kills the app a few seconds after start.
  local i=0
  while (( i < 10 )); do
    sleep 1; i=$((i+1))
    service_is_active || { show_recent_log; die "$SERVICE_NAME started then died."; }
  done
  ok "$SERVICE_NAME is running."
}

wait_health() {
  $NO_START && return 0
  [[ -n "$HEALTH_URL" ]] || return 0
  command -v curl >/dev/null 2>&1 || { warn "curl missing; skipping HEALTH_URL."; return 0; }

  log "Probing $HEALTH_URL (up to ${START_TIMEOUT_SEC}s)..."
  local i=0 code
  while (( i < START_TIMEOUT_SEC )); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$HEALTH_URL" || true)"
    case "$code" in
      200)     ok "Health check passed."; return 0 ;;
      401|403) ok "Port is serving (HTTP $code, endpoint requires auth)."; return 0 ;;
    esac
    service_is_active || { show_recent_log; die "$SERVICE_NAME died while probing."; }
    sleep 1; i=$((i+1))
  done
  warn "No healthy response after ${START_TIMEOUT_SEC}s (HTTP ${code:-timeout}); service still running."
  show_recent_log
}

summary() {
  echo
  ok "Installed."
  cat <<EOF

  Service : $SERVICE_NAME
  Dir     : $APP_DIR
  Jar     : $APP_JAR
  Java    : $JAVA_BIN ($JAVA_VERSION_LINE)
  Heap    : -Xms$XMS -Xmx$XMX
  Config  : $CONFIG_DIR
  Secrets : ${RUNTIME_ENV_FILE:-(none)}
  Logs    : $LOG_DIR
  User    : $SERVICE_USER

  systemctl status|restart|stop $SERVICE_NAME
  journalctl -u $SERVICE_NAME -f
  $SCRIPT_DIR/uninstall.sh
EOF
}

main() {
  parse_args "$@"
  $DRY_RUN || need_root
  require_systemd
  load_env "$ENV_FILE"
  resolve_config
  preflight
  build_exec_start

  if $DRY_RUN; then
    echo; log "[dry-run] unit file for $UNIT_FILE:"
    echo "---------------------------------------------"
    render_unit
    echo "---------------------------------------------"
    exit 0
  fi

  prepare_dirs
  stop_service_if_running
  write_unit
  start_service
  wait_health
  summary
}

main "$@"
