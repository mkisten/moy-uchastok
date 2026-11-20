const TEAM_LOG = '_team_operations_v1';
const TABLE_FIELDS = {
  areas: ['name', 'type', 'createdAt', 'updatedAt'],
  entrances: ['areaId', 'entranceNumber', 'createdAt', 'updatedAt'],
  floors: ['entranceId', 'floorNumber', 'apartmentCount', 'sortOrder', 'updatedAt'],
  apartments: ['entranceId', 'floorId', 'apartmentNumber', 'label', 'status', 'phone', 'comment', 'lastVisitAt', 'createdAt', 'updatedAt', 'deletedAt', 'hasChild'],
  history: ['apartmentId', 'visitDate', 'previousStatus', 'newStatus', 'comment', 'eventType'],
};

/** Запустить из редактора Apps Script, привязанного к новой закрытой таблице. */
function setupTeam() {
  const sheet = SpreadsheetApp.getActiveSpreadsheet();
  if (!sheet) throw new Error('Откройте Apps Script из Google Таблицы');
  withLock_(() => {
    const props = PropertiesService.getScriptProperties();
    const existing = props.getProperty('teamSheet');
    if (existing && existing !== sheet.getId()) throw new Error('Проект уже привязан к другой таблице');
    const log = sheet.getSheetByName(TEAM_LOG) || sheet.insertSheet(TEAM_LOG);
    if (log.getLastRow() === 0) log.appendRow(['operationId', 'operationJson']);
    props.setProperty('teamSheet', sheet.getId());
  });
}

/** Скопировать код из журнала выполнения редактора. Срок действия: 24 часа. */
function createMemberInvite() { createInvite_('member'); }
function createOwnerInvite() { createInvite_('owner'); }

function createInvite_(role) {
  withLock_(() => {
    const props = PropertiesService.getScriptProperties();
    if (!props.getProperty('teamSheet')) throw new Error('Сначала запустите setupTeam');
    const code = Utilities.getUuid().replace(/-/g, '').toLowerCase();
    const now = Date.now();
    const all = props.getProperties();
    for (const key of Object.keys(all)) {
      if (key.startsWith('invite:') && JSON.parse(all[key]).expires < now) props.deleteProperty(key);
    }
    props.setProperty('invite:' + hash_(code), JSON.stringify({role, expires: now + 24 * 3600000}));
    console.log('Одноразовый код: ' + code);
  });
}

/** Показывает идентификаторы устройств. Секретные ключи в журнал не выводятся. */
function listDevices() {
  const all = PropertiesService.getScriptProperties().getProperties();
  for (const key of Object.keys(all)) {
    if (key.startsWith('device:')) {
      const device = JSON.parse(all[key]);
      console.log(device.id + ' · ' + device.role + ' · ' + new Date(device.joinedAt).toISOString());
    }
  }
}

/** Вставьте ID из listDevices в диалог, запустив эту функцию из редактора. */
function revokeDevice() {
  const id = SpreadsheetApp.getUi().prompt('ID устройства для отключения').getResponseText().trim();
  if (!id) return;
  withLock_(() => {
    const props = PropertiesService.getScriptProperties();
    const all = props.getProperties();
    const key = Object.keys(all).find(k => k.startsWith('device:') && JSON.parse(all[k]).id === id);
    if (!key) throw new Error('Устройство не найдено');
    props.deleteProperty(key);
    // Повтор предшествующего join не может заново выдать отозванный доступ.
    console.log('Доступ отозван: ' + id);
  });
}

function doPost(e) {
  try {
    const body = e && e.postData && e.postData.contents;
    if (typeof body !== 'string' || body.length > 2_500_000) fail_('Неверный размер запроса');
    let request;
    try { request = JSON.parse(body); } catch (_) { fail_('Неверный формат запроса'); }
    if (!request || request.protocol !== 1 || typeof request.token !== 'string' || !/^[a-f0-9]{64}$/.test(request.token)) {
      fail_('Неверный протокол или ключ устройства');
    }
    return response_(withLock_(() => handle_(request)));
  } catch (e) {
    if (!(e instanceof TeamError_)) console.error('Team sync error: ' + e.name);
    return response_({ok: false, error: e instanceof TeamError_ ? e.message : 'Сервис временно недоступен. Повторите синхронизацию.'});
  }
}

