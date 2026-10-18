// Crimson menu theme. A theme is Origin's main menu colours re-tinted (cosmetics/lib/theme.mjs):
// the same tokens, contrast and alphas as the default, moved to a crimson accent on wine-
// charcoal surfaces. DISABLED goes neutral (off: 'neutral') so it never reads as the red accent.
// Preview: tools/cosmetics/theme-preview.mjs.
import { themeTokens } from '../../lib/theme.mjs';

const SPEC = { accent: '#e5545c', surface: '#261b1e', text: '#f3e9e3', off: 'neutral' };

export default {
  meta: { id: 'theme-crimson', name: 'Crimson', kind: 'theme', cents: 599, blurb: 'Wine-dark panels, warm cream type and a vivid crimson accent.' },
  theme: themeTokens(SPEC),
};
