package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "report_test_case")
public class ReportTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    public ReportTestCase() {}

    public ReportTestCase(Report report, TestCase testCase) {
        this.report = report;
        this.testCase = testCase;
    }

    public ReportTestCase(Long id, Report report, TestCase testCase) {
        this.id = id;
        this.report = report;
        this.testCase = testCase;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Report getReport() { return report; }
    public void setReport(Report report) { this.report = report; }

    public TestCase getTestCase() { return testCase; }
    public void setTestCase(TestCase testCase) { this.testCase = testCase; }

    public static ReportTestCaseBuilder builder() {
        return new ReportTestCaseBuilder();
    }

    public static class ReportTestCaseBuilder {
        private Long id;
        private Report report;
        private TestCase testCase;

        public ReportTestCaseBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ReportTestCaseBuilder report(Report report) {
            this.report = report;
            return this;
        }

        public ReportTestCaseBuilder testCase(TestCase testCase) {
            this.testCase = testCase;
            return this;
        }

        public ReportTestCase build() {
            return new ReportTestCase(id, report, testCase);
        }
    }
}
