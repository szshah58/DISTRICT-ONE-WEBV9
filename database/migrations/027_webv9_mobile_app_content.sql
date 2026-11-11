USE district_one;

CREATE TABLE IF NOT EXISTS webv9_mobile_content (
    id BIGINT NOT NULL AUTO_INCREMENT,
    section VARCHAR(32) NOT NULL,
    content_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body LONGTEXT NULL,
    location VARCHAR(255) NULL,
    status VARCHAR(80) NULL,
    content_date DATE NULL,
    image_url VARCHAR(1000) NULL,
    phone VARCHAR(80) NULL,
    email VARCHAR(255) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    category VARCHAR(100) NULL,
    summary TEXT NULL,
    full_article LONGTEXT NULL,
    venue VARCHAR(255) NULL,
    event_time VARCHAR(64) NULL,
    registration_info VARCHAR(1000) NULL,
    document_category VARCHAR(100) NULL,
    file_asset_id VARCHAR(36) NULL,
    publish_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    archived_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_webv9_mobile_content_section_key (section, content_key),
    KEY ix_webv9_mobile_content_public (section, publish_state, is_active, sort_order)
) ENGINE=InnoDB;

SET @webv9_mobile_had_publish_state = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'webv9_mobile_content'
      AND column_name = 'publish_state'
);

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'category') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN category VARCHAR(100) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'summary') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN summary TEXT NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'full_article') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN full_article LONGTEXT NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'venue') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN venue VARCHAR(255) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'event_time') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN event_time VARCHAR(64) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'registration_info') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN registration_info VARCHAR(1000) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'document_category') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN document_category VARCHAR(100) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'file_asset_id') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN file_asset_id VARCHAR(36) NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    @webv9_mobile_had_publish_state = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN publish_state VARCHAR(20) NOT NULL DEFAULT ''DRAFT''',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content' AND column_name = 'archived_at') = 0,
    'ALTER TABLE webv9_mobile_content ADD COLUMN archived_at DATETIME NULL',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

SET @webv9_mobile_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'webv9_mobile_content'
       AND index_name = 'ix_webv9_mobile_content_public') = 0,
    'CREATE INDEX ix_webv9_mobile_content_public ON webv9_mobile_content (section,publish_state,is_active,sort_order)',
    'SELECT 1');
PREPARE webv9_mobile_stmt FROM @webv9_mobile_ddl;
EXECUTE webv9_mobile_stmt;
DEALLOCATE PREPARE webv9_mobile_stmt;

UPDATE webv9_mobile_content
SET publish_state = CASE WHEN is_active = 1 THEN 'PUBLISHED' ELSE 'DRAFT' END
WHERE @webv9_mobile_had_publish_state = 0;

CREATE TABLE IF NOT EXISTS webv9_mobile_content_assets (
    id VARCHAR(36) NOT NULL,
    storage_name VARCHAR(80) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_size BIGINT NOT NULL,
    uploaded_by VARCHAR(120) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_webv9_mobile_content_assets_storage (storage_name)
) ENGINE=InnoDB;

INSERT IGNORE INTO webv9_migrations(version, description)
VALUES ('027', 'WEBV9 mobile app content publishing and secure assets');
