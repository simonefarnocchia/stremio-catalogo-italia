'use strict';
// Catalogo Italia per Stremio — file unico generato da tools/bundle.js
const __defs = {}, __cache = {};
function __req(n) {
  if (!n.startsWith('./')) return require(n);
  if (__cache[n]) return __cache[n].exports;
  const module = { exports: {} };
  __cache[n] = module;
  __defs[n](module, module.exports, __req);
  return module.exports;
}

__defs['./config'] = function (module, exports, require) {

// Configurazione dell'utente: viaggia codificata (base64url JSON) nel percorso
// dell'URL dell'addon, come fanno gli addon Stremio configurabili.

const DEFAULT_PROVIDERS = [
  { id: 8, n: 'Netflix' },
  { id: 119, n: 'Prime Video' },
  { id: 337, n: 'Disney+' },
  { id: 39, n: 'NOW' },
  { id: 350, n: 'Apple TV+' },
  { id: 531, n: 'Paramount+' },
  { id: 222, n: 'RaiPlay' },
  { id: 359, n: 'Mediaset Infinity' },
];

const DEFAULT_SECTIONS = {
  all: true,        // "I miei servizi": tutti i servizi scelti insieme
  providers: true,  // un catalogo per ogni servizio
  cinema: true,     // al cinema ora
  upcoming: true,   // prossimamente al cinema
  rent: true,       // noleggio e acquisto
  free: true,       // gratis / con pubblicità
  search: true,     // ricerca in italiano
};

function defaults() {
  return {
    providers: DEFAULT_PROVIDERS.map((p) => ({ ...p })),
    sections: { ...DEFAULT_SECTIONS },
    minVotes: 30,
    key: '',
  };
}

function decode(raw) {
  const cfg = defaults();
  if (!raw) return cfg;
  try {
    const obj = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
    if (Array.isArray(obj.providers)) {
      cfg.providers = obj.providers
        .filter((p) => p && Number.isInteger(p.id))
        .map((p) => ({ id: p.id, n: String(p.n || p.id).slice(0, 40) }))
        .slice(0, 40);
    }
    if (obj.sections && typeof obj.sections === 'object') {
      for (const k of Object.keys(DEFAULT_SECTIONS)) {
        if (k in obj.sections) cfg.sections[k] = !!obj.sections[k];
      }
    }
    if (Number.isFinite(obj.minVotes)) cfg.minVotes = Math.max(0, Math.min(5000, Math.trunc(obj.minVotes)));
    if (typeof obj.key === 'string') cfg.key = obj.key.trim().slice(0, 400);
  } catch (e) {
    // configurazione illeggibile: si usano i valori predefiniti
  }
  return cfg;
}

function encode(cfg) {
  return Buffer.from(JSON.stringify(cfg)).toString('base64url');
}

module.exports = { decode, encode, defaults, DEFAULT_PROVIDERS, DEFAULT_SECTIONS };
};

__defs['./tmdb'] = function (module, exports, require) {

// Client TMDB minimale con cache in memoria e deduplica delle richieste.

const BASE = 'https://api.themoviedb.org/3';
const IMG = 'https://image.tmdb.org/t/p/';
const MAX_ENTRIES = 8000;

const cache = new Map();
const inflight = new Map();

const T = { movie: 'movie', series: 'tv' };

function img(path, size) {
  return path ? IMG + size + path : undefined;
}

function getKey(userKey) {
  return String(userKey || process.env.TMDB_KEY || '').trim();
}

async function tmdb(path, params = {}, { key, ttl = 6 * 3600 } = {}) {
  const k = getKey(key);
  if (!k) throw new Error('Chiave TMDB mancante');

  const qs = new URLSearchParams();
  for (const name of Object.keys(params).sort()) {
    const v = params[name];
    if (v !== undefined && v !== null && v !== '') qs.set(name, String(v));
  }
  const cacheKey = path + '?' + qs.toString();

  const hit = cache.get(cacheKey);
  if (hit && hit.exp > Date.now()) return hit.val;
  if (inflight.has(cacheKey)) return inflight.get(cacheKey);

  const p = (async () => {
    const headers = { accept: 'application/json' };
    const q = new URLSearchParams(qs);
    // Token v4 (JWT, inizia con "eyJ") come Bearer, chiave v3 come api_key
    if (k.startsWith('eyJ')) headers.authorization = 'Bearer ' + k;
    else q.set('api_key', k);
    const url = BASE + path + '?' + q.toString();

    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await fetch(url, { headers });
      if (res.status === 429) {
        const wait = (Number(res.headers.get('retry-after')) || 1) * 1000;
        await new Promise((r) => setTimeout(r, wait));
        continue;
      }
      if (res.status === 404) return null;
      if (res.status === 401) throw new Error('Chiave TMDB non valida');
      if (!res.ok) throw new Error(`TMDB ${res.status} su ${path}`);
      return res.json();
    }
    throw new Error('TMDB: troppe richieste');
  })();

  inflight.set(cacheKey, p);
  try {
    const val = await p;
    cache.set(cacheKey, { val, exp: Date.now() + ttl * 1000 });
    if (cache.size > MAX_ENTRIES) cache.delete(cache.keys().next().value);
    return val;
  } finally {
    inflight.delete(cacheKey);
  }
}

