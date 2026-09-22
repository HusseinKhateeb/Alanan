CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    preferred_language VARCHAR(2) NOT NULL DEFAULT 'ar' CHECK (preferred_language IN ('ar', 'en')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE permissions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL REFERENCES roles(id),
    permission_id BIGINT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE payment_methods (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (NOT is_default OR active)
);

CREATE UNIQUE INDEX payment_methods_one_active_default
    ON payment_methods (is_default) WHERE is_default AND active;

INSERT INTO roles (name) VALUES ('ADMIN'), ('EMPLOYEE');
INSERT INTO permissions (code, description) VALUES
    ('SALES_CREATE', 'Create sales invoices'),
    ('SALES_VIEW', 'View sales invoices'),
    ('SALES_EDIT', 'Edit completed sales invoices'),
    ('PURCHASES_VIEW', 'View purchases and suppliers'),
    ('PURCHASES_CREATE', 'Create purchase invoices'),
    ('COST_VIEW', 'View cost, WAC and profit'),
    ('EXPENSES_CREATE', 'Create expenses'),
    ('INVENTORY_ADJUST', 'Record waste and stock adjustments'),
    ('AUDIT_VIEW', 'View audit history'),
    ('USERS_MANAGE', 'Manage users and permissions'),
    ('BACKUP_MANAGE', 'Manage backup and restore'),
    ('SETTINGS_MANAGE', 'Manage settings and master data');

INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'ADMIN';
INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
    WHERE r.name = 'EMPLOYEE' AND p.code IN ('SALES_CREATE', 'SALES_VIEW');

INSERT INTO payment_methods (name, is_default) VALUES ('Cash', TRUE), ('Bank', FALSE), ('Other', FALSE);
