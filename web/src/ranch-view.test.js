import test from 'node:test';
import assert from 'node:assert/strict';
import { renderRanch, renderProfile, renderStrategy, sourceOptions } from './ranch-view.js';
const person = {id:'p1',name:'<img src=x onerror=alert(1)>',stage:'egg',creature:'rabbit',color:'sage',materials:[],strategies:[]};
const state = {revision:0,people:[person],self:{name:'我',materials:[]},library:[],job:{status:'idle'}};
test('ranch renders untrusted names as text and egg stays unhatched without analysis',()=>{
 const html=renderRanch(state,{page:'ranch'}); assert.ok(html.includes('&lt;img')); assert.ok(!html.includes('<img')); assert.match(html,/等待孵化/);
});
test('profile references distinguish user reports and original materials with escaped source',()=>{
 const html=renderProfile({summary:'<script>x</script>',stale:true,coverage:{used:1,total:3},facets:[{category:'兴趣',text:'咖啡',kind:'inferred',evidenceIds:['m1','person-notes']}],uncertainties:['仍未知']},{...person,notes:'转述',materials:[{id:'m1',text:'<b>原文</b>',speaker:'them'}]});
 assert.ok(!html.includes('<script>')); assert.match(html,/1\s*\/\s*3/); assert.match(html,/推测/); assert.match(html,/待更新/); assert.match(html,/&lt;b&gt;原文/); assert.match(html,/我的备注/);
});
test('strategy keeps behavioral evidence and book references separate and never injects HTML',()=>{
 const html=renderStrategy({overview:'攻略',steps:[],reply:'<iframe>',evidenceIds:['m1'],knowledge:[{title:'<book>',location:'第 2 节',text:'<svg>'}]},{...person,materials:[{id:'m1',text:'真实发言',speaker:'them'}]});
 assert.match(html,/行为依据/); assert.match(html,/知识参考/); assert.match(html,/真实发言/); assert.ok(!html.includes('<iframe>')); assert.ok(!html.includes('<svg>'));
});
test('running analysis disables generation while existing situation draft remains rendered',()=>{
 const html=renderRanch({...state,job:{status:'running'},people:[{...person,profile:{summary:'已有画像'},stage:'hatched'}]},{page:'person',selected:'p1',tab:'strategy',draft:'我的草稿'});
 assert.match(html,/我的草稿/); assert.match(html,/data-action="strategy"[^>]*disabled/);
});
test('source option ids and titles cannot inject HTML attributes',()=>{
 const html=sourceOptions([{id:'" onfocus="x',title:'<script>',scope:'群聊'}]); assert.ok(!html.includes('<script>')); assert.ok(!html.includes('value="" onfocus='));
});
test('self profile and person strategies resolve own statements imported under another person',()=>{
 const full={...state,people:[{...person,id:'p1',stage:'hatched',profile:{summary:'画像'},strategies:[{overview:'建议',evidenceIds:['my-cross-person'],steps:[]}]},{id:'other',materials:[{id:'my-cross-person',speaker:'me',text:'跨人物的我的真实发言'}]}],self:{name:'我',materials:[],profile:{summary:'我',facets:[{text:'判断',evidenceIds:['my-cross-person']}]}}};
 assert.match(renderRanch(full,{page:'self',tab:'profile'}),/跨人物的我的真实发言/);
 assert.match(renderRanch(full,{page:'person',selected:'p1',tab:'strategy'}),/跨人物的我的真实发言/);
});
test('historical profiles remain inspectable with their own evidence and uncertainty',()=>{
 const full={...state,people:[{...person,profile:{summary:'当前'},analyses:[{summary:'上次保存的画像',facets:[{text:'历史推断',kind:'inferred',evidenceIds:['old']}],uncertainties:['历史未知']}],materials:[{id:'old',text:'历史依据',speaker:'them'}]}]};
 const html=renderRanch(full,{page:'person',selected:'p1',tab:'profile'});assert.match(html,/上次保存的画像/);assert.match(html,/历史依据/);assert.match(html,/历史未知/);
});
test('a failed job exposes a dismiss action even after its target was deleted',()=>{
 const html=renderRanch({...state,people:[],job:{status:'error',targetId:'deleted',error:'<script>旧错误</script>'}},{page:'ranch'});
 assert.match(html,/data-action="dismiss-job"[^>]*>关闭提示/); assert.ok(!html.includes('<script>')); assert.match(html,/旧错误/);
});
test('v1 profile separates book methods, fact sources, counterevidence and limits',()=>{
 const html=renderProfile({summary:'初步',knowledgeStatus:'used',knowledge:[{id:'K-1',title:'<书>',location:'第 1 节',text:'<方法>'}],facets:[{category:'交流',text:'有时偏好独处',kind:'inferred',evidenceIds:['m1'],knowledgeIds:['K-1'],counterEvidenceIds:['m2'],scope:'<仅当前材料>',confidenceReason:'<证据有限>'}],uncertainties:['<仍未知>']},{...person,materials:[{id:'m1',speaker:'them',text:'独处'},{id:'m2',speaker:'them',text:'也想聚会'}]});
 assert.match(html,/聊天与自述依据/); assert.match(html,/书籍方法参考/); assert.match(html,/反证与不同情况/);assert.match(html,/也想聚会/);assert.match(html,/适用范围.*&lt;仅当前材料&gt;/);assert.match(html,/判断依据.*&lt;证据有限&gt;/);assert.match(html,/&lt;书&gt;/);assert.match(html,/&lt;方法&gt;/);assert.match(html,/&lt;仍未知&gt;/);assert.doesNotMatch(html,/<方法>|<书>/);
});
test('missing books and unmatched methods explicitly limit the profile',()=>{
 for(const [knowledgeStatus,copy] of [['empty_library',/没有可用书籍/],['no_match',/未找到相关书籍方法/]])assert.match(renderProfile({summary:'有限认识',knowledgeStatus},person),copy);
});
test('profile cannot resolve books as facts or another persons material as target evidence',()=>{
 const html=renderProfile({summary:'初步',knowledge:[{id:'K-1',title:'方法书',text:'书摘'}],facets:[{text:'判断',evidenceIds:['K-1','other','me'],knowledgeIds:['missing']}]},{...person,materials:[{id:'K-1',speaker:'them',text:'错误混入的书籍事实'},{id:'me',speaker:'me',text:'自己的发言不代表对方'}]},{materials:[{id:'other',speaker:'me',text:'他处发言'}]});
 assert.doesNotMatch(html,/错误混入的书籍事实|他处发言|自己的发言不代表对方/);assert.match(html,/来源已不可用/);assert.match(html,/书籍方法来源已不可用/);
});
test('real phases and bounded escaped events belong only to their target page',()=>{
 const job={id:'run-1',status:'running',kind:'profile',targetId:'p1',phase:'knowledge',step:2,maxSteps:6,message:'<正在检索>',events:[{seq:1,type:'phase',phase:'preparing',message:'<材料准备完毕>'}]};
 const html=renderRanch({...state,job},{page:'person',selected:'p1',tab:'profile'});
 assert.match(html,/检索书籍方法/);assert.match(html,/2\s*\/\s*6/);assert.match(html,/&lt;正在检索&gt;/);assert.match(html,/&lt;材料准备完毕&gt;/);assert.match(html,/data-run-id="run-1"/);
 const switched=renderRanch({...state,job,people:[person,{...person,id:'p2',name:'另一人'}]},{page:'person',selected:'p2',tab:'profile'});
 assert.doesNotMatch(switched,/正在检索|材料准备完毕|data-action="cancel"/);assert.match(switched,/data-action="analyze"[^>]*disabled/);
});
test('cancelling blocks both generation paths and interrupted preserves saved profile',()=>{
 const data={...state,people:[{...person,profile:{summary:'已保存画像'}}],job:{id:'run-2',targetId:'p1',status:'cancelling'}};
 for(const [tab,action] of [['profile','analyze'],['strategy','strategy']]){
  const html=renderRanch(data,{page:'person',selected:'p1',tab});assert.match(html,/正在停止/);assert.match(html,new RegExp('data-action="'+action+'"[^>]*disabled'));assert.doesNotMatch(html,/data-action="cancel"/);
 }
 const html=renderRanch({...data,job:{...data.job,status:'interrupted'}},{page:'person',selected:'p1',tab:'profile'});
 assert.match(html,/任务已中断/);assert.match(html,/已保存画像/);assert.doesNotMatch(html,/data-action="analyze"[^>]*disabled/);
});
test('switching people never carries over profile book excerpts',()=>{
 const data={...state,people:[{...person,profile:{summary:'甲',knowledgeStatus:'used',knowledge:[{id:'K-a',title:'甲的方法',text:'甲的书摘'}]}},{...person,id:'p2',profile:{summary:'乙'}}]};
 assert.match(renderRanch(data,{page:'person',selected:'p1',tab:'profile'}),/甲的书摘/);
 assert.doesNotMatch(renderRanch(data,{page:'person',selected:'p2',tab:'profile'}),/甲的书摘/);
});
test('progress retains only the recent phase events and escapes the run attribute',()=>{
 const events=Array.from({length:12},(_,i)=>({seq:i,type:'phase',phase:'evidence',message:`步骤-${i}`}));events.push({type:'internal',message:'不应展示的内部事件'});
 const html=renderRanch({...state,job:{id:'" autofocus="bad',status:'running',targetId:'p1',events}},{page:'person',selected:'p1',tab:'profile'});
 assert.doesNotMatch(html,/步骤-0<|步骤-3<|不应展示的内部事件|data-run-id="" autofocus=/);assert.match(html,/步骤-4</);assert.match(html,/步骤-11</);assert.match(html,/data-run-id="&quot; autofocus=&quot;bad"/);
});
test('library explains that enabled references serve both profiles and strategies',()=>{
 const html=renderRanch(state,{page:'library'});
 assert.match(html,/生成画像或攻略时/);
});
test('strategy describes target profile and both parties materials without requiring two profiles',()=>{
 const html=renderRanch(state,{page:'person',selected:'p1',tab:'strategy'});
 assert.match(html,/结合目标画像、双方材料、关系目标与启用资料/);
 assert.doesNotMatch(html,/结合双方画像/);
});
test('cancelled banner only promises a saved profile when its own target has one',()=>{
 const job={id:'run-1',status:'cancelled',targetId:'p1',kind:'profile'};
 const noProfile=renderRanch({...state,job},{page:'person',selected:'p1',tab:'profile'});
 assert.match(noProfile,/任务已停止/);assert.doesNotMatch(noProfile,/已保存的画像仍可查看/);
 const withProfile=renderRanch({...state,job,people:[{...person,profile:{summary:'已有画像'}}]},{page:'person',selected:'p1',tab:'profile'});
 assert.match(withProfile,/已保存的画像仍可查看/);
 const selfMissing=renderRanch({...state,job:{...job,targetId:'self'},people:[{...person,profile:{summary:'他人已有画像'}}]},{page:'self',tab:'profile'});
 assert.doesNotMatch(selfMissing,/已保存的画像仍可查看/);
});
test('strategy reasoning and phase event fallbacks describe strategies at the reported model step',()=>{
 for(const step of [1,2]){
  const job={id:'strategy-run',status:'running',targetId:'p1',kind:'strategy',phase:'reasoning',step,maxSteps:2,events:[{type:'phase',phase:'reasoning'}]};
  const html=renderRanch({...state,job},{page:'person',selected:'p1',tab:'strategy'});
  assert.match(html,new RegExp(`整理相处攻略 · 模型步骤 ${step} / 2`));
  assert.match(html,/<details class="job-events">[\s\S]*<p>整理相处攻略<\/p>/);
  assert.doesNotMatch(html,/整理画像与推断/);
 }
 const html=renderRanch({...state,job:{status:'running',targetId:'p1',kind:'profile',phase:'reasoning'}},{page:'person',selected:'p1',tab:'profile'});
 assert.match(html,/整理画像与推断/);
});
test('strategy validation and saving retain real phases steps and escaped server messages',()=>{
 for(const [phase,label] of [['validating','核对结构与引用'],['saving','保存结果']]){
  const job={id:'strategy-run',status:'running',targetId:'p1',kind:'strategy',phase,step:2,maxSteps:2,message:'<服务端进度>',events:[{type:'phase',phase:'reasoning',message:'<实际推断进度>'},{type:'phase',phase}]};
  const html=renderRanch({...state,job},{page:'person',selected:'p1',tab:'strategy'});
  assert.match(html,new RegExp(`${label} · 模型步骤 2 / 2`));
  assert.match(html,new RegExp(`<p>${label}</p>`));
  assert.match(html,/&lt;服务端进度&gt;/);assert.match(html,/&lt;实际推断进度&gt;/);
  assert.doesNotMatch(html,/<服务端进度>|<实际推断进度>/);
 }
});
test('cancelled strategy only promises a saved strategy belonging to its target',()=>{
 const job={id:'strategy-run',status:'cancelled',targetId:'p1',kind:'strategy'};
 for(const strategies of [[],[{overview:'已有攻略'}]]){
  const html=renderRanch({...state,job,people:[{...person,profile:{summary:'已有画像'},strategies}]},{page:'person',selected:'p1',tab:'strategy'});
  assert.match(html,/任务已停止/);assert.doesNotMatch(html,/已保存的画像仍可查看/);
  if(strategies.length)assert.match(html,/已保存的相处攻略仍可查看/);
  else assert.doesNotMatch(html,/已保存的相处攻略仍可查看/);
 }
 const html=renderRanch({...state,job,people:[person,{...person,id:'p2',strategies:[{overview:'他人攻略'}]}]},{page:'person',selected:'p1',tab:'strategy'});
 assert.doesNotMatch(html,/已保存的相处攻略仍可查看/);
 const profileJob=renderRanch({...state,job:{...job,kind:'profile'},people:[{...person,strategies:[{overview:'已有攻略'}]}]},{page:'person',selected:'p1',tab:'profile'});
 assert.doesNotMatch(profileJob,/已保存的画像仍可查看|已保存的相处攻略仍可查看/);
});
test('interrupted and cancelled tasks do not assert that persistence definitely failed',()=>{
 for(const kind of ['profile','strategy'])for(const status of ['interrupted','cancelled']){
  const html=renderRanch({...state,job:{id:'ended-run',status,targetId:'p1',kind}},{page:'person',selected:'p1',tab:kind==='strategy'?'strategy':'profile'});
  assert.match(html,status==='interrupted'?/任务已中断/:/任务已停止/);
  assert.doesNotMatch(html,/本次结果未保存|不会作为新画像保存|已保存的/);
 }
});
