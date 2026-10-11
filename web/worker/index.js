// castbay.weenas.com's Worker: the website is static assets (dist/); only /api/* runs here.
//
// POST /api/reports: a problem report the person chose to send from CastBay (About →
// Diagnostics → Upload): plain text, already stripped on the device of media titles, links
// and names. It is kept in R2 under its ID, which the person passes on with their problem
// report; there is no way to read reports back from here. The bucket deletes them after 90
// days (a lifecycle rule). The sender's address is used only by the rate limiter, not kept.
//
// Devices that can't reach Cloudflare send the same requests through cast.weenas.com, a relay
// on the developer's server (RELAY_IPS) that passes them on unchanged and names the device's
// address in X-CastBay-Client-IP; that header is believed only from the relay's address.
//
// POST /api/stats: a day's anonymous usage summary from an app whose owner turned statistics
// on (one row per installation and day in D1; only whitelisted fields and counters are kept,
// anything else is refused). POST /api/stats/delete {id}: deletes an installation's rows, sent
// when statistics are turned off. A daily cron deletes rows older than a year.
//
// GET /api/stats/summary: totals for the private /stats page. Cloudflare Access guards both
// (only the allowed emails get in); this also checks Access's signed token itself, so the
// totals stay private even if the Access application were removed by mistake.

const MAX_BYTES = 512 * 1024;

// Google Search Console's ownership file. The static assets would answer its .html address with
// a redirect to a clean one (as for every page), which the check doesn't follow; so the Worker
// answers it as it is (wrangler.jsonc: run_worker_first).
const GOOGLE_VERIFICATION_PATH = '/google4a41bd36656ece7a.html';
const GOOGLE_VERIFICATION = 'google-site-verification: google4a41bd36656ece7a.html';
// Crockford's base 32: no I, L, O or U, so an ID read off a TV is not misread.
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === GOOGLE_VERIFICATION_PATH && (request.method === 'GET' || request.method === 'HEAD')) {
      return new Response(request.method === 'HEAD' ? null : GOOGLE_VERIFICATION, {
        headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=3600' },
      });
    }
    const routes = {
      '/api/reports': receiveReport,
      '/api/stats': receiveStats,
      '/api/stats/delete': deleteStats,
    };
    // The private page's data, under the path Cloudflare Access guards.
    if (url.pathname === '/api/stats/summary' || url.pathname.startsWith('/api/stats/summary/')) {
      if (request.method !== 'GET') return json({ error: 'method' }, 405, { Allow: 'GET' });
      if (!(await accessAllowed(request, env))) return json({ error: 'forbidden' }, 403);
      if (url.pathname === '/api/stats/summary') return summary(env);
      const report = /^\/api\/stats\/summary\/reports\/(CB-[0-9A-Z]{6})$/.exec(url.pathname);
      if (report) return readReport(env, report[1]);
      return json({ error: 'not found' }, 404);
    }
    const route = routes[url.pathname];
    if (!route) return json({ error: 'not found' }, 404);
    if (request.method !== 'POST') return json({ error: 'method' }, 405, { Allow: 'POST' });
    return route(request, env);
  },

  async scheduled(event, env) {
    await env.STATS.prepare("DELETE FROM daily WHERE day < date('now', '-365 days')").run();
  },
};

/** The sender's address, for the rate limits only: the device's, also through the relay. */
function clientAddress(request, env) {
  const address = request.headers.get('CF-Connecting-IP') ?? 'unknown';
  const relays = (env.RELAY_IPS ?? '').split(',').map((ip) => ip.trim()).filter(Boolean);
  const forwarded = request.headers.get('X-CastBay-Client-IP');
  if (relays.includes(address) && forwarded && /^[0-9a-fA-F.:]{2,45}$/.test(forwarded)) return forwarded;
  return address;
}

async function receiveReport(request, env) {
  const version = request.headers.get('X-CastBay-Version') ?? '';
  // 1.1.0, or a test build's 1.1.0-dev+6228385.
  if (!/^\d+(\.\d+){1,3}(-[0-9A-Za-z.]+)?(\+[0-9A-Za-z.]+)?$/.test(version) || version.length > 40) {
    return json({ error: 'version' }, 400);
  }
  const kind = request.headers.get('X-CastBay-Report') === 'crash' ? 'crash' : 'manual';
  if (env.UPLOAD_LIMIT) {
    const { success } = await env.UPLOAD_LIMIT.limit({ key: clientAddress(request, env) });
    if (!success) return json({ error: 'too many' }, 429);
  }
  const length = Number(request.headers.get('Content-Length') ?? 0);
  if (length > MAX_BYTES) return json({ error: 'too large' }, 413);
  const body = await request.arrayBuffer();
  if (body.byteLength === 0) return json({ error: 'empty' }, 400);
  if (body.byteLength > MAX_BYTES) return json({ error: 'too large' }, 413);

  for (let attempt = 0; attempt < 5; attempt++) {
    const id = 'CB-' + randomId(6);
    const key = `reports/${id}.txt`;
    // onlyIf: written only if no report has this ID yet.
    const stored = await env.REPORTS.put(key, body, {
      onlyIf: { etagDoesNotMatch: '*' },
      httpMetadata: { contentType: 'text/plain; charset=utf-8' },
      customMetadata: { version, kind, received: new Date().toISOString() },
    });
    if (stored) return json({ id }, 201);
  }
  return json({ error: 'busy' }, 503);
}

