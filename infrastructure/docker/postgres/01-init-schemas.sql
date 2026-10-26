-- =============================================================================
-- Vigilia Platform - Database Initialization Script (SQL Reference)
-- Phase 2: Schema & Role Isolation for Microservices
-- Target: PostgreSQL 16
--
-- Execution instructions via psql:
-- Ensure the environment variables AUTH_DB_PASSWORD, CUSTOMER_DB_PASSWORD,
-- TRANSACTION_DB_PASSWORD, and RISK_DB_PASSWORD are set in the environment.
-- psql will read them directly using \getenv (no plaintext secrets in command arguments):
--
--   psql -v ON_ERROR_STOP=1 -U vigilia -d vigilia -f 01-init-schemas.sql
-- =============================================================================

\set ON_ERROR_STOP on

-- 1. Read secrets directly from environment into psql variables
\getenv auth_pwd AUTH_DB_PASSWORD
\getenv customer_pwd CUSTOMER_DB_PASSWORD
\getenv transaction_pwd TRANSACTION_DB_PASSWORD
\getenv risk_pwd RISK_DB_PASSWORD

-- 2. Pass credentials securely into session configuration (project boolean to prevent secret display)
SELECT set_config('vigilia.auth_pwd', :'auth_pwd', false) IS NOT NULL AS auth_configured;
SELECT set_config('vigilia.customer_pwd', :'customer_pwd', false) IS NOT NULL AS customer_configured;
SELECT set_config('vigilia.transaction_pwd', :'transaction_pwd', false) IS NOT NULL AS transaction_configured;
SELECT set_config('vigilia.risk_pwd', :'risk_pwd', false) IS NOT NULL AS risk_configured;

-- 3. Create or Update Dedicated Application Roles & Grant Database CONNECT
-- Attributes: LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION
-- Safe escaping: format('%I', ...) for identifiers, format('%L', ...) for passwords
DO $$
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
            RAISE EXCEPTION 'Password for role % cannot be null or empty', rec.role_name;
        END IF;

        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = rec.role_name) THEN
            EXECUTE format('CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', rec.role_name, v_pwd);
        ELSE
            EXECUTE format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', rec.role_name, v_pwd);
        END IF;
    END LOOP;

    -- Grant CONNECT dynamically on the current database
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO auth_user, customer_user, transaction_user, risk_user', current_database());
END
$$;

-- Reset session configuration parameters to avoid leaking them in session inspection (e.g. SHOW ALL)
RESET vigilia.auth_pwd;
RESET vigilia.customer_pwd;
RESET vigilia.transaction_pwd;
RESET vigilia.risk_pwd;

-- 4. Restrict Schema Public
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM auth_user, customer_user, transaction_user, risk_user;

-- 5. Create/Configure Schemas with Explicit Ownership & Strict Isolation
-- Explicit ALTER SCHEMA ... OWNER TO guarantees proper ownership even if schemas already existed.
-- Existing tables and data are preserved.

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
