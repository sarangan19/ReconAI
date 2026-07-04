const BASE = '/api';

async function get<T>(path: string): Promise<T> {
  const r = await fetch(BASE + path);
  if (!r.ok) throw new Error(`GET ${path} → ${r.status}`);
  return r.json();
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const r = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`POST ${path} → ${r.status}`);
  return r.json();
}

export interface BatchSummary {
  batchId: number;
  totalTransactions: number;
  internalCount: number;
  externalCount: number;
  matchedCount: number;
  breakCount: number;
  matchRate: number;
  status: string;
  createdAt: string;
}

export interface PassStat {
  passNumber: number;
  passName: string;
  matchedCount: number;
  groupsCreated: number;
}

export interface ReconBreak {
  id: number;
  batchId: number;
  detectedType: string;
  detectedConfidence: number;
  status: string;
  resolvedAt?: string;
  resolutionCode?: string;
  createdAt: string;
}

export interface PagedBreaks {
  content: ReconBreak[];
  totalElements: number;
  totalPages: number;
  last: boolean;
  number: number;
}

export interface EvalSummary {
  batchId: number;
  totalEvaluated: number;
  correct: number;
  accuracy: number;
  perType: Record<string, { support: number; precision: number; recall: number; f1: number }>;
  confusionMatrix: Record<string, Record<string, number>>;
  labels: string[];
  message?: string;
}

export const api = {
  getBatch: (id: number) => get<BatchSummary>(`/batches/${id}`),
  getPassStats: (batchId: number) => get<PassStat[]>(`/batches/${batchId}/pass-stats`),
  getBreaks: (batchId: number, page = 0, size = 50, type?: string, status?: string) => {
    const params = new URLSearchParams({ batchId: String(batchId), page: String(page), size: String(size) });
    if (type) params.set('type', type);
    if (status) params.set('status', status);
    return get<PagedBreaks>(`/breaks?${params}`);
  },
  getBreakContext: (id: number) => get<Record<string, unknown>>(`/breaks/${id}/context`),
  transition: (id: number, targetStatus: string, actor = 'USER', note = '') =>
    post<ReconBreak>(`/breaks/${id}/transition`, { targetStatus, actor, note }),
  evalSummary: (batchId: number) => get<EvalSummary>(`/eval/summary?batchId=${batchId}`),
};
