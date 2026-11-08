-- ==============================================================================
-- Vigilia Platform - Auth Service Flyway Migration V2
-- Seed Default Demonstration & Staff Users (ADMIN/MONITOR, ANALYST, CUSTOMER)
-- Password for all seed accounts: Vigilia2026!
-- BCrypt cost 12 hash: $2b$12$5OiHqlbnGqapcz6/GJI6pOjrLNXKAAXaPxpwFWE8Cp/ZhYuIhu.s.
-- ==============================================================================

INSERT INTO users (id, email, password_hash, role, is_active, created_at, updated_at)
VALUES 
    -- 1. Platform Monitor & Administrator
    ('a0000000-0000-0000-0000-000000000001', 'monitor@vigilia.ma', '$2b$12$5OiHqlbnGqapcz6/GJI6pOjrLNXKAAXaPxpwFWE8Cp/ZhYuIhu.s.', 'ADMIN', true, NOW(), NOW()),
    
    -- 2. Fraud Risk Analyst
    ('a0000000-0000-0000-0000-000000000002', 'analyst@vigilia.ma', '$2b$12$5OiHqlbnGqapcz6/GJI6pOjrLNXKAAXaPxpwFWE8Cp/ZhYuIhu.s.', 'ANALYST', true, NOW(), NOW()),
    
    -- 3. Customer Account
    ('a0000000-0000-0000-0000-000000000003', 'customer@vigilia.ma', '$2b$12$5OiHqlbnGqapcz6/GJI6pOjrLNXKAAXaPxpwFWE8Cp/ZhYuIhu.s.', 'CUSTOMER', true, NOW(), NOW())
ON CONFLICT (email) DO NOTHING;

