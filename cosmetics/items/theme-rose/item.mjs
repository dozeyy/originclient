// Rose menu theme. A theme is Origin's main menu colours re-tinted (cosmetics/lib/theme.mjs):
// the same tokens, contrast and alphas as the default, moved to a rose-pink accent on plum-
// charcoal surfaces with a blush type. DISABLED goes neutral (off: 'neutral') so it never reads
// as the pink accent. Preview: tools/cosmetics/theme-preview.mjs.
import { themeTokens } from '../../lib/theme.mjs';

const SPEC = { accent: '#ee7fae', surface: '#251c23', text: '#f5e9ee', off: 'neutral' };

export default {
  meta: { id: 'theme-rose', name: 'Rose', kind: 'theme', cents: 599, blurb: 'Plum-charcoal panels, blush type and a soft rose-pink accent.' },
  theme: themeTokens(SPEC),
};
