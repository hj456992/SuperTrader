import {esc} from './ranch-view.js';
export const platformNames={wechat:'微信',feishu:'飞书',other:'其他已存记录'};
export const sourcePlatform=source=>['wechat','feishu'].includes(source.platform)?source.platform:'other';
export const sourceReady=source=>!!source&&(source.live!==true||source.membersLoaded===true&&typeof source.snapshotId==='string'&&source.snapshotId.trim().length>0);
export function sourceSelection(sources,{platform='',conversationId='',memberId='',query=''}={}){
 const all=platform?sources.filter(s=>sourcePlatform(s)===platform):[];
 const needle=String(query).trim().toLocaleLowerCase();
 const conversations=all.filter(s=>String(s.title||'').toLocaleLowerCase().includes(needle));
 const conversation=conversations.find(s=>s.id===conversationId)||null;
 const ready=sourceReady(conversation);
 const member=ready?(conversation.members||[]).find(m=>m.id===memberId)||null:null;
 return {conversations,total:all.length,conversation,member};
}
export function sourceFields(sources){
 const platforms=['wechat','feishu',...(sources.some(s=>sourcePlatform(s)==='other')?['other']:[])];
 return `<label class="field">1. 选择平台<select name="platform" id="source-platform" required><option value="">请选择微信或飞书</option>${platforms.map(p=>`<option value="${p}">${platformNames[p]}</option>`).join('')}</select></label><div class="between"><p id="source-count" class="hint" role="status" aria-live="polite">选择平台后读取本机可用会话目录</p><button type="button" data-action="source-refresh" class="text-button" disabled>刷新会话列表</button></div><label class="field">搜索会话<input id="source-search" type="search" placeholder="输入会话名称筛选" disabled autocomplete="off"/></label><label class="field">2. 选择会话<select name="conversationId" id="source-conversation" required disabled><option value="">请先选择平台</option></select></label><label class="field">3. 选择成员<select name="memberId" id="source-member" required disabled><option value="">请先选择会话</option></select></label><p id="source-empty" class="hint" role="status" hidden></p><p id="source-detail" class="hint"></p><p id="source-scope" class="hint">先读取会话目录；仅在你选择某个会话后，读取其当前可用消息与成员。</p>`;
}
export function conversationOptions(conversations){return '<option value="">请选择会话</option>'+conversations.map(s=>`<option value="${esc(s.id)}">${esc(s.title)}${s.live===true?'':' · 已保存记录'}</option>`).join('');}
export function createSourceRequestGate(){let platformEpoch=0,memberEpoch=0,platform='',conversationId='';return {
 platform(value){platform=value;conversationId='';platformEpoch++;memberEpoch++;return {kind:'platform',platformEpoch,platform};},
 conversation(value){conversationId=value;memberEpoch++;return {kind:'members',platformEpoch,memberEpoch,platform,conversationId};},
 accepts(token){return token.platformEpoch===platformEpoch&&token.platform===platform&&(token.kind==='platform'||token.memberEpoch===memberEpoch&&token.conversationId===conversationId);}
};}
