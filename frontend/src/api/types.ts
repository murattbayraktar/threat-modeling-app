// Backend kontratlarının TypeScript karşılıkları (contracts/*.schema.json ile uyumlu).

export type LlmMode = 'SERVER' | 'BROWSER';
export type StepStatus = 'PENDING' | 'RUNNING' | 'WAITING_USER' | 'WAITING_LLM' | 'DONE' | 'FAILED';
export type ReviewStatus = 'IN_PROGRESS' | 'WAITING_MODEL_APPROVAL' | 'WAITING_LLM' | 'IN_EXPERT_REVIEW' | 'COMPLETED' | 'FAILED';
export type Stride = 'SPOOFING' | 'TAMPERING' | 'REPUDIATION' | 'INFORMATION_DISCLOSURE' | 'DENIAL_OF_SERVICE' | 'ELEVATION_OF_PRIVILEGE';
export type Decision = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'FALSE_POSITIVE';
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

export interface Review {
  id: string;
  code: string;
  title: string;
  businessUnit?: string;
  projectType?: string;
  dataClassification?: string;
  owner?: string;
  analyst?: string;
  llmMode: LlmMode;
  status: ReviewStatus;
  currentStage: string;
  sampleName?: string;
  intakeAnswers: Record<string, unknown>;
  expertReviewCompleted: boolean;
  createdBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface StepView {
  stage: string;
  label: string;
  actor: 'SYSTEM' | 'LLM' | 'ANALYST';
  status: StepStatus;
  message?: string;
  metrics: Record<string, unknown>;
  startedAt?: string;
  finishedAt?: string;
}

export interface DocumentView {
  id: string;
  documentKey: string;
  fileName: string;
  contentType?: string;
  sizeBytes: number;
  extractionMethod?: string;
  sections: number;
  chars: number;
  warnings: string[];
  hasClientText: boolean;
}

export interface ReviewSummary {
  review: Review;
  threatLevels: Record<string, number>;
  threats: number;
  pendingDecisions: number;
}

export interface ReviewDetail extends Omit<ReviewSummary, 'review'> {
  review: Review;
  steps: StepView[];
  documents: DocumentView[];
  modelVersion?: number;
  modelApproved: boolean;
  pendingLlmCallId?: string;
  running: boolean;
}

export interface Evidence { documentId: string; section: string; quote: string }

export interface Component {
  id: string;
  name: string;
  type: string;
  technology: string | null;
  zone: string;
  internetFacing: boolean | null;
  authentication: string;
  dataClasses: string[];
  description: string;
  evidence: Evidence | null;
  confidence: Confidence;
  elementKind?: string;
}

export interface DataFlow {
  id: string;
  source: string;
  target: string;
  description: string;
  protocol: string | null;
  authentication: string;
  encrypted: boolean | null;
  dataClasses: string[];
  evidence: Evidence | null;
  confidence: Confidence;
  crossesTrustBoundary?: boolean | null;
}

export interface SystemModel {
  schemaVersion: string;
  system: { name: string; description: string };
  components: Component[];
  dataFlows: DataFlow[];
  trustBoundaries: { id: string; zone: string; componentIds: string[] }[];
  missingInformation: { question: string; relatedElementId: string | null }[];
  assumptions: string[];
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING' | 'INFO';
  code: string;
  elementId: string | null;
  message: string;
}

export interface ModelVersion {
  id: string;
  version: number;
  source: string;
  model: SystemModel;
  issues: ValidationIssue[];
  createdBy: string;
  createdAt: string;
  approvedBy?: string;
  approvedAt?: string;
}

export interface ModelView {
  current: ModelVersion | null;
  versions: { version: number; source: string; createdBy: string; createdAt: string; approvedBy?: string; approvedAt?: string; issues: number }[];
  editable: boolean;
}

export interface Mitigation { title: string; description: string; sourceIds: string[]; origin: string }
export interface SourceRef { id: string; source: string; title: string; reference: string }
export interface RiskTraceStep { ruleId: string; description: string; field: string; op: string; value: number; before: number; after: number; rationale: string }

export interface ThreatView {
  id: string;
  code: string;
  modelVersion: number;
  candidateId?: string;
  ruleId?: string;
  stride: Stride;
  targetId: string;
  targetName: string;
  aiAdditional: boolean;
  title: string;
  description: string;
  likelihood?: number;
  impact?: number;
  score?: number;
  level?: string;
  mitigations: Mitigation[];
  decision: Decision;
  ai: {
    title: string; description: string; attackScenario?: string; suggestedLikelihood?: number; suggestedImpact?: number;
    riskRationale?: string; mitigations: Mitigation[]; confidence?: Confidence; assumptions: string[];
    applicable: boolean; notApplicableReason?: string; llmCallId?: string; fromRuleTemplate: boolean;
  };
  system?: {
    likelihood: number; impact: number; score: number; level: string; baselineSource: string;
    baselineLikelihood: number; baselineImpact: number; trace: RiskTraceStep[]; methodologyVersion: string; riskRulesVersion: string;
  };
  expert: {
    decision: Decision; title?: string; description?: string; likelihood?: number; impact?: number; score?: number;
    level?: string; rationale?: string; mitigations?: Mitigation[]; note?: string; decidedBy?: string; decidedAt?: string;
  };
  sources: SourceRef[];
  issues: ValidationIssue[];
}

export interface ThreatCandidate {
  id: string; ruleId: string; stride: Stride; targetId: string; targetName: string; title: string; description: string;
  defaultLikelihood: number; defaultImpact: number; ragQuery: string; tags: string[];
}

export interface KnowledgeChunk { id: string; source: string; title: string; text: string; mitigation: string; tags: string[]; reference: string }
export interface RetrievedChunk { chunk: KnowledgeChunk; score: number; lexicalScore: number; vectorScore: number }

export interface LlmTask {
  callId: string; reviewId: string; stage: string; callKey: string; attempt: number; task: string;
  promptVersion: string; systemPrompt: string; userPrompt: string; schema: unknown; temperature: number;
}

export interface LlmCallView {
  id: string; stage: string; callKey: string; attempt: number; executor: string; model?: string; status: string;
  promptVersion: string; systemPrompt: string; userPrompt: string; rawOutput?: string; errors: string[];
  durationMs?: number; createdAt: string; completedAt?: string;
}

export interface AuditEvent { id: string; reviewId: string; actor: string; type: string; message: string; createdAt: string }
export interface ThreatEvent { id: string; threatId: string; actor: string; action: string; beforeJson?: string; afterJson?: string; note?: string; createdAt: string }

export interface Report {
  review: Review;
  generatedAt: string;
  modelSummary: Record<string, number>;
  decisions: Record<string, number>;
  strideDistribution: Record<string, number>;
  levelDistribution: Record<string, number>;
  riskMatrix: number[][];
  criticalThreats: ReportThreatLine[];
  threats: ReportThreatLine[];
  actionPlan: { threatCode: string; threatTitle: string; level: string; mitigationTitle: string; mitigationDescription: string; sourceIds: string[]; origin: string }[];
  missingStrideCategories: string[];
  openQuestions: { question: string; relatedElementId: string | null }[];
  versions: { threatRules: string; riskRules: string; methodology: string; prompts: string[]; llmExecutors: string[]; modelVersion?: number; modelApprovedBy?: string };
  audit: AuditEvent[];
  disclaimer: string;
}
export interface ReportThreatLine { code: string; title: string; stride: string; target: string; likelihood?: number; impact?: number; score?: number; level?: string; decision: string; expertOverride: boolean }

export interface Evaluation {
  sample: string; expected: number; matched: number; recall: number; generated: number; unmatchedGenerated: number;
  details: { id: string; stride: string; target: string; note: string; matchedThreatCode?: string }[];
  unmatchedThreats: string[];
}

export interface Meta {
  stages: { id: string; label: string; actor: string }[];
  componentTypes: string[];
  zones: string[];
  authMethods: string[];
  dataClasses: string[];
  stride: Stride[];
  riskLevels: { id: string; label: string; min: number; max: number }[];
  llm: { serverProvider: string; serverModel: string; defaultMode: LlmMode; browserEnabled: boolean; browserDefaultModel: string; browserModelBaseUrl?: string; browserConfigUrl?: string };
  extraction?: { browserPdfText: boolean; ocrMode: string; ocrLanguages: string; tesseractWorkerPath: string; tesseractCorePath: string; tesseractLangPath: string };
  retrievalMode: string;
  versions: Record<string, string>;
}

export interface Sample {
  name: string;
  intake: { title?: string; businessUnit?: string; projectType?: string; dataClassification?: string };
  files: { fileName: string; contentType: string; size: number }[];
  hasEvaluation: boolean;
}

// ------------------------------------------------------------------ Sistem parametreleri
export interface SystemSettings {
  llm: { provider: string; baseUrl: string; apiKey: string; model: string; timeoutSeconds: number; structuredOutputMode: string; maxAttempts: number; taskModels: Record<string, string>; taskConnections: Record<string, { baseUrl: string; apiKey: string }> };
  embedding: { enabled: boolean; baseUrl: string; apiKey: string; model: string };
  browserLlm: { enabled: boolean; defaultModel: string; configUrl: string };
  extraction: { browserPdfText: boolean; ocrMode: string; ocrLanguages: string; tesseractWorkerPath: string; tesseractCorePath: string; tesseractLangPath: string };
  pipeline: { defaultLlmMode: LlmMode; temperature: number; modelExtractionMaxCharsServer: number; modelExtractionMaxCharsBrowser: number; threatBatchSizeServer: number; threatBatchSizeBrowser: number; retrievalPerCandidate: number; retrievalMaxPerSource: number };
}
export interface SettingsResponse {
  settings: SystemSettings;
  secrets: Record<string, { set: boolean; hint: string }>;
  policy: { encryptionEnabled: boolean; blockPublicEndpoints: boolean; internalHostSuffixes: string[] };
  source: string;
  canEdit: boolean;
  adminsConfigured: boolean;
  active: { llm: string; retrieval: string };
}
export interface SettingsTestResult { target: string; model: string; ok: boolean; message: string; durationMs: number; scope?: string }
export interface ModelListResponse { ok: boolean; models: string[]; host?: string; external?: boolean; message?: string }
export interface SettingChange { changedBy: string; summary: string; changedAt: string }
