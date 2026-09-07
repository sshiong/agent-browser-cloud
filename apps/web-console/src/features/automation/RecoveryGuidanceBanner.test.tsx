import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { RecoveryGuidanceBanner } from './AutomationPage';

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
