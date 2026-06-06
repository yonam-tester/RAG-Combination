package com.yeonam.tester.repository;

import com.yeonam.tester.domain.ReportTestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReportTestCaseRepository extends JpaRepository<ReportTestCase, Long> {
}
