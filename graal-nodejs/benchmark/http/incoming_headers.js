'use strict';
const common = require('../common.js');
const http = require('http');

const bench = common.createBenchmark(main, {
  c: [50],     // Concurrent connections
  n: [20],     // Number of header lines to append after the common headers
  w: [0, 6],   // Amount of trailing whitespace
});

function main({ c, n, w }) {
  const server = http.createServer((req, res) => {
    res.end();
  });

  server.listen(common.PORT, () => {
    const headers = {
      'Content-Type': 'text/plain',
      'Accept': 'text/plain',
      'User-Agent': 'nodejs-benchmark',
      'Date': new Date().toString(),
      'Cache-Control': 'no-cache'
    };
    for (let i = 0; i < n; i++) {
      // Note:
      // - autocannon does not send header values with OWS
      // - wrk can only send trailing OWS. This is a side-effect of wrk
      // processing requests with http-parser before sending them, causing
      // leading OWS to be stripped.
      headers[`foo${i}`] = `some header value ${i}${' \t'.repeat(w / 2)}`;
    }
    bench.http({
      path: '/',
      connections: c,
      headers
    }, () => {
      server.close();
    });
  });
}
