const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { test } = require('node:test');
const { harness } = require('./local-server.cjs');

const operation = () => ({
  opId: randomUUID(), table: 'areas', uuid: randomUUID(), create: true,
  fields: {name: 'Тест', type: 'MULTI', createdAt: 1, updatedAt: 1},
});

test('одноразовые приглашения, повтор запроса join и отзыв устройства', () => {
  const server = harness();
  const invite = server.invite('member');
  const body = {action: 'join', invite, token: 'a'.repeat(64), seed: false};
  const joined = server.request(body);
  assert.equal(joined.ok, true);
  assert.equal(server.request(body).ok, true);
  assert.equal(server.request({...body, token: 'b'.repeat(64)}).ok, false);
  server.revoke(joined.deviceId);
  assert.equal(server.request(body).ok, false);
  assert.equal(server.request({token: body.token, action: 'pull', after: 0}).ok, false);
});

test('начальная загрузка, дедупликация и граница чтения журнала', () => {
  const server = harness();
  const owner = 'a'.repeat(64);
  const member = 'b'.repeat(64);
  const ownerInvite = server.invite('owner');
  const memberInvite = server.invite('member');
  assert.equal(server.request({token: member, action: 'join', invite: memberInvite, seed: true}).ok, false);
  assert.equal(server.request({token: owner, action: 'join', invite: ownerInvite, seed: true}).ok, true);
  assert.equal(server.request({token: member, action: 'join', invite: memberInvite, seed: false}).ok, true);
  assert.equal(server.request({token: member, action: 'push', operations: [operation()]}).ok, false);
  const first = Array.from({length: 50}, operation);
  const last = operation();
  assert.equal(server.request({token: owner, action: 'push', operations: first}).ok, true);
  assert.equal(server.request({token: owner, action: 'push', operations: first}).ok, true);
  assert.equal(server.request({token: owner, action: 'push', operations: [last]}).ok, true);
  assert.equal(server.request({token: owner, action: 'finishSeed'}).ok, true);
  const page = server.request({token: member, action: 'pull', after: 0});
  assert.equal(page.operations.length, 50);
  assert.equal(page.until, 51);
  assert.equal(server.request({token: owner, action: 'push', operations: [operation()]}).ok, true);
  const end = server.request({token: member, action: 'pull', after: page.cursor, until: page.until});
  assert.deepEqual(end.operations, [last]);
  assert.equal(end.cursor, 51);
  assert.equal(server.request({token: member, action: 'pull', after: 51}).operations.length, 1);
  const bad = operation();
  bad.fields.other = 'bad field';
  assert.equal(server.request({token: owner, action: 'push', operations: [bad]}).ok, false);
});
