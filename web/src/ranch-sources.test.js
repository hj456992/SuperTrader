import test from 'node:test';
import assert from 'node:assert/strict';
import {sourceSelection,createSourceRequestGate,conversationOptions} from './ranch-sources.js';
const sources=[
 {id:'w',platform:'wechat',title:'同名会话',members:[{id:'wx-person',name:'小林'}]},
 {id:'f',platform:'feishu',title:'同名会话',members:[{id:'fs-person',name:'小林'}]},
 {id:'u',platform:'other',title:'旧资料',members:[]}
];
test('platform selection excludes other platforms even when conversation names match',()=>{
 assert.deepEqual(sourceSelection(sources,{platform:'wechat'}).conversations.map(s=>s.id),['w']);
 assert.deepEqual(sourceSelection(sources,{platform:'feishu'}).conversations.map(s=>s.id),['f']);
 assert.deepEqual(sourceSelection(sources,{}).conversations,[]);
});
test('switching platform or conversation revokes the previous member selection',()=>{
 const same=sourceSelection(sources,{platform:'wechat',conversationId:'w',memberId:'wx-person'});
 assert.equal(same.member.id,'wx-person');
 const platformSwitch=sourceSelection(sources,{platform:'feishu',conversationId:'w',memberId:'wx-person'});
 assert.equal(platformSwitch.conversation,null);assert.equal(platformSwitch.member,null);
 const conversationSwitch=sourceSelection(sources,{platform:'feishu',conversationId:'f',memberId:'wx-person'});
 assert.equal(conversationSwitch.conversation.id,'f');assert.equal(conversationSwitch.member,null);
});
test('missing platforms remain accessible as other records without pretending to be WeChat',()=>{
 const legacy=[{id:'old',members:[]}];
 assert.equal(sourceSelection(legacy,{platform:'wechat'}).conversations.length,0);
 assert.equal(sourceSelection(legacy,{platform:'other'}).conversations[0].id,'old');
});
test('title search filters only the selected platform and never loses the unfiltered count',()=>{
 const result=sourceSelection([...sources,{id:'w2',platform:'wechat',title:'Coffee Club',members:[]}],{platform:'wechat',query:' COFFEE '});
 assert.deepEqual(result.conversations.map(s=>s.id),['w2']);assert.equal(result.total,2);
});
test('live list descriptors cannot supply an import identity until its selected transcript is loaded',()=>{
 const live={id:'live',platform:'wechat',live:true,members:[{id:'m',name:'成员'}]};
 assert.equal(sourceSelection([live],{platform:'wechat',conversationId:'live',memberId:'m'}).member,null);
 assert.equal(sourceSelection([{...live,membersLoaded:true,snapshotId:'snapshot-a'}],{platform:'wechat',conversationId:'live',memberId:'m'}).member.id,'m');
});

test('late platform and transcript responses cannot replace a newer choice even after choosing back',()=>{
 const gate=createSourceRequestGate();const wx=gate.platform('wechat');const f=gate.platform('feishu');
 assert.equal(gate.accepts(wx),false);assert.equal(gate.accepts(f),true);
 const a=gate.conversation('a');const b=gate.conversation('b');gate.conversation('a');
 assert.equal(gate.accepts(a),false);assert.equal(gate.accepts(b),false);
 const aNew=gate.conversation('a');assert.equal(gate.accepts(aNew),true);
 gate.platform('wechat');assert.equal(gate.accepts(aNew),false);
});
test('saved fallback entries are labelled distinctly and conversation values are escaped',()=>{
 const html=conversationOptions([{id:'" onclick="bad',title:'<private>',live:false},{id:'live',title:'实时会话',live:true}]);
 assert.match(html,/已保存记录/);assert.ok(!html.includes('<private>'));assert.ok(!html.includes('value="" onclick='));
});

test('live identities require the exact loaded transcript snapshot token',()=>{
 const record={id:'live',platform:'wechat',live:true,membersLoaded:true,members:[{id:'m'}]};
 assert.equal(sourceSelection([record],{platform:'wechat',conversationId:'live',memberId:'m'}).member,null);
 const picked=sourceSelection([{...record,snapshotId:'immutable-snapshot'}],{platform:'wechat',conversationId:'live',memberId:'m'});
 assert.equal(picked.member.id,'m');assert.equal(picked.conversation.snapshotId,'immutable-snapshot');
});
