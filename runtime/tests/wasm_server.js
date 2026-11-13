// The server tests/wasm.rs sends to with reqwest's fetch backend: `node tests/wasm_server.js`, then build the tests
// with TIMVW_WASM_TEST_SERVER=http://127.0.0.1:18765 (see the CI workflow).
// /flaky?id=..: 503 with retry-after-ms the first time for an id, then 200. /echo: echoes the body. /sse: two events.
// /slow?ms=..: 200 after that many milliseconds.
const http = require('http');
const seen = new Set();
http.createServer((req, res) => {
  let chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    if (req.url.startsWith('/flaky?')) {
      if (!seen.has(req.url)) {
        seen.add(req.url);
        res.writeHead(503, {'retry-after-ms': '250'});
        return res.end('busy');
      }
      res.writeHead(200, {'content-type': 'text/plain', 'x-test': 'yes'}); return res.end('ok');
    }
    if (req.url.startsWith('/slow?')) {
      const ms = Number(new URL(req.url, 'http://localhost').searchParams.get('ms')) || 0;
      setTimeout(() => { res.writeHead(200, {'content-type': 'text/plain'}); res.end('slow'); }, ms);
      return;
    }
    if (req.url === '/echo') {
      res.writeHead(200, {'content-type': 'text/plain', 'x-content-type': req.headers['content-type'] || '', 'x-length': req.headers['content-length'] || ''});
      return res.end(body);
    }
    if (req.url === '/sse') {
      res.writeHead(200, {'content-type': 'text/event-stream'});
      res.write('data: {"n":1}\n\n');
      setTimeout(() => { res.write('data: {"n":2}\n\ndata: [DONE]\n\n'); res.end(); }, 50);
      return;
    }
    res.writeHead(404); res.end();
  });
}).listen(18765, '127.0.0.1');