async function imdbIdOf(type, tmdbId, key) {
  const d = await tmdb(`/${T[type]}/${tmdbId}/external_ids`, {}, { key, ttl: 30 * 86400 });
  return d && d.imdb_id ? d.imdb_id : null;
}

// Accetta "tt123", "tt123:1:2", "tmdb:movie:123", "tmdb:series:123:1:2"
async function resolveId(type, id, key) {
  const parts = String(id).split(':');
  if (parts[0] === 'ci') parts.shift();
  if (parts[0] === 'tmdb') {
    return {
      tmdbId: Number(parts[2]) || null,
      imdbId: null,
      season: parts[3] !== undefined ? Number(parts[3]) : undefined,
      episode: parts[4] !== undefined ? Number(parts[4]) : undefined,
    };
  }
  const imdbId = parts[0];
  const found = await tmdb(`/find/${imdbId}`, { external_source: 'imdb_id' }, { key, ttl: 30 * 86400 });
  const list = type === 'movie' ? found && found.movie_results : found && found.tv_results;
  return {
    tmdbId: list && list[0] ? list[0].id : null,
    imdbId,
    season: parts[1] !== undefined ? Number(parts[1]) : undefined,
    episode: parts[2] !== undefined ? Number(parts[2]) : undefined,
  };
}

module.exports = { tmdb, img, imdbIdOf, resolveId, T };
};

__defs['./filters'] = function (module, exports, require) {

// Generi TMDB con nomi italiani e filtri del menu a tendina di Stremio.
// Stremio mostra un solo menu per catalogo: qui contiene generi, anni,
// decenni, voti e ordinamenti, uno alla volta.

const GENRES = {
  movie: [
    [28, 'Azione'], [12, 'Avventura'], [16, 'Animazione'], [35, 'Commedia'],
    [80, 'Crime'], [99, 'Documentario'], [18, 'Dramma'], [10751, 'Famiglia'],
    [14, 'Fantasy'], [878, 'Fantascienza'], [27, 'Horror'], [10402, 'Musica'],
    [9648, 'Mistero'], [10749, 'Romance'], [36, 'Storia'], [53, 'Thriller'],
    [10752, 'Guerra'], [37, 'Western'], [10770, 'Film TV'],
  ],
  series: [
    [10759, 'Azione e avventura'], [16, 'Animazione'], [35, 'Commedia'],
    [80, 'Crime'], [99, 'Documentario'], [18, 'Dramma'], [10751, 'Famiglia'],
    [10762, 'Bambini'], [9648, 'Mistero'], [10765, 'Fantascienza e fantasy'],
    [10764, 'Reality'], [10766, 'Soap'], [10767, 'Talk show'],
    [10768, 'Guerra e politica'], [37, 'Western'], [10763, 'News'],
  ],
};

const POPULAR = '🔥 Più popolari';
const TOP = '⭐ Più votati';
const LATEST = '🆕 Ultime uscite';
const DECADES = {
  '📅 Anni 2010': [2010, 2019],
  '📅 Anni 2000': [2000, 2009],
  "📅 Anni '90": [1990, 1999],
  "📅 Anni '80": [1980, 1989],
  '📅 Classici (prima del 1980)': [1900, 1979],
};
const VOTES = { '⭐ Voto 8+': 8, '⭐ Voto 7+': 7 };

function genreName(type, id) {
  const g = (GENRES[type] || []).find((x) => x[0] === id);
  return g ? g[1] : undefined;
}

function sortedGenres(type) {
  return GENRES[type].map((g) => g[1]).sort((a, b) => a.localeCompare(b, 'it'));
}

function filterOptions(type, { limited = false } = {}) {
  if (limited) return [POPULAR, ...sortedGenres(type)];
  const y = new Date().getFullYear();
  const years = [];
  for (let i = 0; i <= 8; i++) years.push(`📅 ${y - i}`);
  return [POPULAR, TOP, LATEST, ...sortedGenres(type), ...years, ...Object.keys(DECADES), ...Object.keys(VOTES)];
}

// Modifica i parametri di /discover in base al filtro scelto.
function applyFilter(type, value, p) {
  if (!value || value === POPULAR) return;
  const isMovie = type === 'movie';
  const gte = isMovie ? 'primary_release_date.gte' : 'first_air_date.gte';
  const lte = isMovie ? 'primary_release_date.lte' : 'first_air_date.lte';
  const today = new Date().toISOString().slice(0, 10);
  const minVotes = (n) => { p['vote_count.gte'] = Math.max(Number(p['vote_count.gte']) || 0, n); };

  if (value === TOP) {
    p.sort_by = 'vote_average.desc';
    minVotes(isMovie ? 300 : 150);
    return;
  }
  if (value === LATEST) {
    p.sort_by = isMovie ? 'primary_release_date.desc' : 'first_air_date.desc';
    p[lte] = today;
    return;
  }
  const g = GENRES[type].find((x) => x[1] === value);
  if (g) { p.with_genres = g[0]; return; }

  const m = value.match(/^📅 (\d{4})$/);
  if (m) { p[isMovie ? 'primary_release_year' : 'first_air_date_year'] = m[1]; return; }

  if (DECADES[value]) {
    const [a, b] = DECADES[value];
    p[gte] = `${a}-01-01`;
    p[lte] = `${b}-12-31`;
    return;
  }
  if (VOTES[value]) {
    p['vote_average.gte'] = VOTES[value];
    minVotes(isMovie ? 200 : 100);
  }
}

module.exports = { GENRES, genreName, filterOptions, applyFilter };
};

