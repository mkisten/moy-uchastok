const { createHash, randomUUID } = require('node:crypto');
const { readFileSync } = require('node:fs');
const { createServer } = require('node:http');
const { join } = require('node:path');
const { createContext, runInContext } = require('node:vm');

function harness() {
  const properties = new Map();
  const rows = [];
  const messages = [];
  let maxRows = 1000;
  const sheet = {
    getLastRow: () => rows.length,
    getMaxRows: () => maxRows,
    insertRowsAfter: (_after, count) => { maxRows += count; },
    appendRow: row => rows.push(row),
    getRange: (row, col, count, width) => ({
      getValues: () => rows.slice(row - 1, row - 1 + count).map(value => value.slice(col - 1, col - 1 + width)),
      setValues: values => { rows.splice(row - 1, count, ...values); },
    }),
  };
  const book = {
    getId: () => 'local-test-book',
    getSheetByName: () => sheet,
  };
  const context = createContext({
    console: { log: value => messages.push(value), error: value => messages.push(value) },
    SpreadsheetApp: {
      getActiveSpreadsheet: () => book,
      openById: () => book,
      flush: () => {},
      getUi: () => ({ prompt: () => ({ getResponseText: () => context.deviceToRevoke }) }),
    },
    PropertiesService: { getScriptProperties: () => ({
      getProperty: key => properties.get(key) ?? null,
      setProperty: (key, value) => properties.set(key, value),
      getProperties: () => Object.fromEntries(properties),
      deleteProperty: key => properties.delete(key),
      setProperties: values => Object.entries(values).forEach(([key, value]) => properties.set(key, value)),
    }) },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    Utilities: {
      getUuid: randomUUID,
      computeDigest: (_algorithm, value) => [...createHash('sha256').update(value).digest()],
      DigestAlgorithm: { SHA_256: 'sha256' },
      Charset: { UTF_8: 'utf8' },
    },
    ContentService: {
      createTextOutput: value => ({ setMimeType: () => value }),
      MimeType: { JSON: 'application/json' },
    },
  });
  runInContext(readFileSync(join(__dirname, '..', 'Code.gs'), 'utf8'), context);
  context.setupTeam();
  return {
    invite(role) {
      if (role === 'owner') context.createOwnerInvite();
      else if (role === 'member') context.createMemberInvite();
      else throw new Error('Invalid fixture role');
      return messages.at(-1).split(': ')[1];
    },
    request(request) {
      return JSON.parse(context.doPost({postData: {contents: JSON.stringify({protocol: 1, ...request})}}));
    },
    revoke(id) {
      context.deviceToRevoke = id;
      context.revokeDevice();
    },
  };
}

module.exports = { harness };

if (require.main === module) {
  const app = harness();
  const fixtures = { owner: app.invite('owner'), member: app.invite('member') };
  createServer(async (req, res) => {
    try {
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'GET' && req.url === '/fixtures') {
        res.end(JSON.stringify(fixtures));
        return;
      }
      if (req.method === 'POST' && req.url === '/exec') {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        res.end(JSON.stringify(app.request(JSON.parse(Buffer.concat(chunks)))));
        return;
      }
      res.writeHead(404).end();
    } catch (_) {
      res.writeHead(500).end();
    }
  }).listen(18766, '127.0.0.1', () => console.log('Test Apps Script endpoint ready'));
}
