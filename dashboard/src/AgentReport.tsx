import { useEffect, useState } from 'react';
import { api, EvalSummary } from './api';

const CARD: React.CSSProperties = { background: '#1e2330', borderRadius: 10, border: '1px solid #2d3748', padding: '16px 20px' };
const LABEL: React.CSSProperties = { color: '#64748b', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em' };

function pct(v: number) { return (v * 100).toFixed(1) + '%'; }

function HeatCell({ value, max }: { value: number; max: number }) {
  const intensity = max > 0 ? value / max : 0;
  const bg = intensity > 0
    ? `rgba(99, 102, 241, ${0.15 + intensity * 0.75})`
    : 'transparent';
  return (
    <td style={{
      padding: '6px 12px', textAlign: 'center', background: bg,
      color: intensity > 0.5 ? '#fff' : '#94a3b8', fontWeight: intensity > 0 ? 600 : 400,
      fontSize: 12, border: '1px solid #1a1d27',
    }}>
      {value > 0 ? value : '—'}
    </td>
  );
}

export default function AgentReport({ batchId }: { batchId: number }) {
  const [data, setData] = useState<EvalSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  useEffect(() => {
    setLoading(true); setErr('');
    api.evalSummary(batchId)
      .then(setData)
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false));
  }, [batchId]);

  if (loading) return <div style={{ color: '#64748b', paddingTop: 48, textAlign: 'center' }}>Loading eval data…</div>;
  if (err) return <div style={{ color: '#f87171', paddingTop: 48, textAlign: 'center' }}>{err}</div>;
  if (!data) return null;

  if (data.totalEvaluated === 0) {
    return (
      <div style={{ ...CARD, color: '#94a3b8', textAlign: 'center', padding: 48 }}>
        <div style={{ fontSize: '2rem', marginBottom: 12 }}>No eval data yet</div>
        <div style={{ color: '#64748b' }}>
          Run: <code style={{ color: '#6366f1' }}>python -m reconai.eval --batch-id {batchId} --mode rules --out-dir benchmarks --verbose</code>
          <br />then post verdicts with <code style={{ color: '#6366f1' }}>--post-verdict</code> flag (add to runner) to populate this view.
        </div>
      </div>
    );
  }

  const labels = data.labels ?? Object.keys(data.confusionMatrix ?? {});
  const maxCell = Math.max(...labels.flatMap(r => labels.map(c => data.confusionMatrix[r]?.[c] ?? 0)));

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {/* Accuracy hero */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 16 }}>
        <div style={CARD}>
          <div style={LABEL}>Accuracy</div>
          <div style={{ fontSize: '2.5rem', fontWeight: 700, color: '#34d399', marginTop: 4 }}>
            {pct(data.accuracy)}
          </div>
          <div style={{ color: '#64748b', fontSize: 12, marginTop: 2 }}>
            {data.correct} / {data.totalEvaluated} correct
          </div>
        </div>
        <div style={CARD}>
          <div style={LABEL}>Total evaluated</div>
          <div style={{ fontSize: '2rem', fontWeight: 700, marginTop: 4 }}>{data.totalEvaluated}</div>
        </div>
        <div style={CARD}>
          <div style={LABEL}>Break types</div>
          <div style={{ fontSize: '2rem', fontWeight: 700, marginTop: 4 }}>{labels.length}</div>
        </div>
      </div>

      {/* Per-type table */}
      <div style={CARD}>
        <div style={{ ...LABEL, marginBottom: 12 }}>Per-type breakdown</div>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid #374151' }}>
              {['Type', 'Support', 'Precision', 'Recall', 'F1'].map(h => (
                <th key={h} style={{ ...LABEL, padding: '8px 12px', textAlign: h === 'Type' ? 'left' : 'right' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {labels.map(code => {
              const m = data.perType[code] ?? { support: 0, precision: 0, recall: 0, f1: 0 };
              return (
                <tr key={code} style={{ borderBottom: '1px solid #1a1d27' }}>
                  <td style={{ padding: '8px 12px', fontWeight: 600 }}>{code}</td>
                  <td style={{ padding: '8px 12px', textAlign: 'right', color: '#94a3b8' }}>{m.support}</td>
                  <td style={{ padding: '8px 12px', textAlign: 'right', color: m.precision >= 0.7 ? '#34d399' : '#f87171' }}>
                    {pct(m.precision)}
                  </td>
                  <td style={{ padding: '8px 12px', textAlign: 'right', color: m.recall >= 0.7 ? '#34d399' : '#f87171' }}>
                    {pct(m.recall)}
                  </td>
                  <td style={{ padding: '8px 12px', textAlign: 'right', fontWeight: 600,
                    color: m.f1 >= 0.7 ? '#34d399' : m.f1 >= 0.5 ? '#f59e0b' : '#f87171' }}>
                    {pct(m.f1)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Confusion matrix */}
      {data.confusionMatrix && labels.length > 0 && (
        <div style={CARD}>
          <div style={{ ...LABEL, marginBottom: 12 }}>Confusion matrix (rows = actual, cols = predicted)</div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', fontSize: 11 }}>
              <thead>
                <tr>
                  <th style={{ padding: '6px 12px', color: '#64748b', textAlign: 'left' }}>Actual \\ Pred</th>
                  {labels.map(c => (
                    <th key={c} style={{ padding: '6px 12px', color: '#94a3b8', textAlign: 'center', fontSize: 10 }}>
                      {c.replace(/_/g, '_​')}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {labels.map(row => (
                  <tr key={row}>
                    <td style={{ padding: '6px 12px', color: '#94a3b8', fontWeight: 500, fontSize: 10, whiteSpace: 'nowrap' }}>
                      {row}
                    </td>
                    {labels.map(col => (
                      <HeatCell key={col} value={data.confusionMatrix[row]?.[col] ?? 0} max={maxCell} />
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