__defs['./providers'] = function (module, exports, require) {

const { tmdb, img } = require('./tmdb');

// Link di ricerca diretti per i servizi che li supportano in modo affidabile.
// Per gli altri si usa la pagina TMDB "Dove guardare" (dati JustWatch), che
// contiene i link diretti a ogni servizio.
const SEARCH = [
  [/netflix/i, (q) => `https://www.netflix.com/search?q=${q}`],
  [/prime video|amazon video/i, (q) => `https://www.primevideo.com/search/ref=atv_nb_sr?phrase=${q}`],
  [/apple tv/i, (q) => `https://tv.apple.com/it/search?term=${q}`],
  [/google play/i, (q) => `https://play.google.com/store/search?q=${q}&c=movies`],
  [/youtube/i, (q) => `https://www.youtube.com/results?search_query=${q}`],
  [/rai ?play/i, (q) => `https://www.raiplay.it/ricerca.html?q=${q}`],
];

function providerUrl(name, title, fallback) {
  const q = encodeURIComponent(title || '');
  if (q) for (const [re, f] of SEARCH) if (re.test(name)) return f(q);
  return fallback;
}

// Disponibilità in Italia, raggruppata: abbonamento, gratis, noleggio/acquisto
function groupOffers(wp) {
  if (!wp) return null;
  const uniq = (lists) => {
    const seen = new Map();
    for (const list of lists) for (const p of list || []) if (!seen.has(p.provider_id)) seen.set(p.provider_id, p);
    return [...seen.values()].sort((a, b) => (a.display_priority || 999) - (b.display_priority || 999));
  };
  return {
    link: wp.link,
    subscription: uniq([wp.flatrate]),
    free: uniq([wp.free, wp.ads]),
    rentBuy: uniq([wp.rent, wp.buy]),
  };
}

function availabilityText(wp) {
  const g = groupOffers(wp);
  if (!g) return '';
  const names = (l) => l.map((p) => p.provider_name).join(', ');
  const lines = [];
  if (g.subscription.length) lines.push(`📺 In abbonamento: ${names(g.subscription)}`);
  if (g.free.length) lines.push(`🆓 Gratis: ${names(g.free)}`);
  if (g.rentBuy.length) lines.push(`💶 Noleggio/acquisto: ${names(g.rentBuy)}`);
  return lines.join('\n');
}

// Elenco dei servizi disponibili in Italia, per la pagina di configurazione
async function providerList(key) {
  const [m, t] = await Promise.all(
    ['movie', 'tv'].map((x) => tmdb(`/watch/providers/${x}`, { watch_region: 'IT', language: 'it-IT' }, { key, ttl: 86400 }))
  );
  const map = new Map();
  for (const p of [...((m && m.results) || []), ...((t && t.results) || [])]) {
    if (map.has(p.provider_id)) continue;
    const prio = p.display_priorities && p.display_priorities.IT !== undefined ? p.display_priorities.IT : (p.display_priority || 999);
    map.set(p.provider_id, { id: p.provider_id, name: p.provider_name, logo: img(p.logo_path, 'w92'), prio });
  }
  return [...map.values()].sort((a, b) => a.prio - b.prio);
}

module.exports = { providerUrl, groupOffers, availabilityText, providerList };
};

__defs['./catalogs'] = function (module, exports, require) {

const { tmdb, img, imdbIdOf, T } = require('./tmdb');
const { filterOptions, applyFilter, genreName } = require('./filters');

const TYPES = ['movie', 'series'];
const VERSION = '1.1.0';

function isoDay(offsetDays) {
  return new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);
}

function buildManifest(cfg) {
  const s = cfg.sections;
  const catalogs = [];
  const ex = (type, limited) => [
    { name: 'genre', options: filterOptions(type, { limited }), isRequired: false },
    { name: 'skip' },
  ];
  const both = (id, name) => TYPES.forEach((type) => catalogs.push({ type, id, name, extra: ex(type) }));

  if (s.all && cfg.providers.length) both('it_all', 'I miei servizi');
  if (s.providers) for (const p of cfg.providers) both(`it_p_${p.id}`, p.n);
  if (s.cinema) catalogs.push({ type: 'movie', id: 'it_cinema', name: 'Al cinema', extra: ex('movie', true) });
  if (s.upcoming) catalogs.push({ type: 'movie', id: 'it_upcoming', name: 'Prossimamente al cinema', extra: ex('movie', true) });
  if (s.rent) both('it_rent', 'Noleggio e acquisto');
  if (s.free) both('it_free', 'Gratis');
  if (s.search) {
    for (const type of TYPES) {
      catalogs.push({ type, id: 'it_search', name: 'Cerca', extra: [{ name: 'search', isRequired: true }] });
    }
  }

  return {
    id: 'community.catalogo.italia',
    version: VERSION,
    name: 'Catalogo Italia',
    description:
      'Film e serie in italiano organizzati per servizio di streaming (Netflix, Prime Video, NOW, Disney+…), ' +
      'cinema, noleggio e gratis, con filtri per genere, anno e voto. Dati TMDB e JustWatch.',
    resources: [
      'catalog',
      { name: 'meta', types: TYPES, idPrefixes: ['ci:', 'tmdb:'] },
      { name: 'stream', types: TYPES, idPrefixes: ['tt', 'ci:', 'tmdb:'] },
    ],
    types: TYPES,
    idPrefixes: ['ci:', 'tt', 'tmdb:'],
    catalogs,
    behaviorHints: { configurable: true, configurationRequired: false },
  };
}

