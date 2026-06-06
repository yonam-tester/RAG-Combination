package com.yeonam.tester.repository;

import com.yeonam.tester.domain.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RequirementRepository extends JpaRepository<Requirement, String> {
    List<Requirement> findByAnalysisJob_AnalysisId(String analysisId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Requirement r WHERE r.analysisJob.analysisId IN :analysisIds")
    void deleteByAnalysisIds(@Param("analysisIds") List<String> analysisIds);
}
