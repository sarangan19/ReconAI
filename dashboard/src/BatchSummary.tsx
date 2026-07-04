import { useEffect, useState } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell,
} from 'recharts';
import { api, BatchSummary as BS, PassStat } from './api';

const PASS_COLORS = ['#6366f1', '#22d3ee', '#34d399', '#f59e0b'];
const CARD_STYLE: React.CSSProperties = {
  background: '#1e2330', borderRadius: 10, border: '1px solid #2d3748', padding: '16px 20px',
};
const LABEL_STYLE: React.CSSProperties = { color: '#64748b', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em' };
const VALUE_STYLE: React.CSSProperties = { fontSize: '1.75rem', fontWeight: 700, marginTop: 4 };
const GRID: React.CSSProperties = { display: 'grid', gap: 16 };

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div style={CARD_STYLE}>
      <div style={LABEL_STYLE}>{label}</div>
      <div style={VALUE_STYLE}>{value}</div>
      {sub && <div style={{ color: '#64748b', fontSize: 12, marginTop: 2 }}>{sub}</div>}
    </div>
  );
}

export default function BatchSummary({ batchId }: { batchId: number }) {
  const [batch, setBatch] = useState<BS | null>(null);
  const [passes, setPasses] = useState<PassStat[]>([]);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true); setErr('');
    Promise.all([api.getBatch(batchId), api.getPassStats(batchId)])
      .then(([b, ps]) => { setBatch(b); setPasses(ps); })
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false));
  }, [batchId]);

  if (loading) return <div style={{ color: '#64748b', paddingTop: 48, textAlign: 'center' }}>Loading batch {batchId}…</div>;
  if (err) return <div style={{ color: '#f87171', paddingTop: 48, textAlign: 'center' }}>{err}</div>;
  if (!batch) return null;

  const matchRate = (batch.matchRate * 100).toFixed(1);
  const passData = passes.map((p, i) => ({
    name: p.passName ?? `Pass ${p.passNumber}`,
    matched: p.matchedCount,
    color: PASS_COLORS[i % PASS_COLORS.length],
  }));

  return (
    <div style={GRID}>
      {/* Stat cards */}
      <div style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))' }}>
        <StatCard label="Total Transactions" value={batch.totalTransactions.toLocaleString()} />
        <StatCard label="Matched" value={batch.matchedCount.toLocaleString()} sub={`${matchRate}% match rate`} />
        <StatCard label="Breaks" value={batch.breakCount.toLocaleString()} />
        <StatCard label="Status" value={batch.status} />
      </div>

      {/* Pass breakdown */}
      <div style={CARD_STYLE}>
        <div style={{ ...LABEL_STYLE, marginBottom: 16 }}>Matches per pass</div>
        {passData.length === 0
          ? <div style={{ color: '#64748b' }}>No pass stats yet — run reconciliation first.</div>
          : (
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={passData} margin={{ top: 4, right: 8, left: 8, bottom: 4 }}>
                <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 12 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fill: '#94a3b8', fontSize: 12 }} axisLine={false} tickLine={false} />
                <Tooltip
                  contentStyle={{ background: '#1a1d27', border: '1px solid #374151', borderRadius: 8 }}
                  labelStyle={{ color: '#e2e8f0' }}
                  itemStyle={{ color: '#94a3b8' }}
                />
                <Bar dataKey="matched" radius={[4, 4, 0, 0]}>
                  {passData.map((p, i) => <Cell key={i} fill={p.color} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          )}
      </div>

      {/* Batch metadata */}
      <div style={CARD_STYLE}>
        <div style={{ ...LABEL_STYLE, marginBottom: 12 }}>Batch details</div>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <tbody>
            {[
              ['Batch ID', batchId],
              ['Internal txns', batch.internalCount.toLocaleString()],
              ['External txns', batch.externalCount.toLocaleString()],
              ['Break count', batch.breakCount.toLocaleString()],
              ['Created', new Date(batch.createdAt).toLocaleString()],
            ].map(([k, v]) => (
              <tr key={String(k)}>
                <td style={{ color: '#64748b', padding: '5px 0', width: 180 }}>{k}</td>
                <td style={{ color: '#e2e8f0', padding: '5px 0' }}>{String(v)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
