-- v2: sistem parametreleri (Sistem parametreleri ekranı). Mevcut veritabanlarına da eklenir:
-- JdbcReviewStore açılışta app_setting tablosu yoksa bu dosyayı çalıştırır.
CREATE TABLE app_setting (
  setting_key VARCHAR(100) PRIMARY KEY,
  json_value CLOB NOT NULL,
  updated_by VARCHAR(200),
  updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE app_setting_history (
  id VARCHAR(36) PRIMARY KEY,
  setting_key VARCHAR(100) NOT NULL,
  summary VARCHAR(4000),
  changed_by VARCHAR(200),
  changed_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_setting_hist_key ON app_setting_history (setting_key)
