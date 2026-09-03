import { describe, expect, it } from 'vitest';
import { desktopQualityLevel } from './desktopQuality';

describe('viewer quality negotiation', () => {
  it('uses standard RFB quality hints with an explicit lossless option', () => {
    expect(desktopQualityLevel).toEqual({ SMOOTH: 2, BALANCED: 6, SHARP: 9 });
  });
});
