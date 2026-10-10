const assert=require('node:assert/strict');
const test=require('node:test');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const {Node,document}=require('./dom-harness.cjs');
const web=path.resolve(__dirname,'../../main/resources/web');
async function app(){
 const dom=document(),get=dom.getElementById.bind(dom),query=dom.body.querySelectorAll.bind(dom.body),nodes=new Map(),timers=new Map(),windowEvents=new Map(),posts=[];let clock=0,production;
 dom.getElementById=id=>{let found=get(id);if(found)return found;if(!nodes.has(id)){const n=new Node('div');n.id=id;if(id==='legacy-expert')n.hidden=true;dom.body.append(n);nodes.set(id,n);}return nodes.get(id);};
 dom.querySelectorAll=selector=>selector==='.selection'?[]:selector==='.view'||selector==='.nav'?query(selector):[];
 dom.addEventListener=()=>{};
 for(const name of ['library','experts','production','chat']){const n=dom.getElementById('view-'+name);n.className='view';const nav=new Node('button');nav.className='nav';nav.dataset.view=name;dom.body.append(nav);}
 const version={id:'v1',number:1,pageCount:12,charCount:2500,chunkCount:3,filename:'book.pdf',createdAt:'2026-10-10T00:00:00Z'};
 const state={csrf:'token',documents:[{id:'d1',title:'书一',versions:[version]}],experts:[{id:'expert-1',name:'旧专家',versions:[{id:'ev1',name:'旧专家',duty:'已有职责',summary:'旧稿',number:1,subagents:[],processedChunks:0,methodCount:0,model:'test',selections:[],tested:false}]}],imports:[]};
 const location={hash:'#library',origin:'http://localhost'};
 const context={document:dom,window:{addEventListener:(name,fn)=>windowEvents.set(name,fn)},location,history:{replaceState:(_a,_b,hash)=>{location.hash=hash;}},URL,URLSearchParams,crypto:{randomUUID:()=>`req-${++clock}`},setTimeout:(fn,ms)=>{const id=++clock;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},localStorage:{getItem:()=>JSON.stringify([{documentId:'d1',documentVersionId:'v1'}]),setItem(){}},fetch:async(url)=>{if(url==='/api/state')return {ok:true,json:async()=>state};if(url.endsWith('/proposals')){posts.push(url);return {ok:true,json:async()=>({buildId:'build-1'})};}if(url.includes('/snapshot'))return {ok:true,json:async()=>({buildId:'build-1',status:'active',phase:'prelearning',jobs:[{kind:'prelearn',status:'running'}],artifacts:[],summary:{generationStatus:'queued'},proposal:{sources:[]}})};throw new Error('unexpected '+url);},ProductionUI:{create:options=>{production=options;return {enter(){},leave(){}};}}};
 vm.createContext(context);for(const file of ['production-api.js','workbench.js','app.js'])vm.runInContext(fs.readFileSync(path.join(web,file),'utf8'),context);
 await new Promise(setImmediate);
 const navigate=hash=>{location.hash=hash;windowEvents.get('hashchange')();};
 const tick=async ms=>{for(const [id,t] of [...timers])if(t.ms===ms){timers.delete(id);await t.fn();}await Promise.resolve();};
 return {dom,posts,navigate,tick,getProduction:()=>production};
}
test('real app leaves proposal workbench during old expert editing and keeps modes exclusive across navigation',async()=>{const a=await app();a.navigate('#experts');assert.equal(a.dom.getElementById('legacy-expert').hidden,true);await a.dom.getElementById('legacy-toggle').fire('click');assert.equal(a.dom.getElementById('workbench-root').hidden,true);assert.equal(a.dom.getElementById('legacy-expert').hidden,false);a.dom.getElementById('edit-expert').value='expert-1';await a.dom.getElementById('edit-expert').fire('change');await a.tick(1200);assert.equal(a.posts.length,0);a.navigate('#chat');a.navigate('#experts');await a.tick(1200);assert.equal(a.posts.length,0);assert.equal(a.dom.getElementById('workbench-root').hidden,true);});
test('production new-build action returns to selection mode and only then restores the proposal',async()=>{const a=await app();a.navigate('#experts');await a.dom.getElementById('legacy-toggle').fire('click');a.navigate('#production');a.getProduction().onNew();assert.equal(a.dom.getElementById('legacy-expert').hidden,true);assert.equal(a.dom.getElementById('workbench-root').hidden,false);await a.tick(1200);assert.equal(a.posts.length,1);});
