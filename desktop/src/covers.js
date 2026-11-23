'use strict';
// Finds a song's cover when the playing app doesn't share one (YouTube in a browser, some players):
// iTunes Search API by artist + title, then Deezer's public search. Cached; misses remembered.

const cache = new Map(); const missing = new Set();

function cleanArtist(a) {
  const parts = String(a || '').split(/ • | · /).map((p) => p.trim()).filter((p) => p &&
    !/^(video|song|music|podcast|episode|live)$/i.test(p) && !/^[\d.,]+\s*[KMB]?\s*(views?|watching|plays?)$/i.test(p) && !/^\d+\s+(years?|months?|weeks?|days?|hours?)\s+ago$/i.test(p));
  return (parts[0] || String(a || '')).replace(/ - Topic$/, '').replace(/\s*VEVO$/i, '').trim();
}
function cleanTitle(t) {
  return String(t || '')
    .replace(/[([][^)\]]*(official|video|audio|lyric|visuali[sz]er|music video|hd|4k|remaster)[^)\]]*[)\]]/gi, '')
    .replace(/\s+(ft\.?|feat\.?)\s+.*$/i, '')
    .replace(/\s+-\s+YouTube$/i, '').replace(/\s{2,}/g, ' ').trim();
}
const key = (t, a) => `${cleanTitle(t).toLowerCase()}|${cleanArtist(a).toLowerCase()}`;

async function json(url) { const r = await fetch(url, { signal: AbortSignal.timeout(8000) }); if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); }
async function dataUrl(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(8000) }); if (!r.ok) return '';
  const type = r.headers.get('content-type') || 'image/jpeg';
  return `data:${type};base64,${Buffer.from(await r.arrayBuffer()).toString('base64')}`;
}

async function itunes(t, a) {
  const j = await json(`https://itunes.apple.com/search?term=${encodeURIComponent(`${cleanArtist(a)} ${cleanTitle(t)}`.trim())}&entity=song&limit=3`);
  const want = cleanTitle(t).toLowerCase();
  const best = (j.results || []).find((r) => { const n = String(r.trackName || '').toLowerCase(); return n.includes(want) || want.includes(n); }) || (j.results || [])[0];
  return best && best.artworkUrl100 ? best.artworkUrl100.replace('100x100bb', '600x600bb') : '';
}
async function deezer(t, a) {
  const j = await json(`https://api.deezer.com/search?q=${encodeURIComponent(`${cleanArtist(a)} ${cleanTitle(t)}`.trim())}&limit=1`);
  const d = (j.data || [])[0]; return (d && d.album && d.album.cover_xl) || '';
}

/** A data: URL for the cover, or '' if none could be found. */
async function findCover(title, artist) {
  const k = key(title, artist);
  if (cache.has(k)) return cache.get(k);
  if (missing.has(k) || !cleanTitle(title)) return '';
  let url = '';
  try { url = await itunes(title, artist); } catch { /* try the next */ }
  if (!url) try { url = await deezer(title, artist); } catch { /* none */ }
  const d = url ? await dataUrl(url).catch(() => '') : '';
  if (d) { cache.set(k, d); if (cache.size > 40) cache.delete(cache.keys().next().value); } else missing.add(k);
  return d;
}

module.exports = { findCover, cleanArtist, cleanTitle };
