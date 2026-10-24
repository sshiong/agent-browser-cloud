import { zodResolver } from '@hookform/resolvers/zod';
import { describe, expect, it } from 'vitest';
import { schema, type FormInputs } from './createSessionForm';

const input: FormInputs = {
  name: ' Environment ',
  groupId: '',
  tagIds: [],
  description: '',
  accent: 'teal',
  runtimeBuildId: 'runtime-test',
  applicationId: '',
  profileMode: 'empty',
  profileId: '',
  networkMode: 'managed',
  proxyBindingProfileId: '',
  region: 'local',
  executionEnvironment: 'SYSTEM_MANAGED',
  onMaximumReached: 'PAUSE_AGENT',
  strictBudgetConfirmed: false,
  minimumTemplate: 'standard-v1',
  maximumCpuMillis: '4000',
  maximumMemoryMib: '4096',
  maximumCostPerHour: '',
  allowMigration: true,
  allowHibernate: true,
  blockMigrationDuringHumanTakeover: true,
  adjustmentCooldownSeconds: '300',
  scaleDownWindowSeconds: '1200',
  requestedTabs: '4',
  agentActionsPerMinute: '60',
  remoteDesktop: false,
  mediaClass: 'M0',
  videoRecording: false,
  extensionIds: [],
  agentEnabled: true,
  agentPolicy: 'balanced',
  humanTakeover: true,
  idleTimeoutMinutes: '30',
  snapshotPolicy: 'on-stop',
  web3Workload: false,
};

const resolve = (value: FormInputs) =>
  zodResolver(schema)(value, undefined, {
    fields: {},
    shouldUseNativeValidation: false,
  });

describe('create session Zod resolver', () => {
  it('parses DOM numeric strings while preserving optional empty cost', async () => {
    const result = await resolve(input);
    expect(result.errors).toEqual({});
    expect(result.values).toMatchObject({
      name: 'Environment',
      maximumCpuMillis: 4000,
      requestedTabs: 4,
      idleTimeoutMinutes: 30,
      maximumCostPerHour: '',
    });
    expect(
      (await resolve({ ...input, maximumCostPerHour: '0.25' })).values
    ).toMatchObject({ maximumCostPerHour: 0.25 });
  });

  it('keeps field errors and strict termination confirmation', async () => {
    const result = await resolve({
      ...input,
      maximumCpuMillis: '1',
      profileMode: 'existing',
      onMaximumReached: 'TERMINATE_STRICT',
    });
    expect(result.values).toEqual({});
    expect(result.errors.maximumCpuMillis).toBeDefined();
    expect(result.errors.profileId?.message).toBe('请选择 Profile');
    expect(result.errors.strictBudgetConfirmed?.message).toBe(
      '请确认严格预算可能终止环境并中断登录状态'
    );
  });

  it('rejects oversized arrays before reading their elements', async () => {
    const extensionIds = Array<string>(33);
    Object.defineProperty(extensionIds, 0, {
      get() {
        throw new Error('ELEMENT_WALK');
      },
    });
    const result = await resolve({ ...input, extensionIds });
    expect(result.values).toEqual({});
    expect(result.errors.extensionIds).toBeDefined();
  });
});
