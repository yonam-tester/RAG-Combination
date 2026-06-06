import re
import json
import logging

logger = logging.getLogger("llm_server.result_formatter")

def extract_json_block(text: str) -> str:
    """
    Extracts the JSON string from within ```json ... ``` or directly from curly braces.
    """
    # Try finding markdown code block
    pattern = r"```(?:json)?\s*([\s\S]*?)\s*```"
    match = re.search(pattern, text)
    if match:
        return match.group(1).strip()
    
    # If no code block, try finding from the first '{' to the last '}'
    first_brace = text.find('{')
    last_brace = text.rfind('}')
    if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
        return text[first_brace:last_brace+1].strip()
        
    return text.strip()

def format_and_validate_result(raw_text: str) -> dict:
    """
    Parses LLM response, applies default Fallback values, and validates types.
    """
    json_str = extract_json_block(raw_text)
    
    try:
        data = json.loads(json_str)
    except Exception as e:
        logger.error(f"Failed to parse JSON string. Raw: {json_str[:200]}")
        raise ValueError(f"Invalid JSON format in LLM response: {str(e)}")
        
    # Check top-level elements
    if not isinstance(data, dict):
        raise ValueError("Root element of LLM output must be a dictionary")
        
    if "summary" not in data or not data["summary"]:
        data["summary"] = "전체 기능 명세 요구사항 분석 요약 완료."
        
    if "requirements" not in data or not isinstance(data["requirements"], list):
        data["requirements"] = [{"id": "REQ-001", "text": "시스템 기본 요구사항"}]
        
    if "testCases" not in data or not isinstance(data["testCases"], list):
        data["testCases"] = []
        
    # Build requirement mapping
    req_map = {req["id"]: req["text"] for req in data.get("requirements", []) if "id" in req and "text" in req}

    # Standardize and Fallback for each test case
    for i, tc in enumerate(data["testCases"]):
        # Requirement mapping
        if "requirementId" not in tc or not tc["requirementId"]:
            tc["requirementId"] = "REQ-001"
            
        req_id = tc["requirementId"]
        if "requirementText" not in tc or not tc["requirementText"]:
            tc["requirementText"] = req_map.get(req_id, "시스템 기본 요구사항")
            
        if "testCaseId" not in tc or not tc["testCaseId"]:
            tc["testCaseId"] = f"TC-{i+1:03d}"
            
        # Text fields
        if "testCaseName" not in tc or not tc["testCaseName"]:
            tc["testCaseName"] = "추가 검토 필요"
            
        if "testScenario" not in tc or not tc["testScenario"]:
            tc["testScenario"] = "추가 검토 필요"
            
        if "precondition" not in tc or not tc["precondition"]:
            tc["precondition"] = "추가 검토 필요"
            
        if "expectedResult" not in tc or not tc["expectedResult"]:
            tc["expectedResult"] = "추가 검토 필요"
            
        # Test Steps list handling
        if "testSteps" not in tc or not tc["testSteps"]:
            tc["testSteps"] = "1. 추가 검토 필요"
            
        # If testSteps is a string, split it by newline
        if isinstance(tc["testSteps"], str):
            steps = [s.strip() for s in tc["testSteps"].split("\n") if s.strip()]
            tc["testSteps"] = steps if steps else ["1. 추가 검토 필요"]
        elif not isinstance(tc["testSteps"], list):
            tc["testSteps"] = ["1. 추가 검토 필요"]
            
        # Priority and Confidence
        if "priority" not in tc or tc["priority"] not in ["HIGH", "MEDIUM", "LOW"]:
            tc["priority"] = "HIGH"
            
        if "confidenceLevel" not in tc or tc["confidenceLevel"] not in ["HIGH", "MEDIUM", "LOW"]:
            tc["confidenceLevel"] = "HIGH"
            
        # Risk tags
        if "riskTags" not in tc or not isinstance(tc["riskTags"], list):
            tc["riskTags"] = []
        else:
            # Ensure risk tags have '#' prefix
            tc["riskTags"] = [tag if tag.startswith("#") else f"#{tag}" for tag in tc["riskTags"]]

        # Atlassian QA Extended fields validation and fallback
        if "category" not in tc or not tc["category"]:
            tc["category"] = "qa_concept"
            
        if "technique" not in tc or not tc["technique"]:
            tc["technique"] = "Atlassian QA Standard Principle"
            
        if "tddHint" not in tc or not tc["tddHint"]:
            tc["tddHint"] = "추가 검토 필요"
            
        if "negativeScenario" not in tc or not tc["negativeScenario"]:
            tc["negativeScenario"] = "추가 검토 필요"
            
        # Evidences handling
        if "evidences" not in tc or not isinstance(tc["evidences"], list):
            tc["evidences"] = [
                {
                    "chunkId": f"CHK-{i+1:03d}",
                    "evidenceText": tc["testScenario"],
                    "sourceName": "Unknown Source",
                    "sourceSection": "기타"
                }
            ]
        else:
            for ev in tc["evidences"]:
                if "chunkId" not in ev or not ev["chunkId"]:
                    ev["chunkId"] = f"CHK-{i+1:03d}"
                if "evidenceText" not in ev or not ev["evidenceText"]:
                    ev["evidenceText"] = tc["testScenario"]
                if "sourceName" not in ev or not ev["sourceName"]:
                    ev["sourceName"] = "Unknown Source"
                if "sourceSection" not in ev or not ev["sourceSection"]:
                    ev["sourceSection"] = "기타"
            
    return data
