-- DROP tables if they exist to reset schema (order is important due to constraints)
DROP TABLE IF EXISTS report_test_case;
DROP TABLE IF EXISTS report;
DROP TABLE IF EXISTS evidence;
DROP TABLE IF EXISTS risk_item;
DROP TABLE IF EXISTS test_case;
DROP TABLE IF EXISTS requirement;
DROP TABLE IF EXISTS analysis_job;
DROP TABLE IF EXISTS uploaded_file;
DROP TABLE IF EXISTS project;

-- 1. Project
CREATE TABLE project (
    project_id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    github_url VARCHAR(255),
    github_branch VARCHAR(100),
    integration_status VARCHAR(50),
    created_at TIMESTAMP NOT NULL
);

-- 2. UploadedFile
CREATE TABLE uploaded_file (
    file_id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    s3_path VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL,
    CONSTRAINT fk_file_project FOREIGN KEY (project_id) REFERENCES project(project_id) ON DELETE CASCADE
);
CREATE INDEX idx_file_project ON uploaded_file(project_id);

-- 3. AnalysisJob
CREATE TABLE analysis_job (
    analysis_id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    qa_perspective VARCHAR(255),
    custom_prompt TEXT,
    summary TEXT,
    status VARCHAR(50) NOT NULL,
    missing_items_text VARCHAR(2000),
    CONSTRAINT fk_job_project FOREIGN KEY (project_id) REFERENCES project(project_id) ON DELETE CASCADE
);
CREATE INDEX idx_job_project ON analysis_job(project_id);

-- 4. Requirement
CREATE TABLE requirement (
    requirement_id VARCHAR(255) PRIMARY KEY,
    analysis_id VARCHAR(255) NOT NULL,
    requirement_text TEXT NOT NULL,
    CONSTRAINT fk_req_job FOREIGN KEY (analysis_id) REFERENCES analysis_job(analysis_id) ON DELETE CASCADE
);
CREATE INDEX idx_req_job ON requirement(analysis_id);

-- 5. TestCase
CREATE TABLE test_case (
    test_case_id VARCHAR(255) PRIMARY KEY,
    analysis_id VARCHAR(255) NOT NULL,
    requirement_id VARCHAR(255) NOT NULL,
    test_case_name VARCHAR(255) NOT NULL,
    test_scenario TEXT NOT NULL,
    precondition TEXT,
    test_steps CLOB, -- CLOB (TEXT/JSON format)
    expected_result TEXT NOT NULL,
    priority VARCHAR(20) NOT NULL,
    confidence_level VARCHAR(20),
    category VARCHAR(100),
    technique VARCHAR(255),
    tdd_hint CLOB,
    negative_scenario CLOB,
    caution VARCHAR(2000),
    CONSTRAINT fk_tc_job FOREIGN KEY (analysis_id) REFERENCES analysis_job(analysis_id) ON DELETE CASCADE,
    CONSTRAINT fk_tc_req FOREIGN KEY (requirement_id) REFERENCES requirement(requirement_id) ON DELETE CASCADE
);
CREATE INDEX idx_tc_job ON test_case(analysis_id);
CREATE INDEX idx_tc_req ON test_case(requirement_id);

-- 6. RiskItem
CREATE TABLE risk_item (
    risk_id VARCHAR(255) PRIMARY KEY,
    test_case_id VARCHAR(255) NOT NULL,
    risk_type VARCHAR(100) NOT NULL,
    CONSTRAINT fk_risk_tc FOREIGN KEY (test_case_id) REFERENCES test_case(test_case_id) ON DELETE CASCADE
);
CREATE INDEX idx_risk_tc ON risk_item(test_case_id);

-- 7. Evidence
CREATE TABLE evidence (
    evidence_id VARCHAR(255) PRIMARY KEY,
    test_case_id VARCHAR(255) NOT NULL,
    chunk_id VARCHAR(255),
    evidence_text TEXT NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    source_section VARCHAR(255),
    score DOUBLE,
    CONSTRAINT fk_evidence_tc FOREIGN KEY (test_case_id) REFERENCES test_case(test_case_id) ON DELETE CASCADE
);
CREATE INDEX idx_evidence_tc ON evidence(test_case_id);

-- 8. Report
CREATE TABLE report (
    report_id VARCHAR(255) PRIMARY KEY,
    analysis_id VARCHAR(255) NOT NULL,
    file_id VARCHAR(255), -- Nullable
    s3_path VARCHAR(500) NOT NULL,
    format VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_job FOREIGN KEY (analysis_id) REFERENCES analysis_job(analysis_id) ON DELETE CASCADE,
    CONSTRAINT fk_report_file FOREIGN KEY (file_id) REFERENCES uploaded_file(file_id) ON DELETE SET NULL
);
CREATE INDEX idx_report_job ON report(analysis_id);
CREATE INDEX idx_report_file ON report(file_id);

-- 9. ReportTestCase (N:M Join Table)
CREATE TABLE report_test_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id VARCHAR(255) NOT NULL,
    test_case_id VARCHAR(255) NOT NULL,
    CONSTRAINT fk_rtc_report FOREIGN KEY (report_id) REFERENCES report(report_id) ON DELETE CASCADE,
    CONSTRAINT fk_rtc_tc FOREIGN KEY (test_case_id) REFERENCES test_case(test_case_id) ON DELETE CASCADE
);
CREATE INDEX idx_rtc_report ON report_test_case(report_id);
CREATE INDEX idx_rtc_tc ON report_test_case(test_case_id);
