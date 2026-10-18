// Ocean menu theme. A theme is Origin's main menu colours re-tinted (cosmetics/lib/theme.mjs):
// the same tokens, contrast and alphas as the default, moved to a sky-blue accent on deep navy
// surfaces with a cool white type. Preview: tools/cosmetics/theme-preview.mjs.
import { themeTokens } from '../../lib/theme.mjs';

const SPEC = { accent: '#5aa9e6', surface: '#1a2029', text: '#e9eef3' };

export default {
  meta: { id: 'theme-ocean', name: 'Ocean', kind: 'theme', cents: 599, blurb: 'Deep navy panels, cool white type and a calm sky-blue accent.' },
  theme: themeTokens(SPEC),
};