async function preview(type, r, key) {
  let imdb = null;
  try {
    imdb = await imdbIdOf(type, r.id, key);
  } catch (e) {
    // senza ID IMDb si usa l'ID TMDB
  }
  const date = r.release_date || r.first_air_date || '';
  const vote = r.vote_count ? `⭐ ${Number(r.vote_average).toFixed(1)}/10` : null;
  return {
    id: imdb ? `ci:${imdb}` : `tmdb:${type}:${r.id}`,
    type,
    name: r.title || r.name,
    poster: img(r.poster_path, 'w342'),
    posterShape: 'poster',
    background: img(r.backdrop_path, 'w1280'),
    releaseInfo: date.slice(0, 4) || undefined,
    description: [vote, r.overview].filter(Boolean).join('\n'),
    genres: (r.genre_ids || []).map((id) => genreName(type, id)).filter(Boolean),
  };
}

function discoverParams(cfg, type, id, page) {
  const p = {
    language: 'it-IT',
    include_adult: false,
    page,
    sort_by: 'popularity.desc',
    'vote_count.gte': cfg.minVotes,
  };
  const subscriptionTypes = 'flatrate|free|ads';

  if (id === 'it_all') {
    if (!cfg.providers.length) return null;
    Object.assign(p, {
      watch_region: 'IT',
      with_watch_providers: cfg.providers.map((x) => x.id).join('|'),
      with_watch_monetization_types: subscriptionTypes,
    });
  } else if (/^it_p_\d+$/.test(id)) {
    Object.assign(p, { watch_region: 'IT', with_watch_providers: id.slice(5), with_watch_monetization_types: subscriptionTypes });
  } else if (id === 'it_rent') {
    Object.assign(p, { watch_region: 'IT', with_watch_monetization_types: 'rent|buy' });
  } else if (id === 'it_free') {
    Object.assign(p, { watch_region: 'IT', with_watch_monetization_types: 'free|ads' });
  } else if (id === 'it_cinema' && type === 'movie') {
    Object.assign(p, {
      region: 'IT',
      with_release_type: '3|2',
      'release_date.gte': isoDay(-45),
      'release_date.lte': isoDay(0),
      'vote_count.gte': 0,
    });
  } else if (id === 'it_upcoming' && type === 'movie') {
    Object.assign(p, {
      region: 'IT',
      with_release_type: '3|2',
      'release_date.gte': isoDay(1),
      'release_date.lte': isoDay(150),
      'vote_count.gte': 0,
    });
  } else {
    return null;
  }
  return p;
}

async function catalog(cfg, type, id, extra = {}) {
  if (!T[type]) return { metas: [] };
  const key = cfg.key;
  const skip = Number(extra.skip) || 0;
  const page = Math.min(500, Math.floor(skip / 20) + 1);

  let path;
  let params;
  if (id === 'it_search') {
    if (!extra.search) return { metas: [] };
    path = `/search/${T[type]}`;
    params = { language: 'it-IT', include_adult: false, page, query: extra.search };
  } else {
    params = discoverParams(cfg, type, id, page);
    if (!params) return { metas: [] };
    applyFilter(type, extra.genre, params);
    path = `/discover/${T[type]}`;
  }

  const data = await tmdb(path, params, { key, ttl: id === 'it_search' ? 3600 : 6 * 3600 });
  const results = ((data && data.results) || []).filter((r) => r && r.poster_path);
  const metas = await Promise.all(results.map((r) => preview(type, r, key)));

  const seen = new Set();
  return { metas: metas.filter((m) => !seen.has(m.id) && seen.add(m.id)) };
}

module.exports = { buildManifest, catalog, VERSION };
};

