package com.yeonam.tester.service;

import com.yeonam.tester.dto.AnalysisCallbackRequest.EvidenceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(FallbackHandler.class);

    /**
     * Checks if a single evidence chunk is hallucinated or invalid.
     */
    public boolean isHallucinated(String chunkId, String text) {
        if (chunkId == null || chunkId.trim().isEmpty()) {
            log.warn("[HALLUCINATION DETECTED] chunkId is null or empty.");
            return true;
        }
        if (chunkId.startsWith("HALLUCINATED_")) {
            log.warn("[HALLUCINATION DETECTED] chunkId starts with HALLUCINATED_: {}", chunkId);
            return true;
        }
        if (text == null || text.trim().isEmpty()) {
            log.warn("[HALLUCINATION DETECTED] Evidence text is null or empty for chunk: {}", chunkId);
            return true;
        }
        return false;
    }

    /**
     * Filters a list of EvidenceDto, removing hallucinated or invalid ones.
     */
    public List<EvidenceDto> filterEvidences(List<EvidenceDto> dtos) {
        if (dtos == null) {
            return new ArrayList<>();
        }
        List<EvidenceDto> validEvidences = new ArrayList<>();
        for (EvidenceDto dto : dtos) {
            if (!isHallucinated(dto.getChunkId(), dto.getEvidenceText())) {
                validEvidences.add(dto);
            }
        }
        return validEvidences;
    }
}
