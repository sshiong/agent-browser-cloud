export type DesktopQuality = 'SMOOTH' | 'BALANCED' | 'SHARP';

/** Standard RFB quality hints; 9 requests an exact Raw baseline from our gateway. */
export const desktopQualityLevel: Record<DesktopQuality, number> = {
  SMOOTH: 2,
  BALANCED: 6,
  SHARP: 9,
};