__defs['./meta'] = function (module, exports, require) {

const { tmdb, img, resolveId, T } = require('./tmdb');
const { providerUrl, groupOffers, availabilityText } = require('./providers');

function pickLogo(logos) {
  if (!logos || !logos.length) return undefined;
  const rank = (l) => (l.iso_639_1 === 'it' ? 0 : l.iso_639_1 === 'en' ? 1 : 2);
  const best = [...logos].sort((a, b) => rank(a) - rank(b) || (b.vote_average || 0) - (a.vote_average || 0))[0];
  return img(best.file_path, 'w500');
}

function pickTrailers(videos) {
  const list = ((videos && videos.results) || []).filter(
    (v) => v.site === 'YouTube' && (v.type === 'Trailer' || v.type === 'Teaser')
  );
  const rank = (v) => (v.iso_639_1 === 'it' ? 0 : 1) + (v.type === 'Trailer' ? 0 : 0.5);
  return list.sort((a, b) => rank(a) - rank(b)).slice(0, 3);
}

function seriesRange(det) {
  const a = (det.first_air_date || '').slice(0, 4);
  if (!a) return undefined;
  if (det.in_production) return `${a}-`;
  const b = (det.last_air_date || '').slice(0, 4);
  return b && b !== a ? `${a}-${b}` : a;
}

async function episodes(det, baseId, key) {
  const nums = (det.seasons || []).map((s) => s.season_number).filter((n) => Number.isInteger(n));
  const vids = [];
  for (let i = 0; i < nums.length; i += 20) {
    const chunk = nums.slice(i, i + 20);
    const d = await tmdb(
      `/tv/${det.id}`,
      { language: 'it-IT', append_to_response: chunk.map((n) => `season/${n}`).join(',') },
      { key, ttl: 12 * 3600 }
    );
    for (const n of chunk) {
      const season = d && d[`season/${n}`];
      for (const e of (season && season.episodes) || []) {
        vids.push({
          id: `${baseId}:${n}:${e.episode_number}`,
          title: e.name || `Episodio ${e.episode_number}`,
          season: n,
          episode: e.episode_number,
          released: e.air_date ? new Date(e.air_date + 'T00:00:00Z').toISOString() : undefined,
          overview: e.overview || undefined,
          thumbnail: img(e.still_path, 'w300'),
        });
      }
    }
  }
  return vids;
}

async function meta(cfg, type, id) {
  if (!T[type]) return { meta: null };
  const key = cfg.key;
  const r = await resolveId(type, id, key);
  if (!r.tmdbId) return { meta: null };
  const t = T[type];

  const det = await tmdb(
    `/${t}/${r.tmdbId}`,
    {
      language: 'it-IT',
      append_to_response: `credits,videos,external_ids,images,watch/providers,${t === 'movie' ? 'release_dates' : 'content_ratings'}`,
      include_image_language: 'it,en,null',
      include_video_language: 'it,en',
    },
    { key, ttl: 24 * 3600 }
  );
  if (!det) return { meta: null };

  const metaId = String(id);
  const imdbId = r.imdbId || (det.external_ids && det.external_ids.imdb_id) || null;
  const videoBase = imdbId || `tmdb:${type}:${r.tmdbId}`;
  const name = det.title || det.name;
  const wp = det['watch/providers'] && det['watch/providers'].results && det['watch/providers'].results.IT;
  const offers = groupOffers(wp);

  const header = [];
  if (det.vote_count) header.push(`⭐ ${Number(det.vote_average).toFixed(1)}/10 su TMDB`);
  const avail = availabilityText(wp);
  if (avail) header.push(avail);
  else header.push('Non disponibile in streaming in Italia al momento');

  const links = [];
  if (offers) {
    for (const p of [...offers.subscription, ...offers.free]) {
      links.push({ name: p.provider_name, category: 'Disponibile su', url: providerUrl(p.provider_name, name, offers.link) });
    }
    for (const p of offers.rentBuy) {
      links.push({ name: p.provider_name, category: 'Noleggio e acquisto', url: providerUrl(p.provider_name, name, offers.link) });
    }
  }

  const directors = type === 'movie'
    ? ((det.credits && det.credits.crew) || []).filter((c) => c.job === 'Director').map((c) => c.name)
    : (det.created_by || []).map((c) => c.name);
  const cast = ((det.credits && det.credits.cast) || []).slice(0, 12).map((c) => c.name);
  const trailers = pickTrailers(det.videos);
  const runtime = type === 'movie' ? det.runtime : (det.episode_run_time || [])[0];
  const date = det.release_date || det.first_air_date;

  const m = {
    id: metaId,
    type,
    name,
    poster: img(det.poster_path, 'w500'),
    background: img(det.backdrop_path, 'w1280'),
    logo: pickLogo(det.images && det.images.logos),
    description: header.join('\n') + (det.overview ? '\n\n' + det.overview : ''),
    releaseInfo: type === 'movie' ? (date || '').slice(0, 4) || undefined : seriesRange(det),
    released: date ? new Date(date + 'T00:00:00Z').toISOString() : undefined,
    runtime: runtime ? `${runtime} min` : undefined,
    genres: (det.genres || []).map((g) => g.name),
    director: directors,
    cast,
    country: (det.origin_country || (det.production_countries || []).map((c) => c.iso_3166_1)).join(', ') || undefined,
    links,
    trailers: trailers.map((v) => ({ source: v.key, type: 'Trailer' })),
    trailerStreams: trailers.map((v) => ({ title: v.name, ytId: v.key })),
    behaviorHints: type === 'movie' ? { defaultVideoId: videoBase } : {},
  };

  if (type === 'series') m.videos = await episodes(det, videoBase, key);
  return { meta: m };
}

module.exports = { meta };
};

