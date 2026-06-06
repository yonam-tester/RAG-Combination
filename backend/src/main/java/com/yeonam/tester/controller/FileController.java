package com.yeonam.tester.controller;

import com.yeonam.tester.domain.Project;
import com.yeonam.tester.domain.UploadedFile;
import com.yeonam.tester.dto.FileResponse;
import com.yeonam.tester.dto.FileStatusResponse;
import com.yeonam.tester.service.FileService;
import com.yeonam.tester.service.ProjectService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
public class FileController {

    private final FileService fileService;
    private final ProjectService projectService;

    public FileController(FileService fileService, ProjectService projectService) {
        this.fileService = fileService;
        this.projectService = projectService;
    }

    @PostMapping("/api/projects/{projectId}/files")
    public ResponseEntity<?> uploadFile(@PathVariable String projectId,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "fileType", defaultValue = "REQUIREMENT_SPEC") String fileType) {
        try {
            UploadedFile uploadedFile = fileService.uploadFile(projectId, file, fileType);

            FileResponse response = FileResponse.builder()
                    .documentId(uploadedFile.getFileId())
                    .fileName(uploadedFile.getFileName())
                    .fileType(uploadedFile.getFileType())
                    .status(uploadedFile.getStatus())
                    .fileSizeByte(file.getSize())
                    .uploadedAt(LocalDateTime.now())
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/api/projects/{projectId}/files")
    public ResponseEntity<List<FileResponse>> getFilesByProject(@PathVariable String projectId) {
        List<UploadedFile> files = fileService.getFilesByProjectId(projectId);
        List<FileResponse> response = files.stream().map(file -> FileResponse.builder()
                .documentId(file.getFileId())
                .fileName(file.getFileName())
                .fileType(file.getFileType())
                .status(file.getStatus())
                .fileSizeByte(0L) // Default or not stored
                .uploadedAt(LocalDateTime.now())
                .build()
        ).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/files/{fileId}/status")
    public ResponseEntity<?> getFileStatus(@PathVariable String fileId) {
        try {
            UploadedFile file = fileService.getFileById(fileId);
            
            // Map file status and processing steps for the polling client
            String step = "DONE".equals(file.getStatus()) ? "COMPLETED" : "EXTRACTING";
            String msg = "UPLOADED".equals(file.getStatus()) ? "문서 업로드 완료. 대기 중..." : 
                        "PROCESSING".equals(file.getStatus()) ? "문서 텍스트 추출 중..." :
                        "DONE".equals(file.getStatus()) ? "전처리 완료." : "전처리 실패.";

            FileStatusResponse status = FileStatusResponse.builder()
                    .documentId(file.getFileId())
                    .status(file.getStatus())
                    .processingStep(step)
                    .message(msg)
                    .build();

            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/api/files/{fileId}/download")
    public ResponseEntity<?> downloadFile(@PathVariable String fileId) {
        try {
            UploadedFile file = fileService.getFileById(fileId);
            byte[] fileBytes = fileService.downloadFileBytes(file);

            String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(fileBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Download failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/api/files/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String fileId) {
        try {
            fileService.deleteFile(fileId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Delete failed: " + e.getMessage()));
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
