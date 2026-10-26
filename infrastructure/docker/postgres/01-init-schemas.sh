#!/bin/sh
set -eu

# ==============================================================================
# Vigilia Platform - Database Initialization Script
# Phase 2: Schema & Role Isolation for Microservices
# Compatible with PostgreSQL 16 (postgres:16-alpine)
# ==============================================================================

# Ensure required environment variables are set and non-empty.
# NO fallback passwords: fail immediately with an explicit error message.
MISSING_VARS=""
[ -z "${AUTH_DB_PASSWORD:-}" ] && MISSING_VARS="${MISSING_VARS} AUTH_DB_PASSWORD"
[ -z "${CUSTOMER_DB_PASSWORD:-}" ] && MISSING_VARS="${MISSING_VARS} CUSTOMER_DB_PASSWORD"
[ -z "${TRANSACTION_DB_PASSWORD:-}" ] && MISSING_VARS="${MISSING_VARS} TRANSACTION_DB_PASSWORD"
[ -z "${RISK_DB_PASSWORD:-}" ] && MISSING_VARS="${MISSING_VARS} RISK_DB_PASSWORD"

if [ -n "${MISSING_VARS}" ]; then
    echo "[-] ERROR: Missing or empty mandatory environment variables:${MISSING_VARS}" >&2
    echo "[-] Please provide them in the execution environment without using hardcoded defaults." >&2
    exit 1
fi

DB_NAME="${POSTGRES_DB:-vigilia}"
DB_USER="${POSTGRES_USER:-vigilia}"

echo ">>> [vigilia-postgres] Initializing schemas and isolated roles on database: ${DB_NAME}..."

