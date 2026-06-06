package com.yeonam.tester.llm;

public class MockLlmClient implements LlmClient {

    @Override
    public String generateTestCases(String requirementText, String customPrompt, String qaPerspective) {
        return """
                {
                  "summary": "업로드된 명세서 텍스트를 기반으로 사용자 인증 및 권한 제어 관련 테스트 케이스를 자동 분석하였습니다.",
                  "missingItems": [
                    "사용자 비밀번호 변경 시 이전 비밀번호 확인 단계 누락",
                    "로그인 연속 실패 시 계정 잠금 정책 및 잠금 해제 조건 명세 미비"
                  ],
                  "testCases": [
                    {
                      "testCaseId": "TC-001",
                      "requirementId": "REQ-001",
                      "requirementText": "사용자는 유효한 계정 정보(이메일, 비밀번호)를 입력하여 시스템에 로그인할 수 있어야 한다.",
                      "testCaseName": "정상 로그인 및 세션 생성 검증",
                      "testScenario": "유효한 이메일과 비밀번호로 로그인 요청 시, 세션 토큰 반환 및 메인 화면 진입 여부 확인",
                      "precondition": "회원가입이 완료된 활성화 상태의 테스트 계정이 DB에 존재해야 함",
                      "testSteps": "1. 로그인 화면에 진입한다.\\n2. 유효한 이메일('test@example.com')과 비밀번호('Password123!')를 입력한다.\\n3. 로그인 버튼을 클릭한다.",
                      "expectedResult": "로그인에 성공하고 JWT 세션 토큰이 발급되며 메인 대시보드로 정상 이동한다.",
                      "priority": "HIGH",
                      "confidenceLevel": "HIGH",
                      "riskTags": ["#인증_성공", "#세션_토큰_발급"],
                      "category": "functional",
                      "technique": "Happy Path Testing",
                      "tddHint": "assertEquals(HttpStatus.OK, response.getStatusCode());\\nassertNotNull(response.getBody().getToken());",
                      "negativeScenario": "탈퇴한 계정 정보로 로그인 시도 시, HTTP 401 Unauthorized 에러와 함께 '탈퇴 처리된 계정입니다' 경고가 출력되는지 검증.",
                      "caution": "비밀번호 암호화 강도나 PBKDF2 반복 횟수 같은 세부 보안 셋업은 인프라 설정에 의존하므로 로컬 개발 모드 실행 시 점검이 필수적입니다."
                    },
                    {
                      "testCaseId": "TC-002",
                      "requirementId": "REQ-001",
                      "requirementText": "사용자는 유효한 계정 정보(이메일, 비밀번호)를 입력하여 시스템에 로그인할 수 있어야 한다.",
                      "testCaseName": "유효하지 않은 이메일 형식 차단 검증",
                      "testScenario": "이메일 골뱅이(@) 기호 누락 등 비정상 포맷 입력 시 프론트엔드 및 백엔드 이중 유효성 차단 확인",
                      "precondition": "로그인 화면에 접근한 상태",
                      "testSteps": "1. 이메일 입력 칸에 'invalid-email-format'을 기입한다.\\n2. 비밀번호를 입력한다.\\n3. 로그인 단추를 누른다.",
                      "expectedResult": "제출 버튼이 비활성화되거나, 클릭 시 즉각 '올바른 이메일 형식이 아닙니다' 유효성 경고창이 팝업된다.",
                      "priority": "MEDIUM",
                      "confidenceLevel": "HIGH",
                      "riskTags": ["#입력값_유효성", "#인증_실패"],
                      "category": "test_technique",
                      "technique": "Boundary Value Analysis",
                      "tddHint": "assertThrows(MethodArgumentNotValidException.class, () -> authController.login(request));",
                      "negativeScenario": "SQL Injection 시도값(admin' --)을 이메일 필드에 입력하여 제출 시, 안전하게 특수문자가 이스케이프 처리되며 쿼리 에러 없이 로그인 거부되는지 검증.",
                      "caution": "프론트엔드 유효성 필터링은 이메일 정규식 포맷에 맞춘 클라이언트 단 조작이 가능하므로, 항상 백엔드 API 컨트롤러 단독 검증 테스트를 통과해야 합니다."
                    }
                  ]
                }
                """;
    }
}
