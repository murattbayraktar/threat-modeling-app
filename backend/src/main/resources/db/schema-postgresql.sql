-- PostgreSQL şeması (pgvector ile Faz 2 için). JSON alanları TEXT (ileride JSONB).
CREATE TABLE review (
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(32) NOT NULL,
  title VARCHAR(300) NOT NULL,
  business_unit VARCHAR(200),
  project_type VARCHAR(100),
  data_classification VARCHAR(100),
  review_owner VARCHAR(200),
  analyst VARCHAR(200),
  llm_mode VARCHAR(20) NOT NULL,
  status VARCHAR(40) NOT NULL,
  current_stage VARCHAR(40) NOT NULL,
  sample_name VARCHAR(100),
  intake_json TEXT,
  expert_review_completed INTEGER NOT NULL,
  created_by VARCHAR(200),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE review_sequence (
  seq_year INTEGER PRIMARY KEY,
  last_value INTEGER NOT NULL
);
CREATE TABLE review_document (
  id VARCHAR(36) PRIMARY KEY,
  review_id VARCHAR(36) NOT NULL,
  document_key VARCHAR(10) NOT NULL,
  file_name VARCHAR(300) NOT NULL,
  content_type VARCHAR(200),
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(64),
  storage_path VARCHAR(500) NOT NULL,
  client_text TEXT,
  client_method VARCHAR(100),
  parsed_json TEXT,
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_document_review ON review_document (review_id);
CREATE TABLE pipeline_step (
  review_id VARCHAR(36) NOT NULL,
  stage VARCHAR(40) NOT NULL,
  stage_order INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  message VARCHAR(1000),
  metrics_json TEXT,
  started_at VARCHAR(40),
  finished_at VARCHAR(40),
  PRIMARY KEY (review_id, stage)
);
CREATE TABLE model_version (
  id VARCHAR(36) PRIMARY KEY,
  review_id VARCHAR(36) NOT NULL,
  version_no INTEGER NOT NULL,
  source_kind VARCHAR(20) NOT NULL,
  model_json TEXT NOT NULL,
  issues_json TEXT,
  created_by VARCHAR(200),
  created_at VARCHAR(40) NOT NULL,
  approved_by VARCHAR(200),
  approved_at VARCHAR(40)
);
CREATE INDEX ix_model_review ON model_version (review_id);
CREATE TABLE llm_call (
  id VARCHAR(36) PRIMARY KEY,
  review_id VARCHAR(36) NOT NULL,
  stage VARCHAR(40) NOT NULL,
  call_key VARCHAR(100) NOT NULL,
  attempt INTEGER NOT NULL,
  executor VARCHAR(100),
  model_name VARCHAR(200),
  status VARCHAR(20) NOT NULL,
  request_json TEXT NOT NULL,
  raw_output TEXT,
  errors_json TEXT,
  duration_ms BIGINT,
  created_at VARCHAR(40) NOT NULL,
  completed_at VARCHAR(40)
);
CREATE INDEX ix_llm_review ON llm_call (review_id);
CREATE INDEX ix_llm_status ON llm_call (status);
CREATE TABLE review_artifact (
  review_id VARCHAR(36) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  json_value TEXT NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (review_id, kind)
);
CREATE TABLE threat (
  id VARCHAR(36) PRIMARY KEY,
  review_id VARCHAR(36) NOT NULL,
  code VARCHAR(20) NOT NULL,
  model_version INTEGER NOT NULL,
  candidate_id VARCHAR(100),
  rule_id VARCHAR(50),
  stride VARCHAR(40) NOT NULL,
  target_id VARCHAR(20) NOT NULL,
  target_name VARCHAR(400),
  ai_additional INTEGER NOT NULL,
  decision VARCHAR(20) NOT NULL,
  ai_json TEXT NOT NULL,
  system_json TEXT,
  expert_json TEXT NOT NULL,
  sources_json TEXT,
  issues_json TEXT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_threat_review ON threat (review_id);
CREATE TABLE threat_event (
  id VARCHAR(36) PRIMARY KEY,
  threat_id VARCHAR(36) NOT NULL,
  review_id VARCHAR(36) NOT NULL,
  actor VARCHAR(200),
  action VARCHAR(50) NOT NULL,
  before_json TEXT,
  after_json TEXT,
  note VARCHAR(2000),
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_threat_event_review ON threat_event (review_id);
CREATE TABLE audit_event (
  id VARCHAR(36) PRIMARY KEY,
  review_id VARCHAR(36) NOT NULL,
  actor VARCHAR(200),
  event_type VARCHAR(50) NOT NULL,
  message VARCHAR(2000),
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_audit_review ON audit_event (review_id);
