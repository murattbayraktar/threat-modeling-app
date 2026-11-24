-- Oracle şeması (Faz 2+ geçişi için taslak). JSON alanları CLOB; zaman damgaları ISO metin (ileride TIMESTAMP).
-- Not: Oracle 23c öncesinde IF NOT EXISTS yoktur; uygulama tablo varlığını kendisi kontrol eder.
CREATE TABLE review (
  id VARCHAR2(36) PRIMARY KEY,
  code VARCHAR2(32) NOT NULL,
  title VARCHAR2(300) NOT NULL,
  business_unit VARCHAR2(200),
  project_type VARCHAR2(100),
  data_classification VARCHAR2(100),
  review_owner VARCHAR2(200),
  analyst VARCHAR2(200),
  llm_mode VARCHAR2(20) NOT NULL,
  status VARCHAR2(40) NOT NULL,
  current_stage VARCHAR2(40) NOT NULL,
  sample_name VARCHAR2(100),
  intake_json CLOB,
  expert_review_completed NUMBER(19) NOT NULL,
  created_by VARCHAR2(200),
  created_at VARCHAR2(40) NOT NULL,
  updated_at VARCHAR2(40) NOT NULL
);
CREATE TABLE review_sequence (
  seq_year NUMBER(19) PRIMARY KEY,
  last_value NUMBER(19) NOT NULL
);
CREATE TABLE review_document (
  id VARCHAR2(36) PRIMARY KEY,
  review_id VARCHAR2(36) NOT NULL,
  document_key VARCHAR2(10) NOT NULL,
  file_name VARCHAR2(300) NOT NULL,
  content_type VARCHAR2(200),
  size_bytes NUMBER(19) NOT NULL,
  sha256 VARCHAR2(64),
  storage_path VARCHAR2(500) NOT NULL,
  client_text CLOB,
  client_method VARCHAR2(100),
  parsed_json CLOB,
  created_at VARCHAR2(40) NOT NULL
);
CREATE INDEX ix_document_review ON review_document (review_id);
CREATE TABLE pipeline_step (
  review_id VARCHAR2(36) NOT NULL,
  stage VARCHAR2(40) NOT NULL,
  stage_order NUMBER(19) NOT NULL,
  status VARCHAR2(20) NOT NULL,
  message VARCHAR2(1000),
  metrics_json CLOB,
  started_at VARCHAR2(40),
  finished_at VARCHAR2(40),
  PRIMARY KEY (review_id, stage)
);
CREATE TABLE model_version (
  id VARCHAR2(36) PRIMARY KEY,
  review_id VARCHAR2(36) NOT NULL,
  version_no NUMBER(19) NOT NULL,
  source_kind VARCHAR2(20) NOT NULL,
  model_json CLOB NOT NULL,
  issues_json CLOB,
  created_by VARCHAR2(200),
  created_at VARCHAR2(40) NOT NULL,
  approved_by VARCHAR2(200),
  approved_at VARCHAR2(40)
);
CREATE INDEX ix_model_review ON model_version (review_id);
CREATE TABLE llm_call (
  id VARCHAR2(36) PRIMARY KEY,
  review_id VARCHAR2(36) NOT NULL,
  stage VARCHAR2(40) NOT NULL,
  call_key VARCHAR2(100) NOT NULL,
  attempt NUMBER(19) NOT NULL,
  executor VARCHAR2(100),
  model_name VARCHAR2(200),
  status VARCHAR2(20) NOT NULL,
  request_json CLOB NOT NULL,
  raw_output CLOB,
  errors_json CLOB,
  duration_ms NUMBER(19),
  created_at VARCHAR2(40) NOT NULL,
  completed_at VARCHAR2(40)
);
CREATE INDEX ix_llm_review ON llm_call (review_id);
CREATE INDEX ix_llm_status ON llm_call (status);
CREATE TABLE review_artifact (
  review_id VARCHAR2(36) NOT NULL,
  kind VARCHAR2(40) NOT NULL,
  json_value CLOB NOT NULL,
  updated_at VARCHAR2(40) NOT NULL,
  PRIMARY KEY (review_id, kind)
);
CREATE TABLE threat (
  id VARCHAR2(36) PRIMARY KEY,
  review_id VARCHAR2(36) NOT NULL,
  code VARCHAR2(20) NOT NULL,
  model_version NUMBER(19) NOT NULL,
  candidate_id VARCHAR2(100),
  rule_id VARCHAR2(50),
  stride VARCHAR2(40) NOT NULL,
  target_id VARCHAR2(20) NOT NULL,
  target_name VARCHAR2(400),
  ai_additional NUMBER(19) NOT NULL,
  decision VARCHAR2(20) NOT NULL,
  ai_json CLOB NOT NULL,
  system_json CLOB,
  expert_json CLOB NOT NULL,
  sources_json CLOB,
  issues_json CLOB,
  created_at VARCHAR2(40) NOT NULL,
  updated_at VARCHAR2(40) NOT NULL
);
CREATE INDEX ix_threat_review ON threat (review_id);
CREATE TABLE threat_event (
  id VARCHAR2(36) PRIMARY KEY,
  threat_id VARCHAR2(36) NOT NULL,
  review_id VARCHAR2(36) NOT NULL,
  actor VARCHAR2(200),
  action VARCHAR2(50) NOT NULL,
  before_json CLOB,
  after_json CLOB,
  note VARCHAR2(2000),
  created_at VARCHAR2(40) NOT NULL
);
CREATE INDEX ix_threat_event_review ON threat_event (review_id);
CREATE TABLE audit_event (
  id VARCHAR2(36) PRIMARY KEY,
  review_id VARCHAR2(36) NOT NULL,
  actor VARCHAR2(200),
  event_type VARCHAR2(50) NOT NULL,
  message VARCHAR2(2000),
  created_at VARCHAR2(40) NOT NULL
);
CREATE INDEX ix_audit_review ON audit_event (review_id);
