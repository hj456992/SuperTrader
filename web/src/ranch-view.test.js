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
 assert.match(html,/data-action="cancel"[^>]*>关闭提示/); assert.ok(!html.includes('<script>')); assert.match(html,/旧错误/);
});
