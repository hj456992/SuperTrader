const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('focused member filter keeps its identity while polling renders new matching records', async () => {
  const elements = new Map();
  const makeElement = (id = '') => ({
    id,
    dataset: {},
    className: '',
    disabled: false,
    value: '',
    scrollTop: 0,
    scrollHeight: 500,
    clientHeight: 300,
    textContent: '',
    _html: '',
    classList: { add() {}, remove() {} },
    focus() { document.activeElement = this; },
    set innerHTML(value) { this._html = value; },
    get innerHTML() { return this._html; },
  });

  const root = makeElement('wechat-app');
  Object.defineProperty(root, 'innerHTML', {
    set(value) {
      this._html = value;
      if (!value.includes('capture-shell')) return;
      elements.set('.capture-shell', makeElement());
      const ids = [
        'collector-status', 'collector-title', 'collector-detail', 'last-captured',
        'last-attempt', 'collector-action', 'message-count', 'member-count',
        'observation-count', 'duplicate-count', 'coverage-description',
        'segment-summary', 'member-filter', 'message-log',
      ];
      for (const id of ids) {
        if (!elements.has(`#${id}`)) elements.set(`#${id}`, makeElement(id));
      }
      elements.get('#member-filter').value = 'all';
    },
    get() { return this._html || ''; },
  });

  const toast = makeElement('toast');
  const handlers = {};
  const document = {
    activeElement: null,
    querySelector(selector) {
      if (selector === '#wechat-app') return root;
      if (selector === '#toast') return toast;
      return elements.get(selector) || null;
    },
    addEventListener(type, handler) { handlers[type] = handler; },
  };

  let scheduledPoll;
  const window = {
    setTimeout(callback, delay) {
      if (delay === 2000) scheduledPoll = callback;
      return 1;
    },
    clearTimeout() {},
    addEventListener() {},
  };

  const syntheticMessage = index => ({
    id: `synthetic-${index}`,
    memberId: 'member-a',
    author: '测试成员甲',
    text: `合成记录 ${index}`,
    kind: 'text',
    capturedAt: '2026-09-23T10:00:00+08:00',
    confidence: 0.9,
  });
  const syntheticState = count => ({
    group: { name: '卧底不追高', memberCount: 2, messageCount: count },
    collector: { enabled: true, status: 'capturing', detail: '合成测试状态' },
    members: [
      { id: 'member-a', name: '测试成员甲' },
      { id: 'member-b', name: '测试成员乙' },
    ],
    segments: [{
      id: 'synthetic-segment',
      createdAt: null,
      gapBefore: false,
      messages: Array.from({ length: count }, (_, index) => syntheticMessage(index + 1)),
    }],
    stats: { observations: count, duplicates: 0 },
    coverage: { complete: false, description: '仅用于合成测试' },
  });

  let fetchCount = 0;
  const fetch = async () => ({
    ok: true,
    json: async () => syntheticState(++fetchCount),
  });
  const context = vm.createContext({
    document,
    window,
    fetch,
    AbortController,
    console,
    setTimeout,
    clearTimeout,
  });
  const scriptPath = path.join(__dirname, '../../web/public/wechat.js');
  vm.runInContext(fs.readFileSync(scriptPath, 'utf8'), context, { filename: scriptPath });
  await new Promise(resolve => setImmediate(resolve));

  const filter = elements.get('#member-filter');
  filter.value = 'member-a';
  filter.focus();
  handlers.change({ target: filter });
  assert.equal(typeof scheduledPoll, 'function', 'the UI should schedule its two-second poll');

  await scheduledPoll();

  assert.strictEqual(elements.get('#member-filter'), filter, 'polling must preserve the select element');
  assert.strictEqual(document.activeElement, filter, 'polling must preserve select focus');
  assert.equal(filter.value, 'member-a', 'polling must preserve the selected member');
  assert.equal(elements.get('#message-count').textContent, '2', 'polling must update the message counter');
  assert.match(elements.get('#message-log').innerHTML, /合成记录 2/, 'polling must render the new matching record');
});
