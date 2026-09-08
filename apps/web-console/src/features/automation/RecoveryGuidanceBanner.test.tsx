import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { RecoveryGuidanceBanner, StateBindingPanel } from './AutomationPage';

describe('RecoveryGuidanceBanner', () => {
  it('shows the authoritative directive and automatic handling state', () => {
    const html = renderToStaticMarkup(
      <RecoveryGuidanceBanner
        guidance={{
          directive: 'REFRESH',
          reasonCode: 'POST_ACTION_STATE_NOT_ADVANCED',
          automatic: true,
        }}
      />
    );

    expect(html).toContain('Why stuck / 下一步决策');
    expect(html).toContain('REFRESH');
    expect(html).toContain('POST_ACTION_STATE_NOT_ADVANCED');
    expect(html).toContain('控制面正在自动处理');
  });

  it('makes terminal decisions visibly non-automatic', () => {
    const html = renderToStaticMarkup(
      <RecoveryGuidanceBanner
        guidance={{
          directive: 'TERMINAL',
          reasonCode: 'PROMPT_INJECTION_SOURCE_FORBIDDEN',
          automatic: false,
        }}
      />
    );

    expect(html).toContain('bg-danger/5');
    expect(html).toContain('需要人工决策或显式重试');
  });
});

describe('StateBindingPanel', () => {
  it('shows server-clock age and page activity beside the state cursor', () => {
    const html = renderToStaticMarkup(
      <StateBindingPanel
        isLoading={false}
        state={{
          sessionId: 'ses_test',
          contextEpoch: 2,
          stateVersion: 9,
          targetRevision: 4,
          url: 'https://example.test/app',
          title: 'App',
          stateHash: 'a'.repeat(64),
          stateQuality: 'COMPLETE',
          documentReadyState: 'complete',
          networkQuietMillis: 2500,
          networkEvidenceFresh: true,
          observedAt: '2026-09-07T09:00:00Z',
          ageMillis: 1200,
          freshness: 'FRESH',
          pageActivity: 'STABLE',
          targets: [],
          tabs: [],
          activeTabId: '',
          nativeDialogs: [],
          nativeDialogEvidenceFresh: true,
        }}
      />
    );

    expect(html).toContain('FRESH');
    expect(html).toContain('STABLE');
    expect(html).toContain('1200ms old');
  });
});