__defs['./streams'] = function (module, exports, require) {

// Per ogni titolo restituisce "stream" che aprono il servizio giusto.
// Gli altri addon (e i file locali) continuano a comparire sotto, come sempre.

const { tmdb, resolveId, T } = require('./tmdb');
const { providerUrl, groupOffers } = require('./providers');

async function streams(cfg, type, id) {
  if (!T[type]) return { streams: [] };
  const key = cfg.key;
  const r = await resolveId(type, id, key);
  if (!r.tmdbId) return { streams: [] };
  const t = T[type];

  const [det, wpAll] = await Promise.all([
    tmdb(`/${t}/${r.tmdbId}`, { language: 'it-IT' }, { key, ttl: 24 * 3600 }),
    tmdb(`/${t}/${r.tmdbId}/watch/providers`, {}, { key, ttl: 12 * 3600 }),
  ]);
  const offers = groupOffers(wpAll && wpAll.results && wpAll.results.IT);
  if (!offers) return { streams: [] };

  const title = (det && (det.title || det.name)) || '';
  const mine = new Set(cfg.providers.map((p) => p.id));
  const out = [];

  const add = (list, label) => {
    const sorted = [...list].sort((a, b) => (mine.has(b.provider_id) ? 1 : 0) - (mine.has(a.provider_id) ? 1 : 0));
    for (const p of sorted) {
      const desc = `${label}${mine.has(p.provider_id) ? ' · nei tuoi servizi' : ''}\nApri su ${p.provider_name}`;
      out.push({
        name: `${mine.has(p.provider_id) ? '✅' : '▶️'} ${p.provider_name}`,
        description: desc,
        title: desc,
        externalUrl: providerUrl(p.provider_name, title, offers.link),
      });
    }
  };

  add(offers.subscription, 'Incluso nell\'abbonamento');
  add(offers.free, 'Gratis');
  add(offers.rentBuy, 'Noleggio o acquisto');

  if (offers.link) {
    const desc = 'Tutte le offerte in Italia\nLink diretti a ogni servizio (TMDB · JustWatch)';
    out.push({ name: 'ℹ️ Dove guardarlo', description: desc, title: desc, externalUrl: offers.link });
  }
  return { streams: out };
}

module.exports = { streams };
};

