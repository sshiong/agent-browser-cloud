import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listSessions } from '../api/session';

const stateColors: Record<string, string> = {
  CREATED: '#6b7280',
  STARTING: '#f59e0b',
  RUNNING: '#10b981',
  DEGRADED: '#f97316',
  TERMINATING: '#ef4444',
  TERMINATED: '#6b7280',
  FAILED: '#ef4444',
};

export function SessionsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['sessions'],
    queryFn: () => listSessions(),
  });

  if (isLoading) {
    return <div style={{ padding: 24 }}>Loading sessions…</div>;
  }

  if (error) {
    return (
      <div style={{ padding: 24, color: 'red' }}>Error: {error.message}</div>
    );
  }

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}
      >
        <h1 style={{ fontSize: 24, margin: 0 }}>Sessions</h1>
        <button
          style={{
            padding: '8px 16px',
            background: '#3b82f6',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
          }}
        >
          + Create Session
        </button>
      </div>

      {data?.items.length === 0 ? (
        <div style={{ padding: 48, textAlign: 'center', color: '#9ca3af' }}>
          No sessions found. Create one to get started.
        </div>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>
                Session ID
              </th>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>Tenant</th>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>State</th>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>Node</th>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>Epoch</th>
              <th style={{ textAlign: 'left', padding: '12px 8px' }}>
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {data?.items.map((session) => (
              <tr
                key={session.sessionId}
                style={{ borderBottom: '1px solid #f3f4f6' }}
              >
                <td
                  style={{
                    padding: '12px 8px',
                    fontFamily: 'monospace',
                    fontSize: 14,
                  }}
                >
                  <Link
                    to={`/sessions/${session.sessionId}`}
                    style={{ color: '#3b82f6', textDecoration: 'none' }}
                  >
                    {session.sessionId}
                  </Link>
                </td>
                <td style={{ padding: '12px 8px' }}>{session.tenantId}</td>
                <td style={{ padding: '12px 8px' }}>
                  <span
                    style={{
                      display: 'inline-block',
                      padding: '2px 8px',
                      borderRadius: 12,
                      fontSize: 12,
                      fontWeight: 500,
                      background: stateColors[session.state] + '20',
                      color: stateColors[session.state],
                    }}
                  >
                    {session.state}
                  </span>
                </td>
                <td
                  style={{
                    padding: '12px 8px',
                    fontFamily: 'monospace',
                    fontSize: 13,
                    color: '#6b7280',
                  }}
                >
                  {session.nodeId || '—'}
                </td>
                <td style={{ padding: '12px 8px', color: '#6b7280' }}>
                  {session.contextEpoch}
                </td>
                <td style={{ padding: '12px 8px' }}>
                  <Link
                    to={`/sessions/${session.sessionId}`}
                    style={{
                      color: '#3b82f6',
                      textDecoration: 'none',
                      fontSize: 14,
                    }}
                  >
                    View →
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
