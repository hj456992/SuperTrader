import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {renderRanch} from '../../web/src/ranch-view.js';
import {apply} from '../../web/src/ranch-ui.js';

const fixture = () => JSON.parse(readFileSync(new URL('./fixtures/profile-agent.json', import.meta.url), 'utf8'));
const profile = ids => ({summary:'QA有限认识', facets:[{category:'沟通',text:'QA受限判断',kind:'inferred',evidenceIds:ids}], uncertainties:[]});
const page = (state, selected='person-lan') => renderRanch(state,{page:selected==='self'?'self':'person',selected,tab:'profile'});

test('QA A01/A10/A22: current and historical self profiles resolve me across both people only', () => {
  const {state} = fixture();
  state.self.profile = profile(['M-self-direct','M-me-in-lan','M-me-in-yu','M-lan-1','M-yu-1','M-context']);
  state.self.analyses = [{...profile(['M-me-in-yu']),summary:'QA历史本人画像'}];
  const html = page(state,'self');
  for(const phrase of ['我喜欢提前一天确认安排。','我喜欢提前确认集合时间。','我临时有事会提前说明。','QA历史本人画像']) assert.ok(html.includes(phrase),phrase);
  for(const phrase of ['我一般周末愿意散步。','我每个周末都参加长跑。','旁人说：']) assert.ok(!html.includes(phrase),phrase);
  assert.ok(html.includes('我的发言'));
  assert.ok(html.includes('来源已不可用'));
});

test('QA A02/A03/A06/A11: person current/history/counterevidence cannot resolve other subjects or books as facts', () => {
  const {state} = fixture();
  const p = state.people[0];
  p.profile = profile(['M-lan-1','M-yu-1','M-me-in-lan','M-context','K-qa-method-0']);
  p.profile.facets[0].counterEvidenceIds=['M-lan-counter','M-yu-1','M-me-in-yu'];
  p.profile.knowledge=[{id:'K-qa-method-0',title:'QA方法',location:'虚构章节',text:'QA方法正文'}];
  p.profile.facets[0].knowledgeIds=['K-qa-method-0'];
  p.analyses=[{...profile(['M-yu-1','M-me-in-lan']),summary:'QA历史对象画像'}];
  const html=page(state);
  assert.ok(html.includes('我一般周末愿意散步。'));
  assert.ok(html.includes('但这个周末我只想休息，请不要替我确认散步。'));
  for(const phrase of ['我每个周末都参加长跑。','我喜欢提前确认集合时间。','我临时有事会提前说明。','旁人说：']) assert.ok(!html.includes(phrase),phrase);
  const factSection=html.split('<h4 class="source-heading">聊天与自述依据</h4>')[1].split('<h4 class="source-heading">书籍方法参考</h4>')[0];
  assert.ok(!factSection.includes('QA方法正文'));
  assert.ok(html.includes('书籍提供理解方法，不是这个人的事实证据。'));
});

test('QA A07/A08/A22: legacy, empty library and no match remain distinguishable', () => {
  const f=fixture();
  f.state.people[0].profile=f.legacyProfile;
  const legacy=page(f.state);
  assert.ok(!legacy.includes('本次画像仅依据已读取材料'));
  for(const [status,yes,no] of [['empty_library','没有可用书籍','未找到相关书籍方法'],['no_match','未找到相关书籍方法','没有可用书籍']]) {
    f.state.people[0].profile={...f.legacyProfile,knowledgeStatus:status};
    const html=page(f.state);
    assert.ok(html.includes(yes)); assert.ok(!html.includes(no));
  }
});

test('QA A13/A21/A23: job belongs to self or its selected person and escapes all progress data', () => {
  const {state}=fixture();
  state.job={id:'qa-"<run>',targetId:'self',kind:'profile',status:'running',phase:'evidence',step:2,maxSteps:6,message:'<iframe>QA进度</iframe>',events:[{type:'internal',message:'QA内部秘密'},{type:'phase',message:'<img>QA已补查'}]};
  const self=page(state,'self');
  assert.ok(self.includes('补查聊天依据'));
  assert.ok(self.includes('&lt;iframe&gt;QA进度&lt;/iframe&gt;'));
  assert.ok(!self.includes('QA内部秘密'));
  assert.ok(!self.includes('<iframe>'));
  for(const id of ['person-lan','person-yu']) {
    const html=page(state,id);
    assert.ok(!html.includes('QA进度'));
    assert.ok(!html.includes('QA已补查'));
    assert.ok(!html.includes('data-action="cancel"'));
    assert.match(html,/data-action="analyze"[^>]*disabled/);
  }
});

