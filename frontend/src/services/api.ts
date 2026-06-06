import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080/api';

export const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

export interface Project {
  projectId: string;
  projectName: string;
  description: string;
  githubUrl?: string;
  githubBranch?: string;
  integrationStatus?: string;
  createdAt: string;
}

export interface ProjectCreateRequest {
  projectName: string;
  description: string;
  githubUrl?: string;
  githubBranch?: string;
}

export interface UploadedFile {
  documentId: string;
  fileName: string;
  fileType: string;
  status: string;
  fileSizeByte: number;
  uploadedAt: string;
}

export interface FileStatusResponse {
  documentId: string;
  status: string;
  processingStep: string;
  message: string;
}

export interface QaRecommendationResponse {
  recommendedPerspectives: string[];
  reason: string;
}

export interface AnalysisCreateRequest {
  targetDocumentIds: string[];
  qaPerspectives: string[];
  customPrompt: string;
  llmApiKey?: string;
}

export interface AnalysisJobResponse {
  analysisId: string;
  projectId: string;
  status: string;
  createdAt: string;
  qaPerspective?: string;
}

export interface AnalysisStatusResponse {
  analysisId: string;
  status: string;
  message: string;
  progressPercentage: number;
}

export interface Evidence {
  evidenceId: string;
  evidenceText: string;
  sourceName: string;
  sourceSection?: string;
  confidenceLevel: string;
  score?: number;
}

export interface TestCase {
  testCaseId: string;
  testCaseName: string;
  testScenario: string;
  precondition?: string;
  testSteps: string[];
  expectedResult: string;
  priority: string;
  riskTags: string[];
  relatedRequirements: string[];
  evidences: Evidence[];
  category?: string;
  technique?: string;
  tddHint?: string;
  negativeScenario?: string;
  caution?: string;
  analysisId: string;
}

export interface AnalysisResultResponse {
  analysisId: string;
  summary: string;
  testCases: TestCase[];
  missingItems: string[];
}

export interface ReportItem {
  reportId: string;
  analysisId: string;
  reportFormat: string;
  status: string;
  generatedAt: string;
}

export interface ReportListResponse {
  reports: ReportItem[];
}

export interface ReportPreviewResponse {
  reportId: string;
  reportFormat: string;
  content: string;
  disclaimer: string;
  generatedAt: string;
}

// API Functions
export const projectApi = {
  create: (data: ProjectCreateRequest) => api.post<Project>('/projects', data),
  getAll: () => api.get<Project[]>('/projects'),
  getById: (id: string) => api.get<Project>(`/projects/${id}`),
  delete: (id: string) => api.delete(`/projects/${id}`),
};

export const fileApi = {
  upload: (projectId: string, file: File, fileType: string = 'REQUIREMENT_SPEC') => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post<UploadedFile>(`/projects/${projectId}/files`, formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
  },
  getByProject: (projectId: string) => api.get<UploadedFile[]>(`/projects/${projectId}/files`),
  getStatus: (fileId: string) => api.get<FileStatusResponse>(`/files/${fileId}/status`),
  download: (fileId: string) => `${API_BASE_URL}/files/${fileId}/download`,
  delete: (fileId: string) => api.delete(`/files/${fileId}`),
};

export const analysisApi = {
  start: (projectId: string, data: AnalysisCreateRequest) => api.post<AnalysisJobResponse>(`/projects/${projectId}/analysis`, data),
  getJob: (analysisId: string) => api.get<AnalysisJobResponse>(`/analysis/${analysisId}`),
  getStatus: (analysisId: string) => api.get<AnalysisStatusResponse>(`/analysis/${analysisId}/status`),
  getResults: (analysisId: string) => api.get<AnalysisResultResponse>(`/analysis/${analysisId}/results`),
  getByProject: (projectId: string) => api.get<AnalysisJobResponse[]>(`/projects/${projectId}/analysis`),
  delete: (analysisId: string) => api.delete(`/analysis/${analysisId}`),
  getRecommendations: (projectId: string) => api.get<QaRecommendationResponse>(`/projects/${projectId}/qa-recommendations`),
};

export const reportApi = {
  generate: (analysisId: string, format: 'MARKDOWN' | 'PDF', testCaseIds?: string[]) => api.post<any>(`/analysis/${analysisId}/reports`, { reportFormat: format, testCaseIds }),
  getByProject: (projectId: string, fileId?: string, analysisId?: string) => api.get<ReportListResponse>(`/projects/${projectId}/reports`, { params: { fileId, analysisId } }),
  getPreview: (reportId: string) => api.get<ReportPreviewResponse>(`/reports/${reportId}`),
  download: (reportId: string, format?: string) => `${API_BASE_URL}/reports/${reportId}/download${format ? `?format=${format}` : ''}`,
  delete: (reportId: string) => api.delete(`/reports/${reportId}`),
};

export const testCaseApi = {
  getByProject: (projectId: string) => api.get<TestCase[]>(`/projects/${projectId}/testcases`),
  update: (testCaseId: string, data: Partial<TestCase>) => api.put<TestCase>(`/testcases/${testCaseId}`, data),
  delete: (testCaseId: string) => api.delete(`/testcases/${testCaseId}`),
};
