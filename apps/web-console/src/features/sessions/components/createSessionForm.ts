import { z } from 'zod';

export const schema = z
  .object({
    name: z.string().trim().min(1, '请输入环境名称').max(128),
    groupId: z.string().trim().max(36),
    tagIds: z.array(z.string().max(36)).max(16),
    description: z.string().trim().max(500),
    accent: z.enum(['teal', 'blue', 'amber', 'violet']),
    runtimeBuildId: z.string().trim().min(1, '请选择 Runtime Build'),
    applicationId: z.string().trim().max(128),
    profileMode: z.enum(['empty', 'existing', 'checkpoint']),
    profileId: z.string().trim().max(128),
    networkMode: z.enum(['managed', 'direct']),
    proxyBindingProfileId: z.string().trim().max(38),
    region: z.string().trim().min(1, '请选择部署区域').max(32),
    executionEnvironment: z.enum([
      'SYSTEM_MANAGED',
      'CONTAINER',
      'ENHANCED_SANDBOX',
      'MICROVM',
      'NATIVE_OS',
    ]),
    onMaximumReached: z.enum([
      'PAUSE_AGENT',
      'WAIT_SAFE_POINT_MIGRATE',
      'HIBERNATE',
      'TERMINATE_STRICT',
    ]),
    strictBudgetConfirmed: z.boolean(),
    minimumTemplate: z.enum([
      'standard-v1',
      'interactive-v1',
      'heavy-v1',
      'native-standard-v1',
    ]),
    maximumCpuMillis: z.coerce.number().int().min(500).max(32000),
    maximumMemoryMib: z.coerce.number().int().min(512).max(131072),
    maximumCostPerHour: z.union([
      z.literal(''),
      z.coerce.number().positive().max(10000),
    ]),
    allowMigration: z.boolean(),
    allowHibernate: z.boolean(),
    blockMigrationDuringHumanTakeover: z.boolean(),
    adjustmentCooldownSeconds: z.coerce.number().int().min(60).max(3600),
    scaleDownWindowSeconds: z.coerce.number().int().min(300).max(86400),
    requestedTabs: z.coerce.number().int().min(1).max(64),
    agentActionsPerMinute: z.coerce.number().int().min(0).max(600),
    remoteDesktop: z.boolean(),
    mediaClass: z.enum(['M0', 'M1', 'M2', 'M3', 'M4']),
    videoRecording: z.boolean(),
    extensionIds: z.array(z.string().max(128)).max(32),
    agentEnabled: z.boolean(),
    agentPolicy: z.enum(['balanced', 'restricted', 'interactive']),
    humanTakeover: z.boolean(),
    idleTimeoutMinutes: z.coerce.number().int().min(5).max(1440),
    snapshotPolicy: z.enum(['on-stop', 'periodic', 'manual']),
    web3Workload: z.boolean(),
  })
  .superRefine((value, context) => {
    if (value.profileMode !== 'empty' && !value.profileId) {
      context.addIssue({
        code: 'custom',
        path: ['profileId'],
        message: '请选择 Profile',
      });
    }
    if (
      value.onMaximumReached === 'TERMINATE_STRICT' &&
      !value.strictBudgetConfirmed
    ) {
      context.addIssue({
        code: 'custom',
        path: ['strictBudgetConfirmed'],
        message: '请确认严格预算可能终止环境并中断登录状态',
      });
    }
  });

export type FormValues = z.infer<typeof schema>;
export type FormInputs = z.input<typeof schema>;
