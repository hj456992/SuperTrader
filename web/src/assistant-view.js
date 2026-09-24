const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const time = value => value && !Number.isNaN(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '';
const labels = {needed:'需要你回复',optional:'可以参与',none:'暂时无需回复',unknown:'暂时无法判断'};
function refs(p,ids=[]) {
  return `<div class="as-evidence">${ids.map(id=>{
    const index=p.messages.findIndex(m=>m.id===id), m=p.messages[index];
    if(!m)return '';
    return `<button data-action="evidence" data-mid="${esc(id)}" title="${esc(m.text)}">↗ ${esc(m.sender|| (m.role==='me'?'我':p.name))} · ${index+1}</button>`;
  }).join('')}</div>`;
}
function profileView(p,member,tab) {
  const saved=p.profiles?.[member.id], latest=saved?.latest;
  const messages=p.messages.filter(m=>member.messageIds.includes(m.id));
  const tabs=[['profile','画像'],['records',`发言 ${messages.length}`],['history','分析历史']];
  let body='';
  if(tab==='records') {
    body=messages.length ? `<div class="as-records">${[...messages].reverse().map(m=>`<article><small>${esc(time(m.importedAt))}${m.importedAt?' · 导入':''}</small><p>${esc(m.text)}</p>${refs(p,[m.id])}</article>`).join('')}</div>` : '<p class="as-muted">当前没有可归属到此人的发言。</p>';
  } else if(tab==='history') {
    const history=saved?.history||[];
    body=history.length ? `<p class="as-caption">保留最近 30 次分析，原话有纠正或删除时撤销。</p>${[...history].reverse().map(entry=>`<details class="as-history"><summary>${esc(time(entry.createdAt))} · 分析记录</summary><p>${esc(entry.overview?.text)}</p>${refs(p,entry.overview?.evidenceIds)}${(entry.traits||[]).map(t=>`<p>${esc(t.text)}</p>${refs(p,t.evidenceIds)}`).join('')}<p class="as-muted">${esc(entry.change)}</p><p class="as-muted">${esc(entry.uncertainty)}</p></details>`).join('')}` : '<div class="as-placeholder">还没有分析历史。更新分析后会按时间保存。</div>';
  } else if(latest) {
    body=`<div class="as-profile-meta"><span>基于已保存发言</span><span>${saved.stale?'待更新':esc(time(latest.createdAt))}</span></div>
      ${saved.stale?'<p class="as-stale">上下文或关注设置已变化，以下为上次分析。</p>':''}
      <h4>发言分析</h4><p>${esc(latest.overview.text)}</p>${refs(p,latest.overview.evidenceIds)}
      <h4>逐渐了解他</h4>${latest.traits.length?latest.traits.map(t=>`<div class="as-trait"><span class="as-fact ${t.kind==='explicit'?'explicit':''}">${t.kind==='explicit'?'明确表达':'AI 推测'}</span><p>${esc(t.text)}</p>${refs(p,t.evidenceIds)}</div>`).join(''):'<p class="as-muted">现有发言还不足以形成更多认识。</p>'}
      <div class="as-change"><h4>这次的理解变化</h4><p>${esc(latest.change)}</p></div>
      <details class="as-history"><summary>尚不确定的部分</summary><p>${esc(latest.uncertainty)}</p></details>`;
  } else body=`<div class="as-placeholder"><b>从 ${messages.length} 条发言开始了解${esc(member.name)}</b><p>更新分析后，这里会积累发言解读、特点和依据。你也可以先查看原话。</p></div>`;
  return `<div class="as-profile"><div class="as-person-title"><span class="as-avatar">${esc(member.name[0])}</span><div><b>${esc(member.name)}</b><small>本会话中的关注对象</small></div></div><div class="as-tabs" role="tablist" aria-label="${esc(member.name)}的资料">${tabs.map(([key,label])=>`<button role="tab" aria-selected="${key===tab}" data-action="profile-tab" data-tab="${key}">${label}</button>`).join('')}</div><div class="as-profile-body" role="tabpanel">${body}</div></div>`;
}

export function renderAssistant(p,{job={status:'idle'},activeMember,profileTab='profile'}={}) {
  const c=p.assistantContext || {type:'direct',inferred:true,members:[],focusIds:[],recentCount:Math.min(20,p.messages.length)};
  const a=p.analysis?.version===2?p.analysis:null;
  const running=job.status==='running', ownJob=job.personId===p.id;
  const focused=c.members.filter(m=>c.focusIds.includes(m.id));
  const member=focused.find(m=>m.id===activeMember)||focused[0];
  const decision=a?.response;
  return `<section class="assistant-panel" aria-label="交流助手">
    <header class="assistant-header"><div><b>✧ 交流助手</b><small>看懂当下，持续了解你关注的人</small></div><button class="panel-close" data-action="toggle-assistant" aria-label="收起交流助手">×</button></header>
    <div class="assistant-content">
      <div class="as-context"><span class="as-type">${c.type==='group'?'群聊':'个人聊天'}</span><span>${c.inferred?'按现有记录识别':'已确认类型'}</span><button class="link" data-action="assistant-settings" ${running?'disabled':''}>设置</button></div>
      ${running?`<div class="as-progress" role="status"><span class="spinner"></span><span>${ownJob?'正在整理会话与关注对象…':'另一段会话正在分析…'}</span>${ownJob?'<button class="link" data-action="cancel">取消</button>':''}</div>`:''}
      ${job.status==='error'&&ownJob?'<p class="as-error" role="alert">本次分析未保存，可以重试。已有画像仍保留。</p>':''}
      <section class="as-section"><div class="as-section-title"><h2>当前在聊什么</h2><small>最近 ${c.recentCount} 条</small></div>
        ${a?`<p class="as-summary">${esc(a.summary.text)}</p>${refs(p,a.summary.evidenceIds)}`:`<p class="as-muted">${p.analysis?'之前的分析尚未包含会话摘要。':'先梳理最新话题、参与者和讨论进展。'}点击下方更新分析。</p>`}
      </section>
      <section class="as-section"><div class="as-section-title"><h2>是否需要我回复</h2></div>
        <div class="as-decision ${decision?.status||'unknown'}"><span class="as-status-dot"></span><b>${decision?labels[decision.status]:(c.identityKnown?'等待分析':'先确认我的身份')}</b></div>
        <p class="as-muted">${decision?esc(decision.reason):(c.identityKnown?'结合提问、上下文和后续回应判断，不把每条新消息都当成回复任务。':'确认你对应哪个成员，才能判断谁在等你回应。')}</p>
        ${!c.identityKnown?`<button class="as-inline-button" data-action="assistant-settings" ${running?'disabled':''}>确认我的身份 ↗</button>`:''}
        ${decision?refs(p,decision.evidenceIds):''}
        ${decision?.reply?`<details class="as-reply"><summary>查看建议回复</summary><p>${esc(decision.reply)}</p><button class="secondary" data-action="use-reply">放入回复框</button></details>`:''}
      </section>
      <section class="as-section as-focus"><div class="as-section-title"><h2>重点关注</h2>${c.type==='group'?`<button class="link" data-action="assistant-settings" ${running?'disabled':''}>${focused.length?'管理':'＋ 选择对象'}</button>`:'<small>当前对方</small>'}</div>
        ${focused.length?`<div class="as-members" aria-label="重点关注成员">${focused.map(m=>`<button aria-pressed="${member.id===m.id}" data-action="view-member" data-member="${esc(m.id)}">${esc(m.name)}</button>`).join('')}</div>${profileView(p,member,profileTab)}`:`<div class="as-placeholder"><b>想进一步了解谁？</b><p>${c.members.length?'选择群里的成员，分析他的发言，并逐步积累画像。':'现有记录没有可确认的成员。导入群聊时，请用成员姓名标注发言。'}</p></div>`}
      </section>
      <details class="as-material"><summary>材料与数据</summary><p>摘要参考最近 ${c.recentCount} 条；分析会将当前会话名称、交流目标、关注名单、身份及全部 ${p.messages.length} 条已保存记录发送至 DeepSeek。</p><p>${p.source==='feishu_desktop'?'飞书记录仅覆盖读取时加载的页面，不代表完整历史。':''} ${c.namedSource?'成员按本会话中的发言姓名区分；同名及未显示的发言人需要核实。':''} AI 分析有不确定性，点击依据可检查原文；纠正或删除原文会撤销画像分析。</p><button class="link danger" data-action="delete-person">删除此会话的数据</button></details>
    </div>
    <footer class="as-footer"><span>${a?'更新于 '+esc(time(a.createdAt)):'原文已保存 · 分析待更新'}</span><button class="primary" data-action="analyze" ${running||!p.messages.length?'disabled':''}>${running&&ownJob?'正在分析…':'更新分析'}</button></footer>
  </section>`;
}

export function renderAssistantSettings(p) {
  const c=p.assistantContext, s=p.assistantSettings||{};
  return `<h2>会话与关注设置</h2><p class="muted">确认会话类型和你的身份，再选择想持续了解的人。</p>
    <label>会话类型<select id="assistant-type">${[['auto','自动识别'],['group','群聊'],['direct','个人聊天']].map(([v,t])=>`<option value="${v}" ${(s.conversationType||'auto')===v?'selected':''}>${t}</option>`).join('')}</select></label>
    <label>我的身份<select id="assistant-self"><option value="">${c.type==='direct'&&!c.namedSource?'按“我 / 对方”标注识别':'暂不确定'}</option>${c.roleIdentity?`<option value="me" ${c.selfId==='me'?'selected':''}>原文中标注的“我”</option>`:''}${c.members.filter(m=>m.id!=='counterpart').map(m=>`<option value="${esc(m.id)}" ${c.selfId===m.id?'selected':''}>${esc(m.name)}</option>`).join('')}</select></label>
    <fieldset class="as-focus-options"><legend>重点关注对象 <small>最多 5 人 · 单聊自动关注对方</small></legend>${c.members.length?c.members.map(m=>`<label><input type="checkbox" name="assistant-focus" value="${esc(m.id)}" ${c.focusIds.includes(m.id)?'checked':''} ${c.selfId===m.id?'disabled':''}><span>${esc(m.name)}</span><small>${m.messageIds.length} 条发言</small></label>`).join(''):'<p class="muted">没有可识别的成员。请使用“成员姓名：内容”补充群聊原文。</p>'}</fieldset>
    <p class="as-caption">设置只保存在本地；保存后点击“更新分析”生成结果。取消关注会保留已有画像。</p><button class="primary wide" data-action="save-assistant-settings">保存设置</button>`;
}
