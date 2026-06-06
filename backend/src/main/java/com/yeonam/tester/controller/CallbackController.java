package com.yeonam.tester.controller;

import com.yeonam.tester.dto.AnalysisCallbackRequest;
import com.yeonam.tester.service.CallbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
public class CallbackController {

    private final CallbackService callbackService;

    public CallbackController(CallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/api/internal/analysis/{analysisId}/callback")
    public ResponseEntity<?> callback(@PathVariable String analysisId, @RequestBody AnalysisCallbackRequest request) {
        try {
            callbackService.processCallback(analysisId, request);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ErrorResponse("Callback processing failed: " + e.getMessage()));
        }
    }

    private static class ErrorResponse {
        private final String message;
        public ErrorResponse(String message) {
            this.message = message;
        }
        public String getMessage() {
            return message;
        }
    }
}
