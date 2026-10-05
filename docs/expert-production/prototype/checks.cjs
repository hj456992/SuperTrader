const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const context = {}; vm.createContext(context);
const file = path.join(__dirname, 'core.js');
if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
const C = context.ReviewDemo;
assert.ok(C, '概要审核状态模块必须存在');
let count = 0;
function test(name, f) { f(); count++; console.log('PASS', name); }
function send(s, text, id = 'op-' + (s.seq + 1), snapshot = C.snapshot(s)) { return C.send(s, {text, id, snapshot}); }
test('明确确认仅完成概要，停在子专家入口', () => { const s=send(C.seed(), '确认概要 v1'); assert.equal(s.revisions[0].review_status, 'completed'); assert.equal(s.phase, 'specialists'); assert.equal(s.reviews[0].scope, 'summary.full'); assert.ok(s.reviews[0].digest); });
for (const text of ['不通过','如果改完就通过','修改后就可以','他说“通过”','请解释“确认概要 v1”','可以','不确认概要 v1','确认概要 v1 的标题','确认概要 v1？','确认概要 v1，然后再改一下','通过了吗']) {
 test('不误批准：'+text, () => assert.notEqual(send(C.seed(),text).revisions[0].review_status,'completed'));
}
test('问答及其后的可以不推进', () => {let s=send(C.seed(),'为什么需要按逻辑分组？'); s=send(s,'可以'); assert.equal(s.phase,'summary'); assert.equal(s.current,1); assert.equal(s.reviews.length,0);});
test('无原因否决保存待答问题，刷新后补原因创建新稿', () => {let s=send(C.seed(),'不通过'); assert.equal(s.revisions[0].review_status,'needs_reason'); assert.equal(s.clarifications[0].status,'open'); s=JSON.parse(JSON.stringify(s)); s=send(s,'补充原因：需要说明适用边界'); assert.equal(s.current,2); assert.equal(s.revisions[0].review_status,'changes_requested'); assert.equal(s.revisions[1].review_status,'pending'); assert.equal(s.clarifications[0].status,'resolved'); assert.equal(s.messages.find(m=>m.role==='admin').text,'不通过');});
test('等待原因时提问不能被当作原因',()=> {let s=send(C.seed(),'不通过'); s=send(s,'为什么需要修改？'); assert.equal(s.current,1); assert.equal(s.clarifications[0].status,'open');});
test('否决有原因保留原文，新稿可比较',()=> {const original=C.seed().revisions[0].body; const s=send(C.seed(),'不通过：需要说明适用边界'); assert.equal(s.current,2); assert.equal(s.revisions[0].body,original); assert.notEqual(s.revisions[1].body,original); assert.equal(s.revisions[1].reason,'需要说明适用边界'); assert.equal(s.reviews[0].reason,'需要说明适用边界');});
test('旧版确认冲突不能批准新版',()=> {const old=C.snapshot(C.seed()); let s=send(C.seed(),'不通过：补充边界'); s=send(s,'确认概要 v1','stale',old); assert.equal(s.lastResult.code,'VERSION_CONFLICT'); assert.equal(s.revisions[1].review_status,'pending');});
test('内容摘要、范围和展示对象必须匹配',()=> {for (const key of ['digest','scope','object']) {const s=C.seed(), snap=C.snapshot(s); snap[key]='wrong'; const out=send(s,'确认概要 v1','bad',snap); assert.equal(out.lastResult.code,'PRESENTATION_CONFLICT'); assert.equal(out.reviews.length,0);}});
test('历史焦点不改变生产进度，旧稿不能批准',()=> {let s=send(C.seed(),'不通过：补充边界'); s=C.focus(s,1); assert.equal(s.phase,'summary'); assert.equal(s.current,2); s=send(s,'确认概要 v1'); assert.equal(s.lastResult.code,'VERSION_CONFLICT');});
test('同一请求重试不重复写消息或改稿',()=> {const first=send(C.seed(),'不通过：补充边界','same'); const out=send(first,'不通过：补充边界','same'); assert.equal(out.current,2); assert.equal(out.messages.length,first.messages.length); assert.equal(out.reviews.length,1);});
test('同一请求身份不能复用于另一条原话',()=> {const first=send(C.seed(),'为什么？','same'); const out=send(first,'确认概要 v1','same'); assert.equal(out.lastResult.code,'IDEMPOTENCY_CONFLICT'); assert.equal(out.reviews.length,0);});
test('概要确认后不生成专家，确认记录保持',()=> {let s=send(C.seed(),'确认概要 v1'); s=send(s,'生成第一个专家'); assert.equal(s.phase,'specialists'); assert.equal(s.revisions.length,1); assert.equal(s.reviews.length,1);});
test('序列化恢复保留聊天、焦点、审核和未决问题',()=> {const s=send(C.seed(),'不通过'); const saved=JSON.parse(JSON.stringify(s)); assert.equal(C.isValid(saved),true); assert.equal(saved.messages.length,s.messages.length); assert.equal(saved.clarifications[0].question,s.clarifications[0].question); assert.equal(C.isValid({schema:1}),false);});
test('补原因追加审核记录，最初无原因否决不可改写',()=> {let s=send(C.seed(),'不通过');const original=JSON.stringify(s.reviews[0]);s=send(s,'补充原因：需要说明适用边界');assert.equal(s.reviews.length,2);assert.equal(JSON.stringify(s.reviews[0]),original);assert.equal(s.reviews[0].reason,null);assert.equal(s.reviews[1].reason,'需要说明适用边界');assert.equal(s.reviews[1].original,'补充原因：需要说明适用边界');});
test('同一请求原话相同但展示快照不同必须冲突',()=> {const first=send(C.seed(),'为什么？','same');for(const key of ['object','version','digest','scope']){const snap=C.snapshot(first);snap[key]='different';const out=send(first,'为什么？','same',snap);assert.equal(out.lastResult.code,'IDEMPOTENCY_CONFLICT');assert.equal(out.messages.length,first.messages.length);}});
console.log(`${count} 项状态交互验证通过`);
