import { describe, expect, it } from 'vitest';
import { browserNodeQueryOptions, browserNodesKey } from './capacityQueries';

describe('Browser Node query delivery', () => {
  it('relies on the durable Workspace SSE cursor instead of a fixed polling interval', () => {
    expect(browserNodeQueryOptions.queryKey).toEqual(browserNodesKey);
    expect(browserNodeQueryOptions).not.toHaveProperty('refetchInterval');
  });
});
