import test from 'node:test';
import assert from 'node:assert/strict';
import {renderAssistant, renderAssistantSettings} from './assistant-view.js';

const person = () => ({id:'group',name:'周末群',messages:[{id:'a',sender:'小王',text:'周六有空吗'}],
  assistantContext:{type:'group',inferred:true,identityKnown:false,namedSource:true,recentCount:1,selfId:'',
    members:[{id:'sender:小王',name:'小王',messageIds:['a']}],focusIds:['sender:小王']},profiles:{}});
test('old results cannot masquerade as a new group summary',()=>{
  const p=person();p.analysis={need:{text:'旧的错误画像'}};
  const html=renderAssistant(p);
  assert.ok(html.includes('当前在聊什么'));
  assert.ok(html.includes('是否需要我回复'));
  assert.ok(html.includes('更新分析'));
  assert.ok(!html.includes('旧的错误画像'));
});
test('records tab shows only selected members messages and escapes imported HTML',()=>{
  const p=person();p.messages.push({id:'b',sender:'小李',text:'不属于小王'});
  p.messages[0].text='<img src=x onerror=alert(1)>';
  const html=renderAssistant(p,{activeMember:'sender:小王',profileTab:'records'});
  assert.ok(html.includes('&lt;img'));
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('不属于小王'));
});
test('stale profile is visibly distinguished from a current result',()=>{
  const p=person();p.profiles['sender:小王']={stale:true,latest:{overview:{text:'关心时间',evidenceIds:['a']},traits:[],change:'首次',uncertainty:'未知'},history:[]};
  assert.ok(renderAssistant(p).includes('待更新'));
});
test('settings carries member identity and checked focus state',()=>{
  const html=renderAssistantSettings(person());
  assert.ok(html.includes('value="sender:小王"'));
  assert.ok(html.includes('checked'));
  assert.ok(html.includes('我的身份'));
});
