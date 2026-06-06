package com.yeonam.tester;

import com.yeonam.tester.domain.*;
import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.repository.*;
import com.yeonam.tester.service.CallbackService;
import com.yeonam.tester.service.PriorityEvaluator;
import com.yeonam.tester.service.RiskDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Phase4Tests {

    @Autowired
    private CallbackService callbackService;

    @Autowired
    private PriorityEvaluator priorityEvaluator;

    @Autowired
    private RiskDetector riskDetector;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private RiskItemRepository riskItemRepository;

    @Test
    @Transactional
    void testAnalysisTriggerAndMockCallbackE2E() {
        // 1. Setup a project
        Project project = Project.builder()
                .projectId("PRJ-P4-TEST")
                .name("Phase 4 E2E Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        project = projectRepository.saveAndFlush(project);

        // 2. Setup Analysis Job directly in WAITING status to avoid async thread transactional conflicts
        String analysisId = "ANL-P4-MOCK-E2E";
        AnalysisJob job = AnalysisJob.builder()
                .analysisId(analysisId)
                .project(project)
                .status("WAITING")
                .qaPerspective("SECURITY,BACKEND")
                .customPrompt("인증 및 보안 집중 테스트")
                .build();
        analysisJobRepository.saveAndFlush(job);

        // 3. Mock callback payload
        AnalysisCallbackRequest.TestCaseDto tcDto = AnalysisCallbackRequest.TestCaseDto.builder()
                .testCaseId("TC-P4-001")
                .requirementId("REQ-P4-001")
                .requirementText("사용자는 유효한 패스워드를 입력하여 로그인을 완료해야 한다.")
                .testCaseName("올바른 비밀번호 입력 시 로그인 성공")
                .testScenario("사용자가 정상적인 ID와 비밀번호를 입력하고 로그인 버튼을 클릭했을 때의 성공 여부 검증")
                .precondition("회원가입 완료된 정상 계정이 H2 DB에 등록되어 있음")
                .testSteps(List.of("1. 로그인 화면에 진입한다.", "2. 올바른 계정 정보 입력.", "3. 로그인 성공 및 대시보드 리다이렉트 확인."))
                .expectedResult("정상 로그인 완료 및 메인 홈으로 진입됨")
                .priority("HIGH")
                .confidenceLevel("HIGH")
                .riskTags(List.of("인증_실패", "보안_위험"))
                .build();

        AnalysisCallbackRequest callbackReq = AnalysisCallbackRequest.builder()
                .summary("패스워드 입력 및 세션 발급에 대한 테스트 케이스 1건 생성 완료.")
                .testCases(List.of(tcDto))
                .missingItems(List.of("비밀번호 실패 시 세션 잠금 정책 명세 누락"))
                .build();

        // Call the webhook service callback
        callbackService.processCallback(analysisId, callbackReq);

        // 4. Verify DB storage
        AnalysisJob updatedJob = analysisJobRepository.findById(analysisId).orElse(null);
        assertNotNull(updatedJob);
        assertEquals("COMPLETED", updatedJob.getStatus());
        assertEquals("패스워드 입력 및 세션 발급에 대한 테스트 케이스 1건 생성 완료.", updatedJob.getSummary());

        // Verify Requirement
        Requirement requirement = requirementRepository.findById("REQ-P4-001").orElse(null);
        assertNotNull(requirement);
        assertEquals("사용자는 유효한 패스워드를 입력하여 로그인을 완료해야 한다.", requirement.getRequirementText());

        // Verify TestCase
        TestCase testCase = testCaseRepository.findById("TC-P4-001").orElse(null);
        assertNotNull(testCase);
        assertEquals("올바른 비밀번호 입력 시 로그인 성공", testCase.getTestCaseName());
        assertEquals("HIGH", testCase.getPriority());
        assertEquals("HIGH", testCase.getConfidenceLevel());
        assertTrue(testCase.getTestSteps().contains("로그인 성공 및 대시보드 리다이렉트 확인"));

        // Verify Risk Items
        List<RiskItem> risks = riskItemRepository.findByTestCase_TestCaseIdIn(List.of("TC-P4-001"));
        assertEquals(2, risks.size());
        assertTrue(risks.stream().anyMatch(r -> r.getRiskType().equals("인증_실패")));
        assertTrue(risks.stream().anyMatch(r -> r.getRiskType().equals("보안_위험")));
    }

    @Test
    void testPriorityEvaluationLogic() {
        // High priority: Payment or Security + Exception/Error + MUST
        // score: 3 (결제) + 2 (반드시) + 2 (차단) = 7 (HIGH)
        String priority1 = priorityEvaluator.evaluatePriority(
                "사용자는 반드시 카드로 결제를 수행한다.", 
                "한도 초과 카드 결제 시도 시 에러 응답 및 차단"
        );
        assertEquals("HIGH", priority1);

        // Medium priority: Basic system feature or normal flows
        // score: 3 (결제) = 3 (MEDIUM)
        String priority2 = priorityEvaluator.evaluatePriority(
                "사용자는 카드로 결제를 수행한다.", 
                "일반적인 결제 성공 흐름"
        );
        assertEquals("MEDIUM", priority2);

        // Low priority/Fallback: Minimal score
        String priority3 = priorityEvaluator.evaluatePriority(
                "장식용 디자인 확인", 
                "단순 화면 스크롤"
        );
        assertEquals("추가 검토 필요", priority3);
    }

    @Test
    void testRiskDetectionAndTagMapping() {
        // Security keywords
        List<String> risks1 = riskDetector.detectRisks("로그인 페이지를 개발한다.", "잘못된 패스워드 입력");
        assertTrue(risks1.contains("인증_실패"));
        assertTrue(risks1.contains("보안_위험"));

        // Network / Server keywords
        List<String> risks2 = riskDetector.detectRisks("API 연동", "네트워크 단절 시 예외 처리");
        assertTrue(risks2.contains("통신_장애"));

        // Input forms validation
        List<String> risks3 = riskDetector.detectRisks("주소 입력 폼", "유효성 검증 포맷 제한");
        assertTrue(risks3.contains("입력값_오류"));
    }

    @Test
    @Transactional
    void testAnalysisFailureCallback() {
        // 1. Setup a project and analysis job
        Project project = Project.builder()
                .projectId("PRJ-P4-FAIL-TEST")
                .name("Phase 4 Failure Test Project")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        project = projectRepository.saveAndFlush(project);

        AnalysisJob job = AnalysisJob.builder()
                .analysisId("ANL-P4-FAIL-001")
                .project(project)
                .status("WAITING")
                .build();
        analysisJobRepository.saveAndFlush(job);

        // 2. Callback with status = FAILED
        AnalysisCallbackRequest failReq = AnalysisCallbackRequest.builder()
                .status("FAILED")
                .errorMessage("LiteLLM call failed: rate limit exceeded")
                .build();

        callbackService.processCallback("ANL-P4-FAIL-001", failReq);

        // 3. Verify
        AnalysisJob updatedJob = analysisJobRepository.findById("ANL-P4-FAIL-001").orElse(null);
        assertNotNull(updatedJob);
        assertEquals("FAILED", updatedJob.getStatus());
        assertEquals("LiteLLM call failed: rate limit exceeded", updatedJob.getSummary());
    }
}

