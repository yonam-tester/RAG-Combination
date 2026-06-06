package com.yeonam.tester.repository;

import com.yeonam.tester.domain.RiskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RiskItemRepository extends JpaRepository<RiskItem, String> {
    List<RiskItem> findByTestCase_TestCaseId(String testCaseId);
    List<RiskItem> findByTestCase_TestCaseIdIn(List<String> testCaseIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RiskItem r WHERE r.testCase.testCaseId IN :testCaseIds")
    void deleteByTestCaseIds(@Param("testCaseIds") List<String> testCaseIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RiskItem r WHERE r.testCase.analysisJob.analysisId IN :analysisIds")
    void deleteByAnalysisIds(@Param("analysisIds") List<String> analysisIds);
}
