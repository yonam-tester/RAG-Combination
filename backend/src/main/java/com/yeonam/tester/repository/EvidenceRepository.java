package com.yeonam.tester.repository;

import com.yeonam.tester.domain.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EvidenceRepository extends JpaRepository<Evidence, String> {
    List<Evidence> findByTestCase_TestCaseId(String testCaseId);
    List<Evidence> findByTestCase_TestCaseIdIn(List<String> testCaseIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Evidence e WHERE e.testCase.testCaseId IN :testCaseIds")
    void deleteByTestCaseIds(@Param("testCaseIds") List<String> testCaseIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Evidence e WHERE e.testCase.analysisJob.analysisId IN :analysisIds")
    void deleteByAnalysisIds(@Param("analysisIds") List<String> analysisIds);
}