function randomId(length) {
  const bytes = crypto.getRandomValues(new Uint8Array(length));
  return Array.from(bytes, (b) => ALPHABET[b % 32]).join('');
}

function json(value, status, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers },
  });
}

// Every counter the app may send (UsageStats.kt); anything else refuses the whole summary.
const COUNTER = /^(cast\.(airplay|dlna)\.(mirror|music|video)|length\.(under1m|1to10m|over10m)|mirror\.(2160p|1440p|1080p|720p|smaller)\.(h264|h265)|fail\.(receiver|decoder|video))$/;
const SETTINGS = ['access', 'resolution', 'codec', 'dlna', 'lyrics', 'stats', 'picture', 'language', 'updates', 'errorReports'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_STATS_BYTES = 8 * 1024;

async function receiveStats(request, env) {
  if (env.STATS_LIMIT) {
    const { success } = await env.STATS_LIMIT.limit({ key: clientAddress(request, env) });
    if (!success) return json({ error: 'too many' }, 429);
  }
  const body = await readJson(request);
  const row = body && validStats(body);
  if (!row) return json({ error: 'invalid' }, 400);
  await env.STATS.prepare(
    `INSERT OR REPLACE INTO daily (id, day, app, android, sdk, maker, model, screen, device, touch, lang, counts, settings, received)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(row.id, row.day, row.app, row.android, row.sdk, row.maker, row.model, row.screen, row.device,
    row.touch ? 1 : 0, row.lang, JSON.stringify(row.counts), JSON.stringify(row.settings), new Date().toISOString()).run();
  return json({ ok: true }, 200);
}

async function deleteStats(request, env) {
  const body = await readJson(request);
  if (!body || typeof body.id !== 'string' || !UUID.test(body.id)) return json({ error: 'invalid' }, 400);
  await env.STATS.prepare('DELETE FROM daily WHERE id = ?').bind(body.id).run();
  return json({ ok: true }, 200);
}

async function readJson(request) {
  const text = await request.text();
  if (text.length > MAX_STATS_BYTES) return null;
  try { return JSON.parse(text); } catch { return null; }
}

/** The summary with only known fields, each checked; null if anything is off. */
function validStats(b) {
  const text = (v, max, pattern) => typeof v === 'string' && v.length > 0 && v.length <= max && (!pattern || pattern.test(v));
  const now = Date.now();
  const day = Date.parse(b.day + 'T00:00:00Z');
  if (!text(b.id, 36, UUID) || !text(b.day, 10, /^\d{4}-\d{2}-\d{2}$/) || !(day > now - 9 * 86400000 && day < now + 2 * 86400000)) return null;
  if (!text(b.app, 40, /^\d+(\.\d+){1,3}(-[0-9A-Za-z.]+)?(\+[0-9A-Za-z.]+)?$/) || !text(b.android, 10, /^[0-9.]+$/)) return null;
  if (!Number.isInteger(b.sdk) || b.sdk < 21 || b.sdk > 99) return null;
  if (!text(b.maker, 40, /^[\x20-\x7e]+$/) || !text(b.model, 40, /^[\x20-\x7e]+$/)) return null;
  if (!['2160p', '1440p', '1080p', '720p', 'smaller'].includes(b.screen) || !['tv', 'car', 'other'].includes(b.device)) return null;
  if (typeof b.touch !== 'boolean' || !text(b.lang, 8, /^[a-z]{2,3}$/)) return null;
  const counts = {};
  if (!b.counts || typeof b.counts !== 'object' || Object.keys(b.counts).length > 40) return null;
  for (const [key, value] of Object.entries(b.counts)) {
    if (!COUNTER.test(key) || !Number.isInteger(value) || value < 1 || value > 100000) return null;
    counts[key] = value;
  }
  const settings = {};
  if (!b.settings || typeof b.settings !== 'object') return null;
  for (const [key, value] of Object.entries(b.settings)) {
    if (!SETTINGS.includes(key)) return null;
    if (typeof value === 'boolean' || (typeof value === 'string' && /^[A-Za-z0-9_. ]{1,20}$/.test(value))) settings[key] = value;
    else return null;
  }
  return { id: b.id, day: b.day, app: b.app, android: b.android, sdk: b.sdk, maker: b.maker, model: b.model,
    screen: b.screen, device: b.device, touch: b.touch, lang: b.lang, counts, settings };
}

// ---- The private statistics page's totals ----

/** Whether the request carries a valid Cloudflare Access token for the stats application. */
async function accessAllowed(request, env) {
  // `wrangler dev --var STATS_LOCAL_TEST:yes` only (never set in wrangler.jsonc): no Access locally.
  if (env.STATS_LOCAL_TEST === 'yes') return true;
  const token = request.headers.get('Cf-Access-Jwt-Assertion');
  if (!token || !env.ACCESS_TEAM || !env.ACCESS_AUD) return false;
  const [head, body, signature] = token.split('.');
  if (!head || !body || !signature) return false;
  try {
    const header = JSON.parse(base64UrlText(head));
    const claims = JSON.parse(base64UrlText(body));
    const issuer = `https://${env.ACCESS_TEAM}.cloudflareaccess.com`;
    const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
    if (claims.iss !== issuer || !audiences.includes(env.ACCESS_AUD) || !(claims.exp * 1000 > Date.now())) return false;
    const certs = await fetch(`${issuer}/cdn-cgi/access/certs`, { cf: { cacheTtl: 3600 } }).then((r) => r.json());
    const jwk = certs.keys.find((k) => k.kid === header.kid);
    if (!jwk || header.alg !== 'RS256') return false;
    const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    return await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, base64UrlBytes(signature), new TextEncoder().encode(`${head}.${body}`));
  } catch {
    return false;
  }
}

