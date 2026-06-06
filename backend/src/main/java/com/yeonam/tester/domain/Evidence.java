package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    @Column(name = "evidence_id")
    private String evidenceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @Column(name = "chunk_id")
    private String chunkId;

    @Column(name = "evidence_text", nullable = false, columnDefinition = "TEXT")
    private String evidenceText;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "source_section")
    private String sourceSection;

    @Column(name = "score")
    private Double score;

    public Evidence() {}

    public Evidence(String evidenceId, TestCase testCase, String chunkId, String evidenceText, String sourceName, String sourceSection, Double score) {
        this.evidenceId = evidenceId;
        this.testCase = testCase;
        this.chunkId = chunkId;
        this.evidenceText = evidenceText;
        this.sourceName = sourceName;
        this.sourceSection = sourceSection;
        this.score = score;
    }

    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }

    public TestCase getTestCase() { return testCase; }
    public void setTestCase(TestCase testCase) { this.testCase = testCase; }

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getEvidenceText() { return evidenceText; }
    public void setEvidenceText(String evidenceText) { this.evidenceText = evidenceText; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getSourceSection() { return sourceSection; }
    public void setSourceSection(String sourceSection) { this.sourceSection = sourceSection; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    public static EvidenceBuilder builder() {
        return new EvidenceBuilder();
    }

    public static class EvidenceBuilder {
        private String evidenceId;
        private TestCase testCase;
        private String chunkId;
        private String evidenceText;
        private String sourceName;
        private String sourceSection;
        private Double score;

        public EvidenceBuilder evidenceId(String evidenceId) {
            this.evidenceId = evidenceId;
            return this;
        }

        public EvidenceBuilder testCase(TestCase testCase) {
            this.testCase = testCase;
            return this;
        }

        public EvidenceBuilder chunkId(String chunkId) {
            this.chunkId = chunkId;
            return this;
        }

        public EvidenceBuilder evidenceText(String evidenceText) {
            this.evidenceText = evidenceText;
            return this;
        }

        public EvidenceBuilder sourceName(String sourceName) {
            this.sourceName = sourceName;
            return this;
        }

        public EvidenceBuilder sourceSection(String sourceSection) {
            this.sourceSection = sourceSection;
            return this;
        }

        public EvidenceBuilder score(Double score) {
            this.score = score;
            return this;
        }

        public Evidence build() {
            return new Evidence(evidenceId, testCase, chunkId, evidenceText, sourceName, sourceSection, score);
        }
    }
}
