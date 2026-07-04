import { useState } from 'react';
import BatchSummary from './BatchSummary';
import BreakQueue from './BreakQueue';
import AgentReport from './AgentReport';

const TABS = ['Batch Summary', 'Break Queue', 'Agent Report'] as const;
type Tab = typeof TABS[number];

const S = {
  app: {
    minHeight: '100vh', background: '#0f1117', color: '#e2e8f0',
    fontFamily: 'system-ui, sans-serif', fontSize: '14px',
  } as React.CSSProperties,
  header: {
    background: '#1a1d27', borderBottom: '1px solid #2d3748',
    padding: '0 24px', display: 'flex', alignItems: 'center', gap: 32, height: 52,
  } as React.CSSProperties,
  logo: { fontSize: '1.25rem', fontWeight: 700, letterSpacing: '-0.02em', margin: 0 } as React.CSSProperties,
  tabs: { display: 'flex', gap: 4, flex: 1 } as React.CSSProperties,
  batchRow: { display: 'flex', alignItems: 'center', gap: 8 } as React.CSSProperties,
  batchInput: {
    background: '#0f1117', border: '1px solid #374151', borderRadius: 6,
    color: '#e2e8f0', padding: '4px 8px', width: 80, fontSize: 13,
  } as React.CSSProperties,
  content: { padding: 24 } as React.CSSProperties,
};

function TabBtn({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} style={{
      background: 'none', border: 'none', cursor: 'pointer',
      padding: '0 16px', height: 52, fontSize: 13, fontWeight: active ? 600 : 400,
      color: active ? '#6366f1' : '#94a3b8',
      borderBottom: active ? '2px solid #6366f1' : '2px solid transparent',
    }}>
      {label}
    </button>
  );
}

export default function App() {
  const [tab, setTab] = useState<Tab>('Batch Summary');
  const [batchId, setBatchId] = useState(105);
  const [inputVal, setInputVal] = useState('105');

  const handleBatchChange = (v: string) => {
    setInputVal(v);
    const n = parseInt(v, 10);
    if (!isNaN(n) && n > 0) setBatchId(n);
  };

  return (
    <div style={S.app}>
      <header style={S.header}>
        <h1 style={S.logo}>Recon<span style={{ color: '#6366f1' }}>AI</span></h1>
        <nav style={S.tabs}>
          {TABS.map(t => <TabBtn key={t} label={t} active={tab === t} onClick={() => setTab(t)} />)}
        </nav>
        <div style={S.batchRow}>
          <span style={{ color: '#64748b', fontSize: 12 }}>Batch ID</span>
          <input style={S.batchInput} value={inputVal}
            onChange={e => handleBatchChange(e.target.value)} type="number" min={1} />
        </div>
      </header>
      <main style={S.content}>
        {tab === 'Batch Summary' && <BatchSummary batchId={batchId} />}
        {tab === 'Break Queue'   && <BreakQueue   batchId={batchId} />}
        {tab === 'Agent Report'  && <AgentReport  batchId={batchId} />}
      </main>
    </div>
  );
}
