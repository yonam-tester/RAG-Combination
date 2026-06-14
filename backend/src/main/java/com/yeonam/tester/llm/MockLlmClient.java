package com.yeonam.tester.llm;

import java.util.List;

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

    private static final String[] MOCK_SUPPLEMENTARY = {
        "{\"testCaseName\":\"동시 다중 요청 처리 시 데이터 정합성 검증\"," +
        "\"testScenario\":\"동일 리소스에 동시에 여러 요청이 도달할 때 경쟁 조건(Race Condition) 없이 데이터가 일관되게 처리되는지 확인한다.\"," +
        "\"precondition\":\"동시성 부하를 발생시킬 수 있는 클라이언트 도구(JMeter 또는 k6)가 준비되어 있고, 테스트 데이터가 DB에 초기화되어 있어야 한다.\"," +
        "\"testSteps\":\"1. 동일 리소스에 대한 10개의 병렬 요청을 동시에 전송한다.\\n2. 각 요청의 응답 상태 코드를 수집한다.\\n3. 데이터베이스에서 최종 저장된 데이터를 조회하고 일관성을 검증한다.\"," +
        "\"expectedResult\":\"모든 요청이 적절히 처리되거나 충돌 시 명확한 오류 응답이 반환되며, 최종 데이터 상태가 정합성을 유지한다.\"," +
        "\"priority\":\"HIGH\"," +
        "\"technique\":\"State Transition Testing\"," +
        "\"riskTags\":[\"동시성_오류\",\"데이터유효성\",\"서버오류\"]}",

        "{\"testCaseName\":\"토큰 만료 후 API 접근 차단 검증\"," +
        "\"testScenario\":\"인증 토큰의 유효 기간이 만료된 이후에도 API 요청이 허용되지 않고 401 응답이 반환되는지 검증한다.\"," +
        "\"precondition\":\"만료된 JWT 토큰을 직접 생성하거나 시간 조작이 가능한 테스트 환경이 준비되어 있어야 한다.\"," +
        "\"testSteps\":\"1. 만료 시간이 지난 JWT 토큰을 준비한다.\\n2. 해당 토큰으로 보호된 API 엔드포인트에 요청을 전송한다.\\n3. 응답 상태 코드와 오류 메시지를 확인한다.\"," +
        "\"expectedResult\":\"HTTP 401 Unauthorized 응답이 반환되고, 만료된 토큰으로는 어떠한 보호 리소스에도 접근할 수 없다.\"," +
        "\"priority\":\"HIGH\"," +
        "\"technique\":\"Security Testing\"," +
        "\"riskTags\":[\"인증\",\"CREDENTIAL_EXPOSURE\",\"권한검증\"]}",

        "{\"testCaseName\":\"입력 필드 최대 길이 초과 시 오류 반환 검증\"," +
        "\"testScenario\":\"허용된 최대 문자 수를 초과하는 입력값을 전송했을 때 서버가 적절히 거부하고 오류 메시지를 반환하는지 확인한다.\"," +
        "\"precondition\":\"API 클라이언트(Postman 또는 curl) 및 각 필드의 최대 허용 길이 명세가 준비되어 있어야 한다.\"," +
        "\"testSteps\":\"1. 최대 허용 길이보다 1자 초과하는 문자열을 생성한다.\\n2. 해당 값을 입력 필드에 포함하여 API 요청을 전송한다.\\n3. 응답 코드와 오류 메시지를 확인한다.\"," +
        "\"expectedResult\":\"HTTP 400 Bad Request와 함께 최대 길이 초과를 알리는 유효성 검사 오류 메시지가 반환된다.\"," +
        "\"priority\":\"MEDIUM\"," +
        "\"technique\":\"Boundary Value Analysis\"," +
        "\"riskTags\":[\"입력검증\",\"유효성검사\",\"오류처리\"]}"
    };

    @Override
    public String generateSupplementaryScenarios(String summary, String qaPerspective, List<String> existingNames, int count) {
        int available = MOCK_SUPPLEMENTARY.length;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(MOCK_SUPPLEMENTARY[i % available]);
        }
        sb.append("]");
        return sb.toString();
    }
}
