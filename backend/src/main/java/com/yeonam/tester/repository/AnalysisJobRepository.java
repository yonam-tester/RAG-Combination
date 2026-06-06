package com.yeonam.tester.repository;

import com.yeonam.tester.domain.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, String> {
    List<AnalysisJob> findByProject_ProjectId(String projectId);

    @Modifying
    @Query("DELETE FROM AnalysisJob a WHERE a.project.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);
}