__defs['./server'] = function (module, exports, require) {

const http = require('http');
const fs = require('fs');
const path = require('path');

// Opzioni dell'add-on Home Assistant, se presenti
try {
  const o = JSON.parse(fs.readFileSync('/data/options.json', 'utf8'));
  if (o.tmdb_key && !process.env.TMDB_KEY) process.env.TMDB_KEY = String(o.tmdb_key).trim();
} catch (e) {
  // non siamo dentro Home Assistant
}

const { decode } = require('./config');
const { buildManifest, catalog } = require('./catalogs');
const { meta } = require('./meta');
const { streams } = require('./streams');
const { providerList } = require('./providers');

const PORT = Number(process.env.PORT) || 7000;
const CONFIGURE_HTML = "<!doctype html>\n<html lang=\"it\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Catalogo Italia · Configura</title>\n<style>\n  :root { --bg:#0f1115; --card:#181b22; --line:#2a2f3a; --text:#e8eaf0; --muted:#9aa3b2; --acc:#7b5cff; --ok:#2ecc71; }\n  * { box-sizing:border-box; }\n  body { margin:0; background:var(--bg); color:var(--text); font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }\n  main { max-width:860px; margin:0 auto; padding:24px 16px 120px; }\n  h1 { font-size:26px; margin:0 0 4px; }\n  h2 { font-size:17px; margin:0 0 12px; }\n  p.sub { color:var(--muted); margin:0 0 24px; }\n  section { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:18px; margin-bottom:16px; }\n  input[type=text], input[type=search], select { width:100%; padding:10px 12px; border-radius:10px; border:1px solid var(--line); background:#0c0e12; color:var(--text); font-size:15px; }\n  .hint { color:var(--muted); font-size:13px; margin-top:6px; }\n  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(130px,1fr)); gap:10px; margin-top:12px; }\n  .prov { display:flex; align-items:center; gap:8px; padding:8px; border:1px solid var(--line); border-radius:10px; cursor:pointer; user-select:none; background:#12151b; }\n  .prov img { width:32px; height:32px; border-radius:8px; flex:none; background:#222; }\n  .prov span { font-size:13px; line-height:1.2; overflow:hidden; text-overflow:ellipsis; }\n  .prov.on { border-color:var(--acc); background:#1d1836; }\n  .row { display:flex; align-items:center; gap:10px; padding:6px 0; }\n  .row input { width:18px; height:18px; accent-color:var(--acc); }\n  .bar { position:fixed; left:0; right:0; bottom:0; background:rgba(15,17,21,.96); border-top:1px solid var(--line); padding:12px 16px; }\n  .bar div { max-width:860px; margin:0 auto; display:flex; gap:10px; flex-wrap:wrap; }\n  button, a.btn { flex:1; min-width:150px; padding:12px; border-radius:10px; border:0; font-size:15px; font-weight:600; cursor:pointer; text-align:center; text-decoration:none; }\n  .primary { background:var(--acc); color:#fff; }\n  .secondary { background:#252a35; color:var(--text); }\n  .count { color:var(--muted); font-weight:400; font-size:14px; }\n  .more { margin-top:12px; background:none; border:1px dashed var(--line); color:var(--muted); width:100%; flex:none; }\n  #msg { color:var(--ok); font-size:13px; min-height:18px; margin-top:6px; text-align:center; }\n</style>\n</head>\n<body>\n<main>\n  <h1>Catalogo Italia</h1>\n  <p class=\"sub\">Film e serie in italiano per servizio di streaming, cinema e noleggio. Scegli cosa vedere in Stremio e installa.</p>\n\n  <section id=\"keyBox\" hidden>\n    <h2>Chiave TMDB</h2>\n    <input type=\"text\" id=\"key\" placeholder=\"Incolla la chiave API o il token di lettura TMDB\">\n    <div class=\"hint\">Gratuita su themoviedb.org → Impostazioni → API. Resta solo nel link dell'addon: non condividerlo.</div>\n  </section>\n\n  <section>\n    <h2>I tuoi servizi <span class=\"count\" id=\"count\"></span></h2>\n    <input type=\"search\" id=\"filter\" placeholder=\"Cerca un servizio (es. HBO, Sky, Infinity…)\">\n    <div class=\"grid\" id=\"grid\"><div class=\"hint\">Caricamento servizi disponibili in Italia…</div></div>\n    <button class=\"more\" id=\"more\" hidden>Mostra tutti i servizi</button>\n  </section>\n\n  <section>\n    <h2>Sezioni</h2>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"all\"> I miei servizi (tutti insieme)</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"providers\"> Un catalogo per ogni servizio</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"cinema\"> Al cinema</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"upcoming\"> Prossimamente al cinema</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"rent\"> Noleggio e acquisto</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"free\"> Gratis (RaiPlay, Pluto TV…)</label>\n    <label class=\"row\"><input type=\"checkbox\" data-s=\"search\"> Ricerca in italiano</label>\n  </section>\n\n  <section>\n    <h2>Qualità minima</h2>\n    <select id=\"minVotes\">\n      <option value=\"0\">Mostra tutto</option>\n      <option value=\"30\">Almeno 30 voti (consigliato)</option>\n      <option value=\"100\">Almeno 100 voti</option>\n      <option value=\"300\">Solo titoli molto noti (300+ voti)</option>\n    </select>\n    <div class=\"hint\">Nasconde i titoli con pochi voti, che spesso hanno trame o locandine mancanti.</div>\n  </section>\n</main>\n\n<div class=\"bar\"><div>\n  <a class=\"btn primary\" id=\"install\" href=\"#\">Installa in Stremio</a>\n  <button class=\"secondary\" id=\"copy\">Copia link</button>\n  <a class=\"btn secondary\" id=\"web\" href=\"#\" target=\"_blank\" rel=\"noopener\">Stremio Web</a>\n</div><div id=\"msg\"></div></div>\n\n<script>\nconst DEFAULTS = {\n  providers: [\n    {id:8,n:'Netflix'},{id:119,n:'Prime Video'},{id:337,n:'Disney+'},{id:39,n:'NOW'},\n    {id:350,n:'Apple TV+'},{id:531,n:'Paramount+'},{id:222,n:'RaiPlay'},{id:359,n:'Mediaset Infinity'}\n  ],\n  sections: {all:true,providers:true,cinema:true,upcoming:true,rent:true,free:true,search:true},\n  minVotes: 30, key: ''\n};\nconst b64urlDecode = (s) => {\n  s = s.replace(/-/g,'+').replace(/_/g,'/'); while (s.length % 4) s += '=';\n  return new TextDecoder().decode(Uint8Array.from(atob(s), c => c.charCodeAt(0)));\n};\nconst b64urlEncode = (str) => {\n  const bytes = new TextEncoder().encode(str); let bin = '';\n  bytes.forEach(b => bin += String.fromCharCode(b));\n  return btoa(bin).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');\n};\n\nlet cfg = JSON.parse(JSON.stringify(DEFAULTS));\nconst parts = location.pathname.split('/').filter(Boolean);\nif (parts.length === 2 && parts[1] === 'configure') {\n  try { cfg = Object.assign(cfg, JSON.parse(b64urlDecode(parts[0]))); } catch (e) {}\n}\n\nconst selected = new Map(cfg.providers.map(p => [p.id, p.n]));\nlet all = [], showAll = false, hasServerKey = true;\nconst $ = (id) => document.getElementById(id);\n\ndocument.querySelectorAll('[data-s]').forEach(el => {\n  el.checked = cfg.sections[el.dataset.s] !== false;\n  el.onchange = update;\n});\n$('minVotes').value = String(cfg.minVotes);\n$('minVotes').onchange = update;\n$('key').value = cfg.key || '';\n$('key').onchange = () => { update(); loadProviders(); };\n$('filter').oninput = render;\n$('more').onclick = () => { showAll = true; render(); };\n\nfunction render() {\n  const q = $('filter').value.trim().toLowerCase();\n  let list = all.filter(p => !q || p.name.toLowerCase().includes(q));\n  const limit = (!showAll && !q) ? 36 : Infinity;\n  const visible = list.filter((p, i) => i < limit || selected.has(p.id));\n  $('more').hidden = showAll || !!q || list.length <= limit;\n  $('grid').innerHTML = '';\n  for (const p of visible) {\n    const d = document.createElement('div');\n    d.className = 'prov' + (selected.has(p.id) ? ' on' : '');\n    d.innerHTML = `<img alt=\"\" loading=\"lazy\"><span></span>`;\n    d.querySelector('img').src = p.logo || '';\n    d.querySelector('span').textContent = p.name;\n    d.onclick = () => {\n      if (selected.has(p.id)) selected.delete(p.id); else selected.set(p.id, p.name);\n      d.classList.toggle('on'); update();\n    };\n    $('grid').appendChild(d);\n  }\n  $('count').textContent = `· ${selected.size} selezionati`;\n}\n\nasync function loadProviders() {\n  const key = $('key').value.trim();\n  if (!hasServerKey && !key) { $('grid').innerHTML = '<div class=\"hint\">Inserisci la chiave TMDB per caricare i servizi.</div>'; return; }\n  try {\n    const r = await fetch('/api/providers' + (key ? '?key=' + encodeURIComponent(key) : ''));\n    if (!r.ok) throw new Error();\n    all = await r.json();\n    render();\n  } catch (e) {\n    $('grid').innerHTML = '<div class=\"hint\">Impossibile caricare i servizi: controlla la chiave TMDB.</div>';\n  }\n}\n\nfunction manifestUrl() {\n  const out = {\n    providers: [...selected].map(([id, n]) => ({ id, n })),\n    sections: {}, minVotes: Number($('minVotes').value), key: $('key').value.trim()\n  };\n  document.querySelectorAll('[data-s]').forEach(el => out.sections[el.dataset.s] = el.checked);\n  if (hasServerKey && !out.key) delete out.key;\n  return location.origin + '/' + b64urlEncode(JSON.stringify(out)) + '/manifest.json';\n}\n\nfunction update() {\n  const url = manifestUrl();\n  $('install').href = url.replace(/^https?:\\/\\//, 'stremio://');\n  $('web').href = 'https://web.stremio.com/#/addons?addon=' + encodeURIComponent(url);\n  $('count').textContent = `· ${selected.size} selezionati`;\n}\n\n$('copy').onclick = async () => {\n  try { await navigator.clipboard.writeText(manifestUrl()); $('msg').textContent = 'Link copiato: incollalo nella ricerca addon di Stremio.'; }\n  catch (e) { prompt('Copia questo link:', manifestUrl()); }\n};\n\nfetch('/api/status').then(r => r.json()).then(s => {\n  hasServerKey = s.hasKey;\n  $('keyBox').hidden = hasServerKey;\n  update(); loadProviders();\n}).catch(() => { update(); loadProviders(); });\n</script>\n</body>\n</html>\n";
const RESERVED = new Set(['manifest.json', 'catalog', 'meta', 'stream', 'configure', 'api', 'health']);

function send(res, code, body, { type = 'application/json; charset=utf-8', maxAge = 0 } = {}) {
  res.writeHead(code, {
    'content-type': type,
    'access-control-allow-origin': '*',
    'access-control-allow-headers': '*',
    'cache-control': maxAge ? `public, max-age=${maxAge}` : 'no-store',
  });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}

const EMPTY = { catalog: { metas: [] }, meta: { meta: null }, stream: { streams: [] } };

async function handle(req, res) {
  if (req.method === 'OPTIONS') return send(res, 204, '');
  const url = new URL(req.url, 'http://localhost');
  const segs = url.pathname.split('/').filter(Boolean);

  if (!segs.length) {
    res.writeHead(302, { location: '/configure' });
    return res.end();
  }

  let rawCfg = '';
  if (!RESERVED.has(segs[0])) rawCfg = segs.shift();
  const cfg = decode(rawCfg);
  const head = segs[0];

  if (head === 'health') return send(res, 200, { ok: true });
  if (head === 'configure') return send(res, 200, CONFIGURE_HTML, { type: 'text/html; charset=utf-8' });
  if (head === 'api' && segs[1] === 'status') return send(res, 200, { hasKey: !!process.env.TMDB_KEY });
  if (head === 'api' && segs[1] === 'providers') {
    return send(res, 200, await providerList(url.searchParams.get('key') || ''), { maxAge: 86400 });
  }
  if (head === 'manifest.json') return send(res, 200, buildManifest(cfg), { maxAge: 3600 });

  if (EMPTY[head] && segs.length >= 3) {
    const type = segs[1];
    const hasExtra = segs.length >= 4;
    const id = decodeURIComponent(hasExtra ? segs[2] : segs[2].replace(/\.json$/, ''));
    const extra = hasExtra ? Object.fromEntries(new URLSearchParams(segs[3].replace(/\.json$/, ''))) : {};
    try {
      if (head === 'catalog') return send(res, 200, await catalog(cfg, type, id, extra), { maxAge: 6 * 3600 });
      if (head === 'meta') return send(res, 200, await meta(cfg, type, id), { maxAge: 24 * 3600 });
      if (head === 'stream') return send(res, 200, await streams(cfg, type, id), { maxAge: 6 * 3600 });
    } catch (e) {
      console.error(`[${head}] ${type}/${id}:`, e.message);
      return send(res, 200, EMPTY[head], { maxAge: 60 });
    }
  }
  return send(res, 404, { error: 'Non trovato' });
}

function createServer() {
  return http.createServer((req, res) => {
    handle(req, res).catch((e) => {
      console.error(e);
      if (!res.headersSent) send(res, 500, { error: e.message });
    });
  });
}

if (!process.env.CATALOGO_NO_LISTEN) {
  createServer().listen(PORT, () => {
    console.log(`Catalogo Italia attivo su http://0.0.0.0:${PORT}/configure`);
    if (!process.env.TMDB_KEY) console.log('Attenzione: TMDB_KEY non impostata, va inserita nella pagina di configurazione.');
  });
}

module.exports = { createServer };
};

module.exports = __req('./server');
