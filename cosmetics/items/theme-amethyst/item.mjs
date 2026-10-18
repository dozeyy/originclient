// Amethyst menu theme. A theme is Origin's main menu colours re-tinted (cosmetics/lib/theme.mjs):
// the same tokens, contrast and alphas as the default, moved to a violet accent on violet-
// charcoal surfaces with a cool lavender-white type. Preview: tools/cosmetics/theme-preview.mjs.
import { themeTokens } from '../../lib/theme.mjs';

const SPEC = { accent: '#a48cf2', surface: '#201d29', text: '#ece8f5' };

export default {
  meta: { id: 'theme-amethyst', name: 'Amethyst', kind: 'theme', cents: 599, blurb: 'Violet-charcoal panels, lavender-white type and a soft amethyst accent.' },
  theme: themeTokens(SPEC),
};
