import type {
  AuditEvent, Evaluation, LlmCallView, LlmMode, LlmTask, Meta, ModelVersion, ModelView, Report, RetrievedChunk,
  Review, ReviewDetail, ReviewSummary, Sample, SystemModel, ThreatCandidate, ThreatEvent, ThreatView, Mitigation,
  Decision, Stride, KnowledgeChunk, SystemSettings, SettingsResponse, SettingsTestResult, SettingChange, ModelListResponse,
} from './types';

const USER_KEY = 'tm.user';

export function currentUser(): string {
  return localStorage.getItem(USER_KEY) || 'analist';
}

export function setCurrentUser(u: string) {
  localStorage.setItem(USER_KEY, u);
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'X-User': currentUser() };
  let payload: BodyInit | undefined;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload });
  const text = await res.text();
  if (!res.ok) {
    let message = text || res.statusText;
    try {
      message = JSON.parse(text).error ?? message;
    } catch {
      /* düz metin hata */
    }
    throw new ApiError(res.status, message);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface ThreatUpdate {
  decision?: Decision;
  title?: string;
  description?: string;
  likelihood?: number;
  impact?: number;
  resetRisk?: boolean;
  rationale?: string;
  mitigations?: Mitigation[];
  note?: string;
}

export interface NewThreat {
  stride: Stride;
  targetId: string;
  title: string;
  description: string;
  likelihood?: number;
  impact?: number;
  mitigations?: Mitigation[];
  note?: string;
}

export const api = {
  meta: () => request<Meta>('GET', '/api/meta'),
  settings: () => request<SettingsResponse>('GET', '/api/settings'),
  saveSettings: (s: SystemSettings) => request<SettingsResponse>('PUT', '/api/settings', s),
  resetSettings: () => request<SettingsResponse>('POST', '/api/settings/reset'),
  testSettings: (target: 'llm' | 'embedding', s: SystemSettings) => request<SettingsTestResult[]>('POST', `/api/settings/test?target=${target}`, s),
  listModels: (task: string | null, s: SystemSettings) => request<ModelListResponse>('POST', `/api/settings/models${task ? `?task=${task}` : ''}`, s),
  settingsHistory: () => request<SettingChange[]>('GET', '/api/settings/history'),
  samples: () => request<Sample[]>('GET', '/api/samples'),
  sampleFile: async (name: string, file: string) => {
    const res = await fetch(`/api/samples/${name}/files/${encodeURIComponent(file)}`);
    return res.blob();
  },
  metrics: () => request<Record<string, unknown>>('GET', '/api/metrics'),
  rules: () => request<Record<string, any>>('GET', '/api/rules'),
  reloadRules: () => request<Record<string, any>>('POST', '/api/rules/reload'),

  reviews: () => request<ReviewSummary[]>('GET', '/api/reviews'),
  review: (id: string) => request<ReviewDetail>('GET', `/api/reviews/${id}`),
  createReview: (form: FormData) => request<Review>('POST', '/api/reviews', form),
  createFromSample: (name: string, llmMode: LlmMode, clientTexts?: Record<string, string>) =>
    request<Review>('POST', `/api/reviews/from-sample/${name}`, { llmMode, clientTexts }),
  documents: (id: string) => request<any[]>('GET', `/api/reviews/${id}/documents`),

  model: (id: string) => request<ModelView>('GET', `/api/reviews/${id}/model`),
  saveModel: (id: string, model: SystemModel) => request<ModelVersion>('PUT', `/api/reviews/${id}/model`, model),
  approveModel: (id: string) => request<ModelVersion>('POST', `/api/reviews/${id}/model/approve`),
  rejectModel: (id: string, note: string) => request<void>('POST', `/api/reviews/${id}/model/reject`, { note }),
  backToModel: (id: string) => request<ModelVersion>('POST', `/api/reviews/${id}/back-to-model`),

  candidates: (id: string) => request<ThreatCandidate[]>('GET', `/api/reviews/${id}/candidates`),
  retrieval: (id: string) => request<{ mode?: string; perCandidate?: Record<string, { id: string; score: number }[]>; chunks?: Record<string, KnowledgeChunk> }>('GET', `/api/reviews/${id}/retrieval`),
  threats: (id: string) => request<ThreatView[]>('GET', `/api/reviews/${id}/threats`),
  updateThreat: (id: string, threatId: string, u: ThreatUpdate) => request<ThreatView>('PATCH', `/api/reviews/${id}/threats/${threatId}`, u),
  addThreat: (id: string, t: NewThreat) => request<ThreatView>('POST', `/api/reviews/${id}/threats`, t),
  completeReview: (id: string) => request<void>('POST', `/api/reviews/${id}/expert-review/complete`),
  reopenReview: (id: string) => request<void>('POST', `/api/reviews/${id}/expert-review/reopen`),

  retry: (id: string) => request<void>('POST', `/api/reviews/${id}/retry`),
  setLlmMode: (id: string, mode: LlmMode) => request<Review>('PUT', `/api/reviews/${id}/llm-mode`, { mode }),

  report: (id: string) => request<Report>('GET', `/api/reviews/${id}/report`),
  evaluation: (id: string) => request<Evaluation>('GET', `/api/reviews/${id}/evaluation`),
  llmCalls: (id: string) => request<LlmCallView[]>('GET', `/api/reviews/${id}/llm-calls`),
  audit: (id: string) => request<AuditEvent[]>('GET', `/api/reviews/${id}/audit`),
  threatEvents: (id: string) => request<ThreatEvent[]>('GET', `/api/reviews/${id}/threat-events`),

  llmTasks: (reviewId: string) => request<LlmTask[]>('GET', `/api/llm-tasks?reviewId=${reviewId}`),
  submitLlmResult: (callId: string, output: string, model: string, durationMs: number) =>
    request<void>('POST', `/api/llm-tasks/${callId}/result`, { output, model, durationMs }),

  knowledgeSources: () => request<{ mode: string; counts: Record<string, number>; total: number; warnings: string[] }>('GET', '/api/knowledge/sources'),
  knowledgeSearch: (q: string, sources: string[], tags: string[], k = 8) => {
    const p = new URLSearchParams({ q, k: String(k) });
    sources.forEach((s) => p.append('sources', s));
    tags.forEach((t) => p.append('tags', t));
    return request<RetrievedChunk[]>('GET', `/api/knowledge/search?${p}`);
  },
  reloadKnowledge: () => request<unknown>('POST', '/api/knowledge/reload'),
};
