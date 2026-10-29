// What the Worker accepts and refuses of the usage statistics (CastBay Desktop and the Android app), with a stand-in
// for the database. Run it with: node web/worker/stats.test.mjs
import worker from './index.js';

const rows = [];
const env = {
  STATS: {
    prepare(sql) {
      return { sql, bind(...args) { return { sql, args, run: async () => { rows.push({ sql, args }); } }; } };
    },
    async batch(list) { list.forEach((l) => rows.push({ sql: l.sql, args: l.args })); },
  },
};

const day = new Date().toISOString().slice(0, 10);
const good = {
  platform: 'desktop', id: '0b8d6a52-1c3e-4a4b-9d52-6e1a0b8f2c47', day, app: '0.1.4', os: 'linux', osVersion: 'ubuntu 26.04',
  arch: 'x86_64', session: 'wayland', screen: '1080p', lang: 'zh', encoders: ['nvenc', 'x264'],
  counts: { 'cast.screen': 2, 'length.1to10m': 1, 'encoder.nvenc': 2, 'fps.30': 2, 'bitrate.6to11': 2, 'sound.on': 2, 'fail.encoder': 1 },
  settings: { encoder: 'auto', language: 'auto', updates: true, errorReports: false },
};

async function post(path, body) {
  const response = await worker.fetch(new Request('https://x/' + path, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) }), env);
  return response.status;
}

let failed = 0;
const check = (name, actual, expected) => { if (actual !== expected) { failed++; console.log('FAIL', name, actual, '!=', expected); } else console.log('ok  ', name); };

check('a good desktop summary', await post('api/stats', good), 200);
check('it went to desktop_daily', rows.at(-1).sql.includes('desktop_daily'), true);
check('the encoders kept as JSON', rows.at(-1).args[9], '["nvenc","x264"]');
const bad = (name, change) => post('api/stats', { ...good, ...change }).then((s) => check(name, s, 400));
await bad('unknown counter', { counts: { 'cast.screen': 1, 'something.else': 1 } });
await bad('a count of zero', { counts: { 'cast.screen': 0 } });
await bad('unknown os', { os: 'macos' });
await bad('unknown encoder', { encoders: ['qsv'] });
await bad('unknown setting', { settings: { password: 'x' } });
await bad('a name in a setting', { settings: { encoder: 'My PC!' } });
await bad('bad id', { id: 'not-a-uuid' });
await bad('a day too old', { day: '2020-01-01' });
await bad('bad version', { app: '1.0;drop' });
await bad('bad os version', { osVersion: "x'; --" });
check('not JSON', await post('api/stats', 'nope'), 400);
const before = rows.length;
check('the delete covers both tables', await post('api/stats/delete', { id: good.id }), 200);
check('two statements', rows.length - before, 2);
check('both tables named', rows.slice(before).map((r) => r.sql.includes('desktop_daily')).sort().join(), 'false,true');
// An Android summary still goes to its own table.
const android = { id: good.id, day, app: '1.8.8', android: '14', sdk: 34, maker: 'Sony', model: 'XR', screen: '1080p', device: 'tv', touch: false, lang: 'en', counts: { 'cast.airplay.mirror': 1 }, settings: { access: 'open' } };
check('an Android summary', await post('api/stats', android), 200);
check('into daily', /INTO daily /.test(rows.at(-1).sql), true);
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
