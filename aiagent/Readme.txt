=====================================================================
AI-AGENT — SERVICE INSTALLER (Linux / systemd)
=====================================================================
Installs AI-Agent (RAG Document System) as a systemd service. Derived from
the shared BSC installer (vsd-gateway edition), adapted for this application:
EnvironmentFile support for secrets, sandboxing directives, a restart cap,
and unit ordering after the database.

Full deployment plan for UAT 10.21.170.55: docs/DEPLOY-UAT.md.

LAYOUT
    /app/aiagent/
    |-- deploy/AIAgent-0.2.0.jar
    |-- config/
    |   |-- aiagent.env              VALUES + secrets, mode 600, not in git
    |   |-- aiagent.env.example      the only file to edit
    |   |-- application.properties   key -> variable map, holds no values
    |   `-- logback.xml              log plumbing
    |-- lib/jdk-*/                   only if java is not on PATH
    |-- logs/                        application log, cron backup log
    |-- work/                        runtime data: tai-lieu/, backup/
    `-- bin/Linux/{deploy.env,install.sh,uninstall.sh,common.sh}

PREREQUISITES
    1. JDK 21:  dnf install -y java-21-openjdk-headless
       or unpack a JDK 21 into /app/aiagent/lib/
    2. Postgres 17 + pgvector on 127.0.0.1:5432, database rag_db, user rag.
       Flyway creates the tables on first start.
    3. Build the jar on a dev machine (the server has no Maven):
           ./mvnw clean package
    Nothing else: install.sh creates logs/, work/ and the aiagent account.

INSTALL
    1. Copy this directory to the server as /app/aiagent
    2. Put the jar in /app/aiagent/deploy/
    3. cd /app/aiagent/config
       cp aiagent.env.example aiagent.env && chmod 600 aiagent.env
       Fill in POSTGRES_PASSWORD, RAG_ADMIN_API_KEY, RAG_USER_API_KEY,
       OPENAI_API_KEY. Everything else is already set for this server.
    4. Review bin/Linux/deploy.env (install settings: paths, heap, user)
    5. cd /app/aiagent/bin/Linux
       sed -i 's/\r$//' *.sh deploy.env      # if copied from Windows
       chmod +x *.sh
       ./install.sh --dry-run                # print the unit file only
       sudo ./install.sh

    Decide RAG_EMBEDDING_PROVIDER and RAG_EMBEDDING_DIM BEFORE the first start.
    The vector width goes into the Flyway DDL; changing it later means recreating
    the schema and re-ingesting everything. SchemaValidator refuses to start on a
    mismatch, so a typo surfaces immediately.
    Currently: OPENAI / text-embedding-3-small / 1536.

    Startup must log "Schema OK: vector(1536) khop cau hinh". A dimension
    mismatch means stop - do not ingest.

OPERATE
    sudo systemctl status|restart|stop aiagent
    sudo journalctl -u aiagent -f
    tail -f /app/aiagent/logs/aiagent.log

    New jar             : replace it, update APP_JAR if renamed, rerun install.sh
    config/aiagent.env  : systemctl restart aiagent
    bin/Linux/deploy.env: rerun install.sh (baked into the unit file)

    rag.retrieval.* can be changed at runtime: POST /api/v1/rag/settings

UNINSTALL
    sudo ./uninstall.sh                 remove service + application directory
    sudo ./uninstall.sh --keep-work     keep work/ as <APP_DIR>.work-backup
    sudo ./uninstall.sh --keep-files    remove the service only
    add -y to skip the confirmation

    Does not touch the database. Back it up with deploy/backup.sh first.

NOTES
    1. Files copied from Windows have CRLF endings and will not execute.
       install.sh fixes deploy.env and aiagent.env, but not itself.
    2. aiagent.env uses systemd syntax, not shell: KEY=value only, no export,
       no $(cmd), no ${VAR}, no spaces around '='.
    3. config/aiagent.env is read by both aiagent.service and
       rag-postgres.service, so POSTGRES_PASSWORD is declared once. A side
       effect: the database container also sees OPENAI_API_KEY. Point the
       quadlet at a separate file if that matters.
    5. config/application.properties forwards environment variables and holds
       no values. Replacing a ${VAR} with a literal kills the placeholder and
       the matching entry in aiagent.env silently stops working.
    4. One server.port and JMX_PORT per service. AI-Agent: 8080 / 1094.
