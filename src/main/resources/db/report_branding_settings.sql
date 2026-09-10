-- Single-row branding settings applied to printable reports.
-- Run once on PostgreSQL before deploy with ddl-auto=validate.

CREATE TABLE IF NOT EXISTS report_branding_settings (
    id               BIGSERIAL PRIMARY KEY,
    company_name     VARCHAR(150),
    logo_data_uri    TEXT,
    header_text      VARCHAR(300),
    show_cover_page  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMP
);
