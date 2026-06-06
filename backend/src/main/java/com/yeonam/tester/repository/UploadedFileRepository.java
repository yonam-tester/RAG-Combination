package com.yeonam.tester.repository;

import com.yeonam.tester.domain.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFile, String> {
    List<UploadedFile> findByProject_ProjectId(String projectId);
}
