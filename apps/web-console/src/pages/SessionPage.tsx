import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getSession } from '../api/session';

export function SessionPage() {
  const { sessionId = '' } = useParams();
  const { data, error, isLoading } = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => getSession(sessionId),
    enabled: !!sessionId,
  });

  if (isLoading) {
    return <div style={{ padding: 24 }}>Loading session…</div>;
  }

  if (error || !data) {
    return (
      <div style={{ padding: 24 }}>
        <p style={{ color: 'red' }}>Unable to load session.</p>
        <Link to="/">← Back to sessions</Link>
      </div>
    );
  }

  return (
    <div>
      <Link
        to="/"
        style={{ color: '#6b7280', textDecoration: 'none', fontSize: 14 }}
      >
        ← Back to sessions
      </Link>

      <h1 style={{ fontSize: 24, margin: '16px 0' }}>
        <span style={{ fontFamily: 'monospace' }}>{data.sessionId}</span>
      </h1>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
        <section
          style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: 20 }}
        >
          <h2 style={{ fontSize: 16, margin: '0 0 16px', color: '#374151' }}>
            Status
          </h2>
          <dl style={{ margin: 0 }}>
            <DetailItem label="State" value={data.state} />
            <DetailItem label="Tenant" value={data.tenantId} />
            <DetailItem label="Node" value={data.nodeId || '—'} />
            <DetailItem
              label="Runtime Build"
              value={data.runtimeBuildId || '—'}
            />
            <DetailItem
              label="Context Epoch"
              value={String(data.contextEpoch)}
            />
            <DetailItem
              label="Browser Generation"
              value={String(data.browserGeneration)}
            />
          </dl>
        </section>

        {data.currentOperation ? (
          <section
            style={{
              border: '1px solid #e5e7eb',
              borderRadius: 8,
              padding: 20,
            }}
          >
            <h2 style={{ fontSize: 16, margin: '0 0 16px', color: '#374151' }}>
              Current Operation
            </h2>
            <dl style={{ margin: 0 }}>
              <DetailItem
                label="Operation ID"
                value={data.currentOperation.operationId}
              />
              <DetailItem label="Mode" value={data.currentOperation.mode} />
              <DetailItem
                label="Owner"
                value={data.currentOperation.ownerType}
              />
              <DetailItem label="Phase" value={data.currentOperation.phase} />
              <DetailItem label="State" value={data.currentOperation.state} />
              <DetailItem
                label="Priority"
                value={String(data.currentOperation.priority)}
              />
            </dl>
          </section>
        ) : (
          <section
            style={{
              border: '1px solid #e5e7eb',
              borderRadius: 8,
              padding: 20,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#9ca3af',
            }}
          >
            No active operation
          </section>
        )}
      </div>
    </div>
  );
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        padding: '8px 0',
        borderBottom: '1px solid #f3f4f6',
      }}
    >
      <dt style={{ color: '#6b7280', fontSize: 14 }}>{label}</dt>
      <dd style={{ margin: 0, fontFamily: 'monospace', fontSize: 14 }}>
        {value}
      </dd>
    </div>
  );
}
