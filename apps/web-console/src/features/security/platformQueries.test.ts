import { describe, expect, it } from 'vitest';
import {
  NOTIFICATION_DRIVEN_PLATFORM_KEYS,
  platformKeys,
} from './platformQueries';

describe('notification-driven platform query keys', () => {
  it('covers exactly the governance ledgers admitted by whole prefix', () => {
    expect(NOTIFICATION_DRIVEN_PLATFORM_KEYS.map((key) => key[0])).toEqual([
      'break-glass-requests',
      'key-rotation-requests',
      'secure-debug-sessions',
    ]);
  });

  it('never drives the full audit ledger from the notification cursor', () => {
    // The notification projection only carries high-signal audit rows. Adding the audit list
    // here would silently turn "stale for at most one interval" into "never refreshes" for
    // every event the projection drops.
    const driven = NOTIFICATION_DRIVEN_PLATFORM_KEYS.map((key) => key[0]);
    expect(driven).not.toContain(platformKeys.auditEvents[0]);
  });
});