function handle_(request) {
  const props = PropertiesService.getScriptProperties();
  const sheetId = props.getProperty('teamSheet');
  if (!sheetId) fail_('Владелец должен настроить таблицу');
  const key = 'device:' + hash_(request.token);
  const log = SpreadsheetApp.openById(sheetId).getSheetByName(TEAM_LOG);
  if (!log || log.getLastRow() === 0) fail_('Журнал команды недоступен');
  const head = log.getLastRow() - 1;
  if (request.action === 'join') return join_(request, props, key, head);
  if (!props.getProperty(key)) fail_('Доступ устройства отозван или не настроен');
  const seedOwner = props.getProperty('seedOwner');
  if (seedOwner && seedOwner !== key) fail_('Владелец ещё загружает исходные данные команды. Повторите позже.');
  if (request.action === 'finishSeed') {
    if (seedOwner === key) props.deleteProperty('seedOwner');
    return {ok: true};
  }
  if (request.action === 'push') {
    const operations = request.operations;
    if (!Array.isArray(operations) || operations.length > 50) fail_('Неверный размер пакета');
    const existing = new Set(head ? log.getRange(2, 1, head, 1).getValues().flat() : []);
    const rows = [];
    for (const op of operations) {
      const json = validateOperation_(op);
      if (!existing.has(op.opId)) {
        existing.add(op.opId);
        rows.push([op.opId, json]);
      }
    }
    if (rows.length) {
      const last = head + 1 + rows.length;
      if (last > log.getMaxRows()) log.insertRowsAfter(log.getMaxRows(), Math.max(100, last - log.getMaxRows()));
      log.getRange(head + 2, 1, rows.length, 2).setValues(rows);
      SpreadsheetApp.flush();
    }
    return {ok: true};
  }
  if (request.action === 'pull') {
    const after = request.after;
    const until = request.until === undefined ? head : request.until;
    if (!Number.isSafeInteger(after) || !Number.isSafeInteger(until) || after < 0 || until < after || until > head) {
      fail_('Журнал изменился. Обратитесь к владельцу команды.');
    }
    const cursor = Math.min(until, after + 50);
    const operations = cursor === after ? [] :
      log.getRange(after + 2, 2, cursor - after, 1).getValues().flat().map(value => JSON.parse(value));
    return {ok: true, cursor, until, operations};
  }
  fail_('Неизвестное действие');
}

function join_(request, props, key, head) {
  if (typeof request.invite !== 'string' || !/^[a-f0-9]{32}$/.test(request.invite)) fail_('Код приглашения неверен');
  const inviteKey = 'invite:' + hash_(request.invite);
  const saved = props.getProperty(key);
  if (saved && JSON.parse(saved).inviteKey === inviteKey) return {ok: true};
  const value = props.getProperty(inviteKey);
  if (!value) fail_('Код приглашения неверен, использован или устарел');
  const invite = JSON.parse(value);
  if (invite.usedBy || invite.expires < Date.now()) fail_('Код приглашения неверен, использован или устарел');
  const seedOwner = props.getProperty('seedOwner');
  if (request.seed && (invite.role !== 'owner' || head !== 0 || (seedOwner && seedOwner !== key))) {
    fail_('Загрузить данные телефона может владелец новой пустой команды');
  }
  if (request.seed) props.setProperty('seedOwner', key);
  const device = {id: Utilities.getUuid(), role: invite.role, joinedAt: Date.now(), inviteKey};
  invite.usedBy = key;
  const updates = {};
  updates[key] = JSON.stringify(device);
  updates[inviteKey] = JSON.stringify(invite);
  props.setProperties(updates);
  return {ok: true, deviceId: device.id};
}

function validateOperation_(op) {
  if (!op || !Object.prototype.hasOwnProperty.call(TABLE_FIELDS, op.table) ||
      typeof op.opId !== 'string' || !/^[a-f0-9-]{36}$/.test(op.opId) ||
      typeof op.uuid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(op.uuid)) fail_('Некорректная операция');
  if (op.remove !== true) {
    const fields = op.fields;
    if (typeof op.create !== 'boolean' || !fields || Array.isArray(fields) || typeof fields !== 'object' ||
        Object.keys(fields).some(field => !TABLE_FIELDS[op.table].includes(field) ||
          (fields[field] !== null && !['string','number','boolean'].includes(typeof fields[field]))) ||
        (op.create && TABLE_FIELDS[op.table].some(field => !Object.prototype.hasOwnProperty.call(fields, field)))) {
      fail_('Некорректные поля операции');
    }
    if (op.ifVisitAt !== undefined && (op.table !== 'apartments' || op.create ||
        !Number.isSafeInteger(op.ifVisitAt) || !Object.prototype.hasOwnProperty.call(fields, 'lastVisitAt'))) {
      fail_('Некорректное условие отмены визита');
    }
  }
  const json = JSON.stringify(op);
  if (json.length > 45_000) fail_('Одна из записей слишком большая для Google Таблицы');
  return json;
}

function withLock_(action) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) fail_('Команда синхронизируется. Повторите попытку.');
  try { return action(); } finally { lock.releaseLock(); }
}

function hash_(value) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, value, Utilities.Charset.UTF_8)
    .map(n => (n & 255).toString(16).padStart(2, '0')).join('');
}

class TeamError_ extends Error {}
function fail_(message) { throw new TeamError_(message); }
function response_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
