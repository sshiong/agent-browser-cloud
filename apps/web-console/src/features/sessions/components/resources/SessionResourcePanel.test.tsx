import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SessionResourcePanel } from './SessionResourcePanel';
import type { SessionResourceView } from '@/types/session';

describe('resource panel missing pricing', () => {
  it.each([null, undefined])(
    'renders unavailable cost %s without crashing the detail route',
    (cost) => {
      const resource = {
        sessionId: 'ses-null-pricing',
        policy: {
          resolvedTemplate: 'standard-v1',
          executionEnvironment: 'SYSTEM_MANAGED',
        },
        status: 'OBSERVING',
        dataFreshness: 'AWAITING_TELEMETRY',
        usageSamples: [],
        cost: { currentHourlyCost: cost, maximumHourlyCost: cost, trend: [] },
      } as unknown as SessionResourceView;
      const html = renderToStaticMarkup(
        <SessionResourcePanel
          resource={resource}
          events={[]}
          streamState="LIVE"
          loading={false}
          error={null}
          safePointError={null}
          canAdminister={false}
          platformAdmin={false}
          humanTakeover={false}
          updating={false}
          updateError={null}
          onRetry={() => {}}
          onUpdate={async () => {}}
        />
      );
      expect(html).toContain('自动资源策略');
      expect(html).toContain('—');
      expect(html).not.toContain('上限 $');
    }
  );
});
