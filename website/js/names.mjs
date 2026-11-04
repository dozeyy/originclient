// Minecraft name lookups for the Store (store.js), kept apart from the page so node can test
// them without a browser (tools/website/test_store_names.mjs).

// PlayerDB's answer -> name state. Only a clear "no such player" is 'missing'; its own outages,
// rate limits and Mojang lookup failures also come back as success: false, and telling a buyer
// their real name doesn't exist would stop them checking out, so those are 'unknown'
// ("couldn't check it right now").
export function nameResult(status, j) {
  const p = j?.data?.player;
  if (j?.success && p) {
    const uuid = (p.raw_id || p.id || '').replace(/-/g, '');
    return { state: 'ok', player: { name: p.username, uuid, ...skinOf(p) } };
  }
  const code = String(j?.code || '');
  if (/not_found|invalid_username|unknown_player|no_player/i.test(code)) return { state: 'missing' };
  if (status === 404 || (status === 400 && !code)) return { state: 'missing' };
  return { state: 'unknown' };
}

// The player's head next to the typed name, always built from their UUID on crafthead (allowed by the
// site's CSP img-src). PlayerDB's own avatar link is never used: its host is PlayerDB's choice, and any
// host outside img-src would leave a broken image. Null for anything that isn't a plain 32-hex UUID, so
// nothing else can end up in the URL.
export function headUrl(uuid) {
  const id = String(uuid || '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(id) ? `https://crafthead.net/avatar/${id}/48` : null;
}

// The skin file and arm model straight from the Mojang profile, when the
// lookup includes it (either as skin_texture or the raw textures property).
export function skinOf(p) {
  const out = { skinUrl: p.skin_texture || null, slim: undefined };
  const prop = (p.properties || []).find(x => x.name === 'textures');
  if (prop) {
    try {
      const skin = JSON.parse(atob(prop.value)).textures?.SKIN;
      if (skin?.url) out.skinUrl = skin.url;
      if (skin) out.slim = skin.metadata?.model === 'slim';
    } catch { /* keep what we have */ }
  }
  return out;
}
