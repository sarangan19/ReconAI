import { useEffect, useState } from 'react';
import { api, ReconBreak } from './api';

const CARD: React.CSSProperties = { background: '#1e2330', borderRadius: 10, border: '1px solid #2d3748', padding: '16px 20px' };
const LABEL: React.CSSProperties = { color: '#64748b', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em' };
const SEL: React.CSSProperties = {
  background: '#0f1117', border: '1px solid #374151', borderRadius: 6,
  color: '#e2e8f0', padding: '5px 10px', fontSize: 13,
};
const BTN: React.CSSProperties = {
  background: '#6366f1', border: 'none', borderRadius: 6, color: '#fff',
  padding: '5px 14px', fontSize: 12, cursor: 'pointer', fontWeight: 500,
};

const TYPE_COLORS: Record<string, string> = {
  DUP_EXTERNAL: '#f59e0b', MISSING_EXTERNAL: '#f87171', MISSING_INTERNAL: '#fb923c',
  AMT_FAT_FINGER: '#e879f9', AMT_FX_ROUNDING: '#34d399', DATE_TIMING: '#22d3ee',
  REF_CORRUPTION: '#a78bfa', SPLIT_SETTLEMENT: '#60a5fa',
};

const STATUS_COLORS: Record<string, string> = {
  OPEN: '#f87171', INVESTIGATING: '#f59e0b', RESOLUTION_PROPOSED: '#60a5fa',
  RESOLVED: '#34d399', ESCALATED: '#e879f9',
};

function Badge({ text, color }: { text: string; color?: string }) {
  return (
    <span style={{
      display: 'inline-block', padding: '2px 8px', borderRadius: 4,
      background: (color ?? '#6366f1') + '22', color: color ?? '#6366f1',
      fontSize: 11, fontWeight: 600, letterSpacing: '0.04em',
    }}>
      {text}
    </span>
  );
}

interface ContextData {
  break: Record<string, unknown>;
  transactions: unknown[];
  nearMissCandidates: unknown[];
  auditTrail: unknown[];
  agentVerdicts: unknown[];
}

function BreakDetail({ brk, onTransition }: {
  brk: ReconBreak;
  onTransition: (id: number, status: string) => Promise<void>;
}) {
  const [ctx, setCtx] = useState<ContextData | null>(null);
  const [loading, setLoading] = useState(true);
  const [transitioning, setTransitioning] = useState(false);

  useEffect(() => {
    api.getBreakContext(brk.id)
      .then(d => setCtx(d as unknown as ContextData))
      .catch(() => setCtx(null))
      .finally(() => setLoading(false));
  }, [brk.id]);

  const handleTransition = async (status: string) => {
    setTransitioning(true);
    try { await onTransition(brk.id, status); } finally { setTransitioning(false); }
  };

  const NEXT: Record<string, string[]> = {
    OPEN: ['INVESTIGATING', 'RESOLUTION_PROPOSED'],
    INVESTIGATING: ['RESOLUTION_PROPOSED', 'ESCALATED'],
    RESOLUTION_PROPOSED: ['RESOLVED', 'OPEN'],
    ESCALATED: ['INVESTIGATING', 'RESOLVED'],
    RESOLVED: [],
  };
  const nextStates = NEXT[brk.status] ?? [];

  return (
    <div style={{ display: 'grid', gap: 12 }}>
      <div style={CARD}>
        <div style={{ ...LABEL, marginBottom: 10 }}>Break {brk.id}</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 24px' }}>
          {[
            ['Type', <Badge text={brk.detectedType} color={TYPE_COLORS[brk.detectedType]} />],
            ['Status', <Badge text={brk.status} color={STATUS_COLORS[brk.status]} />],
            ['Confidence', `${(brk.detectedConfidence * 100).toFixed(0)}%`],
            ['Created', new Date(brk.createdAt).toLocaleDateString()],
          ].map(([k, v]) => (
            <div key={String(k)}>
              <div style={LABEL}>{String(k)}</div>
              <div style={{ marginTop: 2 }}>{v}</div>
            </div>
          ))}
        </div>
        {nextStates.length > 0 && (
          <div style={{ marginTop: 14, display: 'flex', gap: 8 }}>
            {nextStates.map(s => (
              <button key={s} style={{ ...BTN, opacity: transitioning ? 0.5 : 1 }}
                onClick={() => handleTransition(s)} disabled={transitioning}>
                → {s}
              </button>
            ))}
          </div>
        )}
      </div>

      {loading && <div style={{ color: '#64748b' }}>Loading context…</div>}

      {ctx && (
        <>
          <div style={CARD}>
            <div style={{ ...LABEL, marginBottom: 8 }}>Transactions ({(ctx.transactions as unknown[]).length})</div>
            <pre style={{ color: '#94a3b8', fontSize: 11, margin: 0, overflowX: 'auto' }}>
              {JSON.stringify(ctx.transactions, null, 2)}
            </pre>
          </div>

          {(ctx.nearMissCandidates as unknown[]).length > 0 && (
            <div style={CARD}>
              <div style={{ ...LABEL, marginBottom: 8 }}>Near-miss candidates</div>
              <pre style={{ color: '#94a3b8', fontSize: 11, margin: 0, overflowX: 'auto' }}>
                {JSON.stringify(ctx.nearMissCandidates, null, 2)}
              </pre>
            </div>
          )}

          {(ctx.agentVerdicts as unknown[]).length > 0 && (
            <div style={CARD}>
              <div style={{ ...LABEL, marginBottom: 8 }}>Agent verdicts</div>
              {(ctx.agentVerdicts as Record<string, unknown>[]).map((v, i) => (
                <div key={i} style={{ marginBottom: 8 }}>
                  <Badge text={String(v.rootCauseCode)} color="#6366f1" />
                  {' '}
                  <span style={{ color: '#64748b', fontSize: 12 }}>conf {String(v.confidence)}</span>
                  <div style={{ color: '#94a3b8', fontSize: 12, marginTop: 4 }}>{String(v.explanation)}</div>
                </div>
              ))}
            </div>
          )}

          {(ctx.auditTrail as unknown[]).length > 0 && (
            <div style={CARD}>
              <div style={{ ...LABEL, marginBottom: 8 }}>Audit trail</div>
              {(ctx.auditTrail as Record<string, unknown>[]).map((e, i) => (
                <div key={i} style={{ borderLeft: '2px solid #374151', paddingLeft: 12, marginBottom: 8 }}>
                  <span style={{ color: '#6366f1', fontSize: 11, fontWeight: 600 }}>{String(e.actor)}</span>
                  <span style={{ color: '#64748b', fontSize: 11 }}> · {String(e.action)}</span>
                  <div style={{ color: '#94a3b8', fontSize: 11 }}>
                    {new Date(String(e.createdAt)).toLocaleString()}
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export default function BreakQueue({ batchId }: { batchId: number }) {
  const [breaks, setBreaks] = useState<ReconBreak[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [selected, setSelected] = useState<ReconBreak | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);

  const load = (p: number) => {
    setLoading(true); setErr('');
    api.getBreaks(batchId, p, 50, filterType || undefined, filterStatus || undefined)
      .then(d => { setBreaks(d.content); setTotalPages(d.totalPages); setPage(d.number); })
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(0); }, [batchId, filterType, filterStatus]);

  const handleTransition = async (id: number, status: string) => {
    await api.transition(id, status);
    load(page);
    setSelected(b => b && b.id === id ? { ...b, status } : b);
  };

  const TYPES = ['', 'DUP_EXTERNAL', 'MISSING_EXTERNAL', 'MISSING_INTERNAL',
    'AMT_FAT_FINGER', 'AMT_FX_ROUNDING', 'DATE_TIMING', 'REF_CORRUPTION'];
  const STATUSES = ['', 'OPEN', 'INVESTIGATING', 'RESOLUTION_PROPOSED', 'RESOLVED', 'ESCALATED'];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: selected ? '1fr 420px' : '1fr', gap: 16, alignItems: 'start' }}>
      <div style={{ display: 'grid', gap: 12 }}>
        {/* Filters */}
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <select style={SEL} value={filterType} onChange={e => setFilterType(e.target.value)}>
            {TYPES.map(t => <option key={t} value={t}>{t || 'All types'}</option>)}
          </select>
          <select style={SEL} value={filterStatus} onChange={e => setFilterStatus(e.target.value)}>
            {STATUSES.map(s => <option key={s} value={s}>{s || 'All statuses'}</option>)}
          </select>
          {loading && <span style={{ color: '#64748b', fontSize: 12 }}>Loading…</span>}
          {err && <span style={{ color: '#f87171', fontSize: 12 }}>{err}</span>}
        </div>

        {/* Table */}
        <div style={{ ...CARD, padding: 0, overflow: 'hidden' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #2d3748' }}>
                {['ID', 'Type', 'Confidence', 'Status', 'Created'].map(h => (
                  <th key={h} style={{ ...LABEL, padding: '10px 16px', textAlign: 'left', fontWeight: 600 }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {breaks.map(b => (
                <tr key={b.id}
                  onClick={() => setSelected(s => s?.id === b.id ? null : b)}
                  style={{
                    borderBottom: '1px solid #1a1d27', cursor: 'pointer',
                    background: selected?.id === b.id ? '#252a3a' : 'transparent',
                  }}>
                  <td style={{ padding: '10px 16px', color: '#6366f1', fontWeight: 600 }}>{b.id}</td>
                  <td style={{ padding: '10px 16px' }}>
                    <Badge text={b.detectedType} color={TYPE_COLORS[b.detectedType]} />
                  </td>
                  <td style={{ padding: '10px 16px', color: '#94a3b8' }}>
                    {(b.detectedConfidence * 100).toFixed(0)}%
                  </td>
                  <td style={{ padding: '10px 16px' }}>
                    <Badge text={b.status} color={STATUS_COLORS[b.status]} />
                  </td>
                  <td style={{ padding: '10px 16px', color: '#64748b', fontSize: 12 }}>
                    {new Date(b.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
              {!loading && breaks.length === 0 && (
                <tr><td colSpan={5} style={{ padding: 24, textAlign: 'center', color: '#64748b' }}>
                  No breaks found.
                </td></tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button style={{ ...BTN, background: '#374151' }}
              onClick={() => load(page - 1)} disabled={page === 0}>Prev</button>
            <span style={{ color: '#64748b', fontSize: 12 }}>Page {page + 1} / {totalPages}</span>
            <button style={{ ...BTN, background: '#374151' }}
              onClick={() => load(page + 1)} disabled={page >= totalPages - 1}>Next</button>
          </div>
        )}
      </div>

      {selected && (
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <span style={{ color: '#64748b', fontSize: 12 }}>Break detail</span>
            <button onClick={() => setSelected(null)}
              style={{ background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', fontSize: 18, lineHeight: 1 }}>
              ×
            </button>
          </div>
          <BreakDetail brk={selected} onTransition={handleTransition} />
        </div>
      )}
    </div>
  );
}
