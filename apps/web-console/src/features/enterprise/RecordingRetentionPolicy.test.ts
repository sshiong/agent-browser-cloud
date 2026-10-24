import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { RecordingRetentionPolicy } from './RecordingRetentionPolicy';
import type { RetentionPolicyView } from '@/types/enterprise';

const mutation = vi.hoisted(() => ({
  isPending: false,
  isSuccess: false,
  isError: false,
  error: new Error('owned'),
  mutate: vi.fn(),
  reset: vi.fn(),
}));
vi.mock('./enterpriseQueries', () => ({
  useUpsertRetentionPolicy: () => mutation,
}));

const policy: RetentionPolicyView = {
  tenantId: 'owned',
  dataClass: 'REMOTE_DESKTOP_RECORDING',
  retentionDays: 31,
  legalHold: true,
  residencyRegion: 'primary',
  updatedBy: 'owned',
  updatedAt: '2026-10-23T00:00:00Z',
  cloudHoldRequired: true,
  recordingCloudHold: {
    status: 'PENDING',
    desiredMode: 'ON',
    pendingRecordings: 1,
    unknownRecordings: 0,
    verifiedOnRecordings: 0,
    verifiedOffRecordings: 0,
    reconciliationExhausted: false,
  },
};

function render(input = policy, canManage = true) {
  return renderToStaticMarkup(
    createElement(RecordingRetentionPolicy, { policy: input, canManage })
  );
}

describe('Recording protection evidence UI', () => {
  it('saving a desired policy cannot render an applied cloud success', () => {
    mutation.isSuccess = true;
    try {
      const html = render();
      expect(html).toContain('等待云端回执');
      expect(html).toContain('策略已保存');
      expect(html).not.toContain('云端保全已验证');
      expect(html).toMatch(/<input[^>]*disabled=""[^>]*checked=""/);
    } finally {
      mutation.isSuccess = false;
    }
  });

  it('unknown exhausted results remain protected and distinct from verified evidence', () => {
    const html = render({
      ...policy,
      recordingCloudHold: {
        ...policy.recordingCloudHold!,
        status: 'UNKNOWN',
        pendingRecordings: 0,
        unknownRecordings: 1,
        reconciliationExhausted: true,
      },
    });
    expect(html).toContain('云端结果未知');
    expect(html).toContain('保全仍保留');
    expect(html).not.toContain('云端保全已验证');
    expect(html).toContain('请先解除期望保全');
  });

  it('old servers cannot acquire cloud evidence from a logical hold flag', () => {
    const html = render({
      ...policy,
      cloudHoldRequired: undefined,
      recordingCloudHold: undefined,
    });
    expect(html).toContain('未取得云端证明');
    expect(html).toContain('请先升级服务端');
    expect(html).not.toContain('云端保全已验证');
  });

  it('read-only viewers see proof status but receive no mutation controls', () => {
    const html = render(
      {
        ...policy,
        recordingCloudHold: {
          ...policy.recordingCloudHold!,
          status: 'VERIFIED',
          pendingRecordings: 0,
          verifiedOnRecordings: 1,
        },
      },
      false
    );
    expect(html).toContain('云端保全已验证（托管录制）');
    expect(html).not.toContain('<form');
  });
});
