package com.yeonam.tester.repository;

import com.yeonam.tester.domain.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, String> {
    List<TestCase> findByAnalysisJob_AnalysisId(String analysisId);

    @Query("SELECT t FROM TestCase t JOIN t.analysisJob a WHERE a.project.projectId = :projectId")
    List<TestCase> findByProjectId(@Param("projectId") String projectId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TestCase t WHERE t.analysisJob.analysisId IN :analysisIds")
    void deleteByAnalysisIds(@Param("analysisIds") List<String> analysisIds);
}
