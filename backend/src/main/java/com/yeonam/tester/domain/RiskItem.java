package com.yeonam.tester.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "risk_item")
public class RiskItem {

    @Id
    @Column(name = "risk_id")
    private String riskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", nullable = false)
    private TestCase testCase;

    @Column(name = "risk_type", nullable = false)
    private String riskType;

    public RiskItem() {}

    public RiskItem(String riskId, TestCase testCase, String riskType) {
        this.riskId = riskId;
        this.testCase = testCase;
        this.riskType = riskType;
    }

    public String getRiskId() { return riskId; }
    public void setRiskId(String riskId) { this.riskId = riskId; }

    public TestCase getTestCase() { return testCase; }
    public void setTestCase(TestCase testCase) { this.testCase = testCase; }

    public String getRiskType() { return riskType; }
    public void setRiskType(String riskType) { this.riskType = riskType; }

    public static RiskItemBuilder builder() {
        return new RiskItemBuilder();
    }

    public static class RiskItemBuilder {
        private String riskId;
        private TestCase testCase;
        private String riskType;

        public RiskItemBuilder riskId(String riskId) {
            this.riskId = riskId;
            return this;
        }

        public RiskItemBuilder testCase(TestCase testCase) {
            this.testCase = testCase;
            return this;
        }

        public RiskItemBuilder riskType(String riskType) {
            this.riskType = riskType;
            return this;
        }

        public RiskItem build() {
            return new RiskItem(riskId, testCase, riskType);
        }
    }
}
