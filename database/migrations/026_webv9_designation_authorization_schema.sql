USE district_one;

CREATE TABLE IF NOT EXISTS webv9_positions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    position_code VARCHAR(50) NOT NULL,
    position_name VARCHAR(100) NOT NULL,
    department VARCHAR(100) NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_webv9_positions_code (position_code),
    KEY ix_webv9_positions_department_active (department, is_active)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS webv9_position_permissions (
    position_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    granted_by BIGINT NULL,
    PRIMARY KEY (position_id, permission_id),
    KEY ix_webv9_position_permissions_permission (permission_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS webv9_user_project_scopes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_id BIGINT UNSIGNED NOT NULL,
    location VARCHAR(255) NULL,
    city VARCHAR(120) NULL,
    assigned_by BIGINT NULL,
    PRIMARY KEY (id),
    KEY ix_webv9_user_project_scopes_user_project (user_id, project_id),
    KEY ix_webv9_user_project_scopes_project (project_id)
) ENGINE=InnoDB;

SET @webv9_has_position_id = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'position_id'
);
SET @webv9_ddl = IF(@webv9_has_position_id = 0,
    'ALTER TABLE users ADD COLUMN position_id BIGINT NULL',
    'SELECT 1');
PREPARE webv9_stmt FROM @webv9_ddl;
EXECUTE webv9_stmt;
DEALLOCATE PREPARE webv9_stmt;

SET @webv9_has_access_starts_at = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'access_starts_at'
);
SET @webv9_ddl = IF(@webv9_has_access_starts_at = 0,
    'ALTER TABLE users ADD COLUMN access_starts_at DATETIME NULL',
    'SELECT 1');
PREPARE webv9_stmt FROM @webv9_ddl;
EXECUTE webv9_stmt;
DEALLOCATE PREPARE webv9_stmt;

SET @webv9_has_access_ends_at = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'access_ends_at'
);
SET @webv9_ddl = IF(@webv9_has_access_ends_at = 0,
    'ALTER TABLE users ADD COLUMN access_ends_at DATETIME NULL',
    'SELECT 1');
PREPARE webv9_stmt FROM @webv9_ddl;
EXECUTE webv9_stmt;
DEALLOCATE PREPARE webv9_stmt;

SET @webv9_has_position_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'ix_users_position_id'
);
SET @webv9_ddl = IF(@webv9_has_position_index = 0,
    'CREATE INDEX ix_users_position_id ON users (position_id)',
    'SELECT 1');
PREPARE webv9_stmt FROM @webv9_ddl;
EXECUTE webv9_stmt;
DEALLOCATE PREPARE webv9_stmt;

INSERT IGNORE INTO webv9_migrations(version, description)
VALUES ('026', 'WEBV9 designation permissions, access dates, and project scopes');