function base64UrlBytes(text) {
  const plain = atob(text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4));
  return Uint8Array.from(plain, (c) => c.charCodeAt(0));
}

function base64UrlText(text) {
  return new TextDecoder().decode(base64UrlBytes(text));
}

/** Totals over the last 30 days (devices counted once each), and devices per day. */
async function summary(env) {
  const since = "date('now', '-30 days')";
  const recent = `SELECT * FROM daily WHERE day >= ${since}`;
  // Each device's latest day in the window, for what describes the device (model, version…).
  const latest = `SELECT d.* FROM daily d JOIN (SELECT id, max(day) AS day FROM daily WHERE day >= ${since} GROUP BY id) l
    ON d.id = l.id AND d.day = l.day`;
  const by = (column) => `SELECT ${column} AS key, count(*) AS n FROM (${latest}) GROUP BY 1 ORDER BY n DESC LIMIT 30`;
  const queries = {
    devices7: `SELECT count(DISTINCT id) AS n FROM daily WHERE day >= date('now', '-7 days')`,
    devices30: `SELECT count(DISTINCT id) AS n FROM daily WHERE day >= ${since}`,
    perDay: `SELECT day AS key, count(DISTINCT id) AS n FROM daily WHERE day >= ${since} GROUP BY day ORDER BY day`,
    app: by('app'),
    android: by('android'),
    model: by("maker || ' ' || model"),
    screen: by('screen'),
    device: by('device'),
    touch: by("CASE touch WHEN 1 THEN 'touch' ELSE 'no touch' END"),
    lang: by('lang'),
    counts: `SELECT j.key AS key, sum(j.value) AS n FROM (${recent}) r, json_each(r.counts) j GROUP BY 1 ORDER BY n DESC`,
    failuresByApp: `SELECT r.app || ' · ' || j.key AS key, sum(j.value) AS n FROM (${recent}) r, json_each(r.counts) j
      WHERE j.key LIKE 'fail.%' GROUP BY 1 ORDER BY n DESC LIMIT 30`,
    settings: `SELECT j.key || ' = ' || j.value AS key, count(*) AS n FROM (${latest}) r, json_each(r.settings) j GROUP BY 1 ORDER BY j.key, n DESC`,
  };
  const names = Object.keys(queries);
  const reports = await recentReports(env);
  const results = await env.STATS.batch(names.map((name) => env.STATS.prepare(queries[name])));
  const out = { generated: new Date().toISOString() };
  names.forEach((name, i) => {
    const rows = results[i].results;
    out[name] = name.startsWith('devices') ? rows[0].n : rows;
  });
  out.reports = reports;
  return json(out, 200);
}

/** The latest problem reports (R2 keeps them 90 days), newest first, without their text. */
async function recentReports(env) {
  const all = [];
  let cursor;
  do {
    const page = await env.REPORTS.list({ prefix: 'reports/', include: ['customMetadata'], cursor });
    all.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor && all.length < 2000);
  const reports = all
    .map((o) => ({
      id: o.key.slice('reports/'.length, -'.txt'.length),
      received: o.customMetadata?.received ?? o.uploaded.toISOString(),
      version: o.customMetadata?.version ?? '',
      kind: o.customMetadata?.kind ?? 'manual',
      size: o.size,
    }))
    .sort((a, b) => (a.received < b.received ? 1 : -1));
  return { total: reports.length, latest: reports.slice(0, 100) };
}

/** One report's text, to read on the private page. */
async function readReport(env, id) {
  const object = await env.REPORTS.get(`reports/${id}.txt`);
  if (!object) return json({ error: 'not found' }, 404);
  return new Response(object.body, {
    headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex' },
  });
}
