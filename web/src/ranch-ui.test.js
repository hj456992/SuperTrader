import test from 'node:test';
import assert from 'node:assert/strict';
import { requestPayload, mutationOwnsContext, apply } from './ranch-ui.js';
test('an open form keeps its captured revision after polling advances state',()=>{
 const form={revision:3,name:'older form value'};
 assert.deepEqual(requestPayload(form,4),{revision:3,name:'older form value'});
 assert.deepEqual(requestPayload({name:'immediate action'},4),{revision:4,name:'immediate action'});
 assert.equal(requestPayload({revision:0,name:'initial form'},5).revision,0);
});
test('a slow mutation cannot own a dialog or page opened after it started',()=>{
 const origin={dialogEpoch:1,page:'person',selected:'a',tab:'profile'};
 assert.equal(mutationOwnsContext(origin,{...origin}),true);
 assert.equal(mutationOwnsContext(origin,{...origin,dialogEpoch:2}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,selected:'b'}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,page:'library'}),false);
 assert.equal(mutationOwnsContext(origin,{...origin,tab:'materials'}),false);
});

// Only the browser and HTTP boundary are replaced; apply mounts the real UI and handlers.
function mounted(t,initial) {
 const listeners={},timers=new Map();let timerId=0,cleanup,current=initial,read,write;
 const app={innerHTML:'',contains:b=>b.inApp===true,addEventListener(){},replaceChildren(){this.innerHTML='';}};
 const modal={open:false,innerHTML:'',showModal(){this.open=true;},contains:form=>form.inModal===true,querySelector:()=>null,querySelectorAll:()=>[],addEventListener(){},close(){this.open=false;}};
 const notice={textContent:'',classList:{add(){},remove(){}}};
 const oldDocument=globalThis.document;
 globalThis.document={activeElement:null,querySelector:s=>({'#app':app,'#modal':modal,'#notice':notice}[s]),addEventListener:(type,fn)=>{listeners[type]=fn;}};
 t.after(()=>{cleanup?.();if(oldDocument===undefined)delete globalThis.document;else globalThis.document=oldDocument;});
 t.mock.method(globalThis,'setTimeout',(fn,ms)=>{timers.set(++timerId,{fn,ms});return timerId;});
 t.mock.method(globalThis,'clearTimeout',id=>timers.delete(id));
 const requests=[];
 t.mock.method(globalThis,'fetch',async(url,options)=>{requests.push({url,body:options.body&&JSON.parse(options.body)});return {ok:true,json:async()=>options.body&&write?write(url):url.endsWith('-state')&&read?read():structuredClone(current)};});
 apply({effect:fn=>{cleanup=fn();}});
 return {app,modal,notice,requests,submit:form=>listeners.submit({target:{closest:()=>form},preventDefault(){}}),setState:value=>{current=value;},setRead:fn=>{read=fn;},setWrite:fn=>{write=fn;},click:async(action,dataset={})=>{const b={inApp:true,dataset:{action,...dataset}};await listeners.click({target:{closest:()=>b}});},poll:()=>{const entry=[...timers.entries()].find(([,v])=>v.ms===1300||v.ms===5000);assert.ok(entry,'state polling remains scheduled');timers.delete(entry[0]);return entry[1].fn();},pollDelay:()=>[...timers.values()].find(v=>v.ms===1300||v.ms===5000)?.ms};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const runningState={revision:1,people:[{id:'p1',name:'甲',materials:[]}],self:{materials:[]},library:[],job:{id:'run-1',status:'running',targetId:'p1',kind:'profile'}};
test('stop sends the run id captured on the rendered button',async t=>{
 const ui=mounted(t,runningState);await settle();await ui.click('cancel',{runId:'run-1'});
 assert.deepEqual(ui.requests.find(r=>r.url.endsWith('-cancel')).body,{runId:'run-1',revision:1});
});
test('cancelling keeps fast polling and blocks conflicting starts and repeated stops',async t=>{
 const ui=mounted(t,{...runningState,job:{...runningState.job,status:'cancelling'}});await settle();await ui.click('person',{id:'p1'});await ui.click('analyze');await ui.click('strategy');await ui.click('cancel',{runId:'run-1'});
 assert.equal(ui.requests.filter(r=>r.body).length,0);assert.equal(ui.pollDelay(),1300);
});
test('a delayed poll cannot overwrite a newer cancelling response',async t=>{
 const ui=mounted(t,runningState);await settle();let resolveOld;
 ui.setRead(()=>new Promise(resolve=>{resolveOld=resolve;}));const oldPoll=ui.poll();await settle();
 ui.setRead(null);ui.setState({...runningState,job:{...runningState.job,status:'cancelling'}});await ui.click('cancel',{runId:'run-1'});
 assert.match(ui.app.innerHTML,/正在停止/);resolveOld(runningState);await oldPoll;
 assert.match(ui.app.innerHTML,/正在停止/);assert.equal(ui.pollDelay(),1300);
});
test('completion notices do not follow a user to a different person or a different run',async t=>{
 const data={...runningState,people:[...runningState.people,{id:'p2',name:'乙',materials:[]}]};
 const ui=mounted(t,data);await settle();await ui.click('person',{id:'p2'});
 ui.setState({...data,job:{...data.job,status:'done'}});await ui.poll();assert.equal(ui.notice.textContent,'');
 ui.setState({...data,job:{...data.job,id:'run-2',status:'running'}});await ui.poll();await ui.click('person',{id:'p1'});
 ui.setState({...data,job:{...data.job,id:'run-3',status:'done'}});await ui.poll();assert.equal(ui.notice.textContent,'');
});

test('an acknowledged run can be stopped while the start response is still pending',async t=>{
 const ui=mounted(t,{...runningState,job:{status:'idle'}});await settle();await ui.click('person',{id:'p1'});
 let resolveStart;ui.setWrite(url=>url.endsWith('-analyze')?new Promise(resolve=>{resolveStart=resolve;}):{});
 const start=ui.click('analyze');await settle();ui.setState(runningState);await ui.poll();
 await ui.click('cancel',{runId:'run-1'});
 assert.equal(ui.requests.filter(r=>r.url.endsWith('-cancel')).length,1);
 resolveStart({});await start;
});
test('a stale stop button cannot cancel a different run',async t=>{
 const ui=mounted(t,{...runningState,job:{...runningState.job,id:'run-2'}});await settle();await ui.click('cancel',{runId:'run-1'});
 assert.equal(ui.requests.filter(r=>r.url.endsWith('-cancel')).length,0);
});
test('reopening reads saved legacy and v1 profiles without starting a new run',async t=>{
 const ui=mounted(t,{...runningState,people:[{...runningState.people[0],profile:{summary:'已保存的旧画像',facets:[]}}],self:{materials:[],profile:{summary:'已保存的本人画像',knowledgeStatus:'empty_library'}},job:{id:'old-run',targetId:'self',status:'interrupted'}});await settle();
 await ui.click('person',{id:'p1'});assert.match(ui.app.innerHTML,/已保存的旧画像/);assert.doesNotMatch(ui.app.innerHTML,/任务已中断/);
 await ui.click('self');assert.match(ui.app.innerHTML,/已保存的本人画像/);assert.match(ui.app.innerHTML,/任务已中断/);assert.equal(ui.requests.filter(r=>r.body).length,0);
});
test('legacy jobs without run ids keep the existing cancel fallback',async t=>{
 const ui=mounted(t,{...runningState,job:{status:'running'}});await settle();await ui.click('cancel');assert.deepEqual(ui.requests.find(r=>r.url.endsWith('-cancel')).body,{revision:1});
});

test('knowledge deletion explains removal of current and historical profiles before removing anything',async t=>{
 const ui=mounted(t,{...runningState,job:{status:'idle'}});await settle();
 await ui.click('knowledge-delete',{id:'book-1'});
 assert.match(ui.modal.innerHTML,/相关当前及历史画像会被移除.*重新生成/);
 assert.match(ui.modal.innerHTML,/引用它的攻略会一并移除/);
 assert.equal(ui.requests.filter(r=>r.body).length,0);
});
test('successful upload announces availability for profiles and strategies',async t=>{
 const ui=mounted(t,{...runningState,job:{status:'idle'}});await settle();
 const oldFormData=globalThis.FormData,oldFileReader=globalThis.FileReader;
 globalThis.FormData=class {constructor(form){return Object.entries(form.values);}};
 globalThis.FileReader=class {readAsDataURL(){this.result='data:text/plain;base64,ZmFrZQ==';this.onload();}};
 t.after(()=>{globalThis.FormData=oldFormData;if(oldFileReader===undefined)delete globalThis.FileReader;else globalThis.FileReader=oldFileReader;});
 const form={inModal:true,dataset:{form:'knowledge-upload',revision:'1'},values:{file:{size:4,name:'fiction.txt'},title:'虚构方法'}};
 ui.submit(form);await settle();
 assert.equal(ui.requests.filter(r=>r.url.endsWith('-knowledge-upload')).length,1);
 assert.match(ui.notice.textContent,/画像和攻略参考/);
});
for(const status of ['error','interrupted'])test(`closing ${status} is local, survives polling, and cannot hide a new run`,async t=>{
 const initial={...runningState,job:{...runningState.job,status,error:'受控失败'}};
 const ui=mounted(t,initial);await settle();await ui.click('person',{id:'p1'});
 const close=ui.app.innerHTML.match(/<button data-action="([^"]+)"[^>]*data-run-id="([^"]+)"[^>]*>关闭提示/);assert.ok(close);
 await ui.click(close[1],{runId:close[2]});
 assert.doesNotMatch(ui.app.innerHTML,/关闭提示|受控失败|任务已中断/);assert.equal(ui.requests.filter(r=>r.body).length,0);
 await ui.poll();await ui.click('library');await ui.click('person',{id:'p1'});assert.doesNotMatch(ui.app.innerHTML,/关闭提示|受控失败|任务已中断/);
 ui.setState({...initial,job:{...initial.job,id:'run-2',status:'running',phase:'knowledge',message:'新任务检索'}});await ui.poll();
 assert.match(ui.app.innerHTML,/新任务检索/);await ui.click(close[1],{runId:close[2]});assert.match(ui.app.innerHTML,/新任务检索/);assert.equal(ui.requests.filter(r=>r.body).length,0);
 ui.setState({...initial,job:{...initial.job,id:'run-2'}});await ui.poll();assert.match(ui.app.innerHTML,/关闭提示/);
});
