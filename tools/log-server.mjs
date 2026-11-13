// Tiny log receiver for the Speaker Android app. No dependencies, no settings needed.
//   Window 1:  node tools\log-server.mjs
//   Window 2:  %LOCALAPPDATA%\ngrok\ngrok.exe http 8787
// The app POSTs log batches to https://<ngrok-domain>/log; they're printed here live and appended to
// .ai-workspace/logs/device.log (Claude can read that file directly).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.LOG_TOKEN || '';
const LOG_FILE = process.env.LOG_FILE || path.join(repoRoot, '.ai-workspace', 'logs', 'device.log');

fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });
const out = fs.createWriteStream(LOG_FILE, { flags: 'a' });
let lastSource = '';

function write(line) {
  out.write(line + '\n');
  console.log(line);
}

// ---- Self-update: serve the newest build from dist/ (version read from app/build.gradle.kts) ----
function latestBuild() {
  const gradle = fs.readFileSync(path.join(repoRoot, 'app', 'build.gradle.kts'), 'utf8');
  const versionCode = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
  const versionName = (gradle.match(/versionName\s*=\s*"([^"]+)"/) || [])[1];
  const apk = path.join(repoRoot, 'dist', `Speaker-${versionName}-debug.apk`);
  if (!versionCode || !versionName || !fs.existsSync(apk)) return null;
  return { versionCode, versionName, apk, size: fs.statSync(apk).size };
}

function handleUpdate(req, res) {
  const build = latestBuild();
  if (!build) {
    res.writeHead(404, { 'content-type': 'application/json' }).end('{"error":"no built APK in dist/ for the current version"}');
    console.log(`[update] ${req.url} -> no APK available`);
    return;
  }
  if (req.url === '/update/latest.json') {
    const { versionCode, versionName, size } = build;
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ versionCode, versionName, size }));
    console.log(`[update] phone checked for updates -> offering v${versionName} (${versionCode})`);
    return;
  }
  res.writeHead(200, {
    'content-type': 'application/vnd.android.package-archive',
    'content-length': build.size,
    'content-disposition': `attachment; filename="Speaker-${build.versionName}.apk"`,
  });
  fs.createReadStream(build.apk).pipe(res);
  console.log(`[update] phone is downloading v${build.versionName} (${build.size} bytes)`);
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && (req.url === '/' || req.url === '/health')) {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('speaker log server ok\n');
    return;
  }
  if (req.method === 'GET' && (req.url === '/update/latest.json' || req.url === '/update/apk')) {
    handleUpdate(req, res);
    return;
  }
  // Any POST path is accepted (/, /log, ...) unless LOG_TOKEN is set, then only /log/<token>.
  if (req.method !== 'POST' || (TOKEN && req.url !== `/log/${TOKEN}`)) {
    res.writeHead(404).end();
    return;
  }
  let body = '';
  let size = 0;
  req.on('data', (chunk) => {
    size += chunk.length;
    if (size > 2_000_000) { res.writeHead(413).end(); req.destroy(); return; }
    body += chunk;
  });
  req.on('end', () => {
    if (!String(req.headers['content-type'] || '').includes('json')) {
      // App v0.3+ sends plain text: first line "# device | app version", then log lines.
      const [head, ...rest] = body.replace(/\n$/, '').split('\n');
      if (head !== lastSource) { write(`\n===== ${new Date().toISOString()}  ${head.replace(/^# /, '')} =====`); lastSource = head; }
      rest.forEach(write);
      res.writeHead(204).end();
      return;
    }
    try {
      const data = JSON.parse(body);
      const source = `${data.device || '?'} | app ${data.app || '?'}`;
      if (source !== lastSource) {
        write(`\n===== ${new Date().toISOString()}  ${source} =====`);
        lastSource = source;
      }
      for (const line of data.lines || []) write(String(line));
      res.writeHead(204).end();
    } catch (e) {
      res.writeHead(400).end('bad json');
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[log-server] listening on http://127.0.0.1:${PORT}, writing to ${LOG_FILE}`);
});