# Security notice:
# Passwords are read directly from the environment using psql's \getenv command (PG 14+),
# preventing secrets from being exposed in command-line arguments (ps aux / /proc/PID/cmdline).
psql -v ON_ERROR_STOP=1 \
     --username "${DB_USER}" \
     --dbname "${DB_NAME}" \
     <<-EOSQL

    -- -------------------------------------------------------------------------
    -- 1. Read secrets from environment variables into psql variables (\getenv)
    -- -------------------------------------------------------------------------
    \getenv auth_pwd AUTH_DB_PASSWORD
    \getenv customer_pwd CUSTOMER_DB_PASSWORD
    \getenv transaction_pwd TRANSACTION_DB_PASSWORD
    \getenv risk_pwd RISK_DB_PASSWORD

    -- -------------------------------------------------------------------------
    -- 2. Pass credentials into session configuration securely (boolean projection prevents secret display)
    -- -------------------------------------------------------------------------
    SELECT set_config('vigilia.auth_pwd', :'auth_pwd', false) IS NOT NULL AS auth_configured;
    SELECT set_config('vigilia.customer_pwd', :'customer_pwd', false) IS NOT NULL AS customer_configured;
    SELECT set_config('vigilia.transaction_pwd', :'transaction_pwd', false) IS NOT NULL AS transaction_configured;
    SELECT set_config('vigilia.risk_pwd', :'risk_pwd', false) IS NOT NULL AS risk_configured;

    -- -------------------------------------------------------------------------
    -- 3. Create or Update Dedicated Application Roles & Grant Database CONNECT
    -- Attributes: LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION
    -- Escaping: format('%I', ...) for identifier, format('%L', ...) for password
    -- -------------------------------------------------------------------------
    DO \$\$
    DECLARE
        rec RECORD;
        v_pwd text;
    BEGIN
        FOR rec IN SELECT * FROM (VALUES
            ('auth_user', 'vigilia.auth_pwd'),
            ('customer_user', 'vigilia.customer_pwd'),
            ('transaction_user', 'vigilia.transaction_pwd'),
            ('risk_user', 'vigilia.risk_pwd')
        ) AS t(role_name, guc_name)
        LOOP
            v_pwd := current_setting(rec.guc_name);
            IF v_pwd IS NULL OR length(trim(v_pwd)) = 0 THEN
                RAISE EXCEPTION 'Password for role % cannot be empty', rec.role_name;
            END IF;

            IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = rec.role_name) THEN
                EXECUTE format('CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', rec.role_name, v_pwd);
            ELSE
                EXECUTE format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', rec.role_name, v_pwd);
            END IF;
        END LOOP;

        -- Grant CONNECT dynamically on current database
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO auth_user, customer_user, transaction_user, risk_user', current_database());
    END
    \$\$;

    -- Reset custom GUC parameters to remove them from session visibility (e.g. SHOW ALL)
    RESET vigilia.auth_pwd;
    RESET vigilia.customer_pwd;
    RESET vigilia.transaction_pwd;
    RESET vigilia.risk_pwd;

    -- -------------------------------------------------------------------------
    -- 4. Restrict Public Schema
    -- -------------------------------------------------------------------------
    REVOKE ALL ON SCHEMA public FROM PUBLIC;
    REVOKE ALL ON SCHEMA public FROM auth_user, customer_user, transaction_user, risk_user;

    -- -------------------------------------------------------------------------
    -- 5. Create/Configure Schemas with Explicit Ownership & Strict Isolation
    -- Explicit ALTER SCHEMA ... OWNER TO ensures correct ownership even if schema already exists.
    -- Existing tables and data are fully preserved.
    -- -------------------------------------------------------------------------

    -- === Schema: auth ===
    CREATE SCHEMA IF NOT EXISTS auth;
    ALTER SCHEMA auth OWNER TO auth_user;
    REVOKE ALL ON SCHEMA auth FROM PUBLIC, customer_user, transaction_user, risk_user;
    GRANT USAGE, CREATE ON SCHEMA auth TO auth_user;
    ALTER ROLE auth_user SET search_path TO auth;

    -- Minimal default privileges for migrations executed by auth_user
    ALTER DEFAULT PRIVILEGES FOR ROLE auth_user IN SCHEMA auth GRANT ALL ON TABLES TO auth_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE auth_user IN SCHEMA auth GRANT ALL ON SEQUENCES TO auth_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE auth_user IN SCHEMA auth REVOKE ALL ON TABLES FROM PUBLIC, customer_user, transaction_user, risk_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE auth_user IN SCHEMA auth REVOKE ALL ON SEQUENCES FROM PUBLIC, customer_user, transaction_user, risk_user;

    -- === Schema: customer ===
    CREATE SCHEMA IF NOT EXISTS customer;
    ALTER SCHEMA customer OWNER TO customer_user;
    REVOKE ALL ON SCHEMA customer FROM PUBLIC, auth_user, transaction_user, risk_user;
    GRANT USAGE, CREATE ON SCHEMA customer TO customer_user;
    ALTER ROLE customer_user SET search_path TO customer;

    -- Minimal default privileges for migrations executed by customer_user
    ALTER DEFAULT PRIVILEGES FOR ROLE customer_user IN SCHEMA customer GRANT ALL ON TABLES TO customer_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE customer_user IN SCHEMA customer GRANT ALL ON SEQUENCES TO customer_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE customer_user IN SCHEMA customer REVOKE ALL ON TABLES FROM PUBLIC, auth_user, transaction_user, risk_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE customer_user IN SCHEMA customer REVOKE ALL ON SEQUENCES FROM PUBLIC, auth_user, transaction_user, risk_user;

    -- === Schema: transaction ===
    CREATE SCHEMA IF NOT EXISTS transaction;
    ALTER SCHEMA transaction OWNER TO transaction_user;
    REVOKE ALL ON SCHEMA transaction FROM PUBLIC, auth_user, customer_user, risk_user;
    GRANT USAGE, CREATE ON SCHEMA transaction TO transaction_user;
    ALTER ROLE transaction_user SET search_path TO transaction;

    -- Minimal default privileges for migrations executed by transaction_user
    ALTER DEFAULT PRIVILEGES FOR ROLE transaction_user IN SCHEMA transaction GRANT ALL ON TABLES TO transaction_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE transaction_user IN SCHEMA transaction GRANT ALL ON SEQUENCES TO transaction_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE transaction_user IN SCHEMA transaction REVOKE ALL ON TABLES FROM PUBLIC, auth_user, customer_user, risk_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE transaction_user IN SCHEMA transaction REVOKE ALL ON SEQUENCES FROM PUBLIC, auth_user, customer_user, risk_user;

    -- === Schema: risk ===
    CREATE SCHEMA IF NOT EXISTS risk;
    ALTER SCHEMA risk OWNER TO risk_user;
    REVOKE ALL ON SCHEMA risk FROM PUBLIC, auth_user, customer_user, transaction_user;
    GRANT USAGE, CREATE ON SCHEMA risk TO risk_user;
    ALTER ROLE risk_user SET search_path TO risk;

    -- Minimal default privileges for migrations executed by risk_user
    ALTER DEFAULT PRIVILEGES FOR ROLE risk_user IN SCHEMA risk GRANT ALL ON TABLES TO risk_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE risk_user IN SCHEMA risk GRANT ALL ON SEQUENCES TO risk_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE risk_user IN SCHEMA risk REVOKE ALL ON TABLES FROM PUBLIC, auth_user, customer_user, transaction_user;
    ALTER DEFAULT PRIVILEGES FOR ROLE risk_user IN SCHEMA risk REVOKE ALL ON SEQUENCES FROM PUBLIC, auth_user, customer_user, transaction_user;

EOSQL

echo ">>> [vigilia-postgres] Schemas and roles initialized with strict isolation successfully."
