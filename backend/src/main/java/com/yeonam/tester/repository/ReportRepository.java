package com.yeonam.tester.repository;

import com.yeonam.tester.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByAnalysisJob_Project_ProjectId(String projectId);

    List<Report> findByUploadedFile_FileId(String fileId);

    @Query("SELECT DISTINCT r FROM Report r " +
           "LEFT JOIN r.reportTestCases rtc " +
           "LEFT JOIN rtc.testCase tc " +
           "LEFT JOIN tc.evidences e " +
           "LEFT JOIN UploadedFile uf ON e.sourceName = uf.fileName " +
           "WHERE r.analysisJob.project.projectId = :projectId " +
           "AND (:fileId IS NULL OR r.uploadedFile.fileId = :fileId OR uf.fileId = :fileId) " +
           "AND (:analysisId IS NULL OR r.analysisJob.analysisId = :analysisId)")
    List<Report> findByProjectWithFilters(
            @Param("projectId") String projectId,
            @Param("fileId") String fileId,
            @Param("analysisId") String analysisId
    );

    List<Report> findByAnalysisJob_AnalysisIdIn(List<String> analysisIds);

    @Modifying
    @Query("DELETE FROM Report r WHERE r.analysisJob.project.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") String projectId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Report r WHERE r.analysisJob.analysisId IN :analysisIds")
    void deleteByAnalysisIds(@Param("analysisIds") List<String> analysisIds);
}
