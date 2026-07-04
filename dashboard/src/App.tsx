export default function App() {
  return (
    <div style={{
      minHeight: '100vh',
      background: '#0f1117',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      fontFamily: 'system-ui, sans-serif',
      color: '#e2e8f0',
    }}>
      <h1 style={{ fontSize: '3rem', fontWeight: 700, margin: 0, letterSpacing: '-0.02em' }}>
        Recon<span style={{ color: '#6366f1' }}>AI</span>
      </h1>
      <p style={{ color: '#64748b', marginTop: '0.75rem', fontSize: '1.1rem' }}>
        Transaction reconciliation platform — dashboard coming in Phase 7
      </p>
      <div style={{
        marginTop: '2rem',
        padding: '1rem 1.5rem',
        background: '#1e2330',
        borderRadius: '8px',
        border: '1px solid #2d3748',
        fontSize: '0.875rem',
        color: '#94a3b8',
      }}>
        Engine API: <a href="http://localhost:8080/swagger-ui.html"
          style={{ color: '#6366f1' }}>http://localhost:8080/swagger-ui.html</a>
      </div>
    </div>
  )
}
