import { describe, expect, it } from 'vitest';
import { enterpriseOverviewQueryOptions } from './enterpriseQueries';

describe('Enterprise Overview query', () => {
  it('does not use a fixed refetch interval', () => {
    expect(enterpriseOverviewQueryOptions).not.toHaveProperty(
      'refetchInterval'
    );
  });
});