// Replace only browser/HTTP boundaries. Real mount, request sequencing and click handlers execute.
function mount(t, initial) {
  let cleanup, current=initial, read, post;
  const listeners={}, timers=new Map(), requests=[];
  let timerId=0;
  const app={innerHTML:'',contains:b=>b.inApp,addEventListener(){},replaceChildren(){this.innerHTML='';}};
  const modal={open:false,contains:()=>false,querySelector:()=>null,querySelectorAll:()=>[],addEventListener(){},close(){this.open=false;}};
  const notice={textContent:'',classList:{add(){},remove(){}}};
  const original=globalThis.document;
  globalThis.document={activeElement:null,querySelector:s=>({'#app':app,'#modal':modal,'#notice':notice}[s]),addEventListener:(type,fn)=>listeners[type]=fn};
  t.after(()=>{cleanup?.(); if(original===undefined)delete globalThis.document; else globalThis.document=original;});
  t.mock.method(globalThis,'setTimeout',(fn,ms)=>{timers.set(++timerId,{fn,ms});return timerId;});
  t.mock.method(globalThis,'clearTimeout',id=>timers.delete(id));
  t.mock.method(globalThis,'fetch',async(url,options)=>{
    const body=options.body?JSON.parse(options.body):undefined;
    requests.push({url,body});
    return {ok:true,json:async()=>body&&post?post(url,body):!body&&read?read():structuredClone(current)};
  });
  apply({effect:fn=>cleanup=fn()});
  return {app,notice,requests,setState:s=>current=s,setRead:fn=>read=fn,setPost:fn=>post=fn,
    click:async(action,extra={})=>listeners.click({target:{closest:()=>({inApp:true,dataset:{action,...extra}})}}),
    poll:()=>{const entry=[...timers].find(([,v])=>[1300,5000].includes(v.ms));assert.ok(entry);timers.delete(entry[0]);return entry[1].fn();},
    dispose:()=>{cleanup();cleanup=null;}};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};

function running() {
  const {state}=fixture();
  state.people[0].profile=profile(['M-lan-1']);
  state.job={id:'qa-run-a',targetId:'person-lan',kind:'profile',status:'running',phase:'knowledge',message:'QA甲独有进度'};
  return state;
}

test('QA A14/A15/A21: cancel then switch to another person rejects late old poll and preserves target',async t=>{
  const initial=running(), ui=mount(t,initial);await settle();
  await ui.click('person',{id:'person-lan'});
  const late=deferred();ui.setRead(()=>late.promise);const oldPoll=ui.poll();await settle();
  ui.setRead(null);
  ui.setState({...initial,job:{...initial.job,status:'cancelled'}});
  await ui.click('cancel',{runId:'qa-run-a'});
  await ui.click('person',{id:'person-yu'});
  late.resolve(initial);await oldPoll;
  assert.ok(ui.app.innerHTML.includes('虚构小屿'));
  assert.ok(!ui.app.innerHTML.includes('QA甲独有进度'));
  assert.ok(!ui.app.innerHTML.includes('我一般周末愿意散步。'));
  assert.doesNotMatch(ui.app.innerHTML,/data-action="analyze"[^>]*disabled/);
  assert.deepEqual(ui.requests.filter(r=>r.url.endsWith('-cancel')).map(r=>r.body),[{runId:'qa-run-a',revision:7}]);
  assert.equal(ui.notice.textContent,'');
});

test('QA A14/A15: in-flight cancel blocks duplicate requests; stale button cannot stop newly observed run',async t=>{
  const initial=running(),ui=mount(t,initial);await settle();
  const response=deferred();ui.setPost(()=>response.promise);
  const cancellation=ui.click('cancel',{runId:'qa-run-a'});await settle();
  await ui.click('cancel',{runId:'qa-run-a'});
  assert.equal(ui.requests.filter(r=>r.url.endsWith('-cancel')).length,1);
  const next={...initial,job:{...initial.job,id:'qa-run-b'}};
  ui.setState(next);await ui.poll();response.resolve({});await cancellation;
  await ui.click('cancel',{runId:'qa-run-a'});
  assert.equal(ui.requests.filter(r=>r.url.endsWith('-cancel')).length,1);
  assert.ok(ui.app.innerHTML.includes('data-run-id="qa-run-b"'));
});

test('QA A16/A22: disposal during an outstanding state read neither cancels nor revives the page',async t=>{
  const initial=running(),ui=mount(t,initial);await settle();
  const response=deferred();ui.setRead(()=>response.promise);const poll=ui.poll();await settle();
  ui.dispose();response.resolve({...initial,job:{...initial.job,status:'done'}});await poll;
  assert.equal(ui.app.innerHTML,'');
  assert.equal(ui.requests.filter(r=>r.body).length,0);
  assert.equal(ui.notice.textContent,'');
});
