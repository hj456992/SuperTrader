import { renderAssistant, renderAssistantSettings } from './assistant-view.js';
import { acknowledgeSentDraft } from './draft-state.js';

export const name = 'garden-ui';

/** 由原版 Cordis 挂载并释放 UI 插件的全部副作用。 */
export function apply(ctx) {
  ctx.effect(() => mount(), 'garden-ui mount');
}

function mount() {
  const app = document.querySelector('#app');
  const modal = document.querySelector('#modal');
  const abort = new AbortController();
  let data = {people:[],job:{status:'idle'}};
  let selected = null;
  let busy = false;
  let timer;
  let noticeTimer;
  let draft = '';
  let draftId = '';
  let assistantOpen = false;
  let renderedPerson = null;
  const chatPositions = new Map();
  const assistantPositions = new Map();
  const profileViews = new Map();
  let pendingAppend = null;
  let newCapture = null;
  const followDrafts = new Map();
  const replyDrafts = new Map();
  const wechatHref = location.port === '48740' ? 'http://127.0.0.1:48741/wechat.html' : '/wechat.html';
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const person = () => data.people.find(p => p.id === selected);
  const sprout = `<svg viewBox="0 0 32 32" aria-hidden="true"><path d="M25 6H7a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h4l5 4v-4h9a3 3 0 0 0 3-3V9a3 3 0 0 0-3-3Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M10 13h12M10 18h8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>`;

  async function api(action, body) {
    const response = await fetch('/api/'+action, body ? {method:'POST',headers:{'Content-Type':'application/json','X-Garden-Request':'1'},body:JSON.stringify(body),signal:abort.signal} : {signal:abort.signal});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '请求未完成');
    return result;
  }
  function notice(text) {
    clearTimeout(noticeTimer);
    const el = document.querySelector('#notice');
    el.textContent = text;
    if (modal.open) modal.querySelector('.dialog-notice').textContent = text;
    el.classList.add('show');
    noticeTimer = setTimeout(() => el.classList.remove('show'), 6500);
  }
  async function refresh() {
    data = await api('state');
    if (!person()) selected = data.people[0]?.id || null;
    render();
    if (data.job.status === 'running') poll();
  }
  function poll() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      try {
        const next = await api('state');
        data = next;
        if (next.job.status === 'running') poll();
        else {
          render();
          if (next.job.status === 'error') notice(next.job.error);
          if (next.job.status === 'done') notice('会话分析与关注对象画像已保存。');
        }
      } catch (error) {
        if (error.name !== 'AbortError') {
          notice('连接已断开。刷新页面可恢复已保存的内容。');
          render();
        }
      }
    }, 1500);
  }
  function render() {
    const oldChat = document.querySelector('#chat-log');
    if (oldChat && renderedPerson) chatPositions.set(renderedPerson, {top:oldChat.scrollTop,bottom:oldChat.scrollHeight-oldChat.scrollTop-oldChat.clientHeight<48});
    const oldAssistant = document.querySelector('.assistant-content');
    if (oldAssistant && renderedPerson) assistantPositions.set(renderedPerson,oldAssistant.scrollTop);
    const p = person();
    const a = p?.analysis;
    const running = data.job.status === 'running';
    const nextDraftId = p?.id || '';
    if (nextDraftId !== draftId) {
      draftId = nextDraftId;
      draft = replyDrafts.get(draftId) ?? (a?.version === 2 ? '' : a?.reply) ?? '';
    }
    app.innerHTML = `<div class="messenger ${assistantOpen ? 'assistant-open' : ''}">
      <aside class="conversations"><div class="brand"><span class="brand-icon">${sprout}</span><div>爱聊 <span class="brand-english">ai chat</span><small>在每一次交流里，更懂一点</small></div></div>
      <div class="list-heading"><span>聊天</span><button data-action="new" aria-label="添加一个人">＋</button></div>
      <nav aria-label="聊天列表"><button class="person" data-action="wechat"><span class="avatar">微</span><span class="person-preview"><b>聊天日志</b><small>卧底不追高 · 原文日志</small></span></button>${data.people.map(q => `<button class="person ${selected === q.id ? 'selected' : ''}" data-action="select" data-id="${esc(q.id)}"><span class="avatar">${esc(q.name[0])}</span><span class="person-preview"><b>${esc(q.name)}${q.source === 'sample' ? '<i>示例</i>' : ''}</b><small>${esc(q.messages.filter(m=>m.role!=='note').at(-1)?.text || q.group)}</small></span></button>`).join('')}</nav>
      <button class="add-person" data-action="new">＋ 新建聊天 / 导入记录</button><div class="side-foot"><span class="live-dot"></span> 本地已保存<small>dsh-java · 初版 Demo</small><button class="link" data-action="about">运行与数据说明</button></div></aside>
      ${p ? `<section class="chat-pane" aria-label="与${esc(p.name)}的聊天"><header class="chat-header"><div><h1>${esc(p.name)}</h1><p>${esc(p.group)} <span>· ${p.messages.length} 条记录${p.source === 'sample' ? ' · 虚构示例' : p.source === 'feishu_desktop' ? ' · 飞书客户端读取' : ' · 手动导入'}</span></p></div><div class="row"><button class="subtle" data-action="import-chat" ${running ? 'disabled' : ''}>补充记录</button><button class="assistant-toggle ${assistantOpen ? 'active' : ''}" data-action="toggle-assistant">✧ 交流助手</button></div></header>
      <div id="chat-log" class="chat-log" role="log" aria-label="全部聊天记录"><div class="timeline-label">当前已保存的全部 ${p.messages.length} 条记录 · 按导入顺序</div>${p.messages.map(messageView).join('') || '<div class="timeline-label">还没有聊天记录，先导入你们的对话。</div>'}<div class="timeline-end">以上是当前全部记录</div></div>
      <section class="composer" aria-label="回复输入区"><div class="composer-toolbar"><span>写给对方的回复 · 可编辑</span><button class="link" data-action="analyze" ${running || !p.messages.length ? 'disabled' : ''}>${running ? '正在分析…' : '✧ 分析会话'}</button></div><textarea id="draft" aria-label="建议回复" rows="3" placeholder="在这里准备回复…">${esc(draft)}</textarea><div class="composer-bottom"><small>复制后自行发送，不会代发消息</small><div class="row"><button class="secondary" data-action="copy" ${draft.trim() ? '' : 'disabled'}>复制</button><button class="primary" data-action="adopt" ${a && draft.trim() ? '' : 'disabled'}>我已自行发出</button></div></div></section></section>
      ${renderAssistant(p,{job:data.job,...(profileViews.get(p.id)||{})})}` : `<section class="empty-pane">${emptyView()}</section>`}</div>`;
    renderedPerson = p?.id || null;
    const assistantContent = document.querySelector('.assistant-content');
    if(assistantContent && p) assistantContent.scrollTop=assistantPositions.get(p.id)||0;
    const log = document.querySelector('#chat-log');
    if(log) {
      const saved = chatPositions.get(p.id);
      log.scrollTop = !saved || saved.bottom ? log.scrollHeight : saved.top;
    }
  }
  function messageView(m, index) {
    const p=person();
    if(m.sender) return `<article class="chat-message incoming" id="msg-${esc(m.id)}" data-message-id="${esc(m.id)}"><span class="chat-avatar">${esc(m.sender?.[0] || '飞')}</span><div class="message-content"><div class="message-meta">${esc(m.sender)} <span>${m.corrected?'已纠正':m.source==='feishu_desktop'?'飞书原文':'手动导入'}</span></div><div class="bubble">${esc(m.text.replace('飞书原文｜'+m.sender+'：',''))}</div><div class="message-tools"><span>记录 ${index+1}</span><button class="link" data-action="correct" data-mid="${esc(m.id)}">纠正</button><button class="link" data-action="remove-message" data-mid="${esc(m.id)}">删除</button></div></div></article>`;
    if(m.role==='note') return `<article class="chat-note" id="msg-${esc(m.id)}" data-message-id="${esc(m.id)}"><div><span>我的备注</span><p>${esc(m.text)}</p><div class="message-tools"><button class="link" data-action="correct" data-mid="${esc(m.id)}">纠正</button><button class="link" data-action="remove-message" data-mid="${esc(m.id)}">删除</button></div></div></article>`;
    return `<article class="chat-message ${m.role==='me'?'outgoing':'incoming'}" id="msg-${esc(m.id)}" data-message-id="${esc(m.id)}"><span class="chat-avatar ${m.role==='me'?'self-avatar':''}">${m.role==='me'?'我':esc(p.name[0])}</span><div class="message-content"><div class="message-meta">${m.role==='me'?'我':esc(p.name)} <span>${m.selfReportedSent?'手动确认已发':m.corrected?'已纠正':''}</span></div><div class="bubble">${esc(m.text)}</div><div class="message-tools"><span>记录 ${index+1}</span><button class="link" data-action="correct" data-mid="${esc(m.id)}">纠正</button><button class="link" data-action="remove-message" data-mid="${esc(m.id)}">删除</button></div></div></article>`;
  }
  function emptyView() {
    return `<section class="welcome"><div class="welcome-plant">${sprout}</div><div class="eyebrow">每一段熟悉，都从听懂开始</div><h1>少一点猜测，<br>多懂他一点。</h1><p>把你们的对话放进来。<br>一起看看他在意什么，你可以说什么、做什么。</p><div class="row"><button class="primary" data-action="sample">先用示例体验 ↗</button><button class="secondary" data-action="new">导入我的对话</button></div><small>示例人物是虚构的，分析会真实调用 DeepSeek。</small></section>`;
  }
  function openDialog(html) {
    modal.innerHTML = `<p class="dialog-notice" role="alert"></p><button class="close" data-action="close" aria-label="关闭">×</button>${html}`;
    modal.showModal();
  }
  function newDialog() {
    newCapture = null;
    openDialog(`<div class="eyebrow">先把背景交代清楚</div><h2>新建会话</h2><p class="muted">选择聊天平台，补充这次交流的背景。</p><label>聊天平台<select id="new-platform"><option value="wechat" selected>微信</option><option value="feishu">飞书</option></select></label><div id="feishu-import" hidden><button class="secondary wide" data-action="list-feishu">加载飞书会话列表</button><label>选择飞书会话<select id="new-feishu-chat" disabled><option value="">请先加载会话列表</option></select></label><button class="secondary wide" data-action="read-feishu-selected" disabled>读取所选会话</button><button class="link" data-action="read-feishu">读取飞书当前会话</button><p class="muted" id="feishu-read-status" role="status">可从飞书已加载的会话列表中选择。读取所选会话当前加载的消息；不代表全部历史。</p></div><label>会话类型<select id="new-conversation-type"><option value="auto">自动识别</option><option value="group">群聊</option><option value="direct">个人聊天</option></select></label><label>来自哪个群 / 场景<input id="new-group" maxlength="100" placeholder="例如：城市摄影群"></label><label>这次你想做到什么<input id="new-goal" maxlength="300" value="更理解对方，自然地回应，并做一件合适的事"></label><label>历史与当前对话<textarea id="new-text" rows="7" placeholder="对方：按先后顺序，粘贴几条对话&#10;我：我当时的回应&#10;对方：他现在说的话&#10;备注：必要的背景"></textarea></label><p class="muted">单聊使用“对方： / 我：”；群聊选择“群聊”，每行使用“成员姓名：内容”。背景用“备注：”。</p><p class="disclosure">保存只写入本地数据库。点击后续“分析”才会发送至 DeepSeek。</p><button class="primary wide" data-action="create">保存上下文</button>`);
  }
  async function sample() {
    const existing = data.people.find(p => p.source === 'sample' && p.name === '小林');
    if (existing) {selected=existing.id;render();return;}
    const p = await api('create',{name:'小林',group:'城市摄影群 · 示例',source:'sample',goal:'认真理解他想表达的东西，自然地回应。',text:'对方：拍夜景不只是为了清楚，我想拍出那种下班后终于松口气的感觉。\n我：那种从忙碌里抽离的感觉？\n对方：对，但每次大家都在问参数，很少有人聊照片给人的感受。\n对方：这张你帮我看看构图问题，直接说，不用客气。\n备注：上一条是另一次他主动求技术建议的对话，不能推断他始终排斥技术讨论。\n对方：这组夜景改了三遍，大家还是只问我用什么相机。算了，可能也就那样。'});
    selected=p.id;await refresh();notice('示例上下文已加入。点击“理解他，准备回应”开始真实分析。');
  }
  async function mutate(action, extra={}) {
    const p=person();
    const result=await api(action,{id:p.id,revision:p.revision,...extra});
    await refresh();
    return result;
  }
  async function handle(event) {
    const button=event.target.closest('[data-action]');
    if (!button || button.disabled || busy) return;
    const action=button.dataset.action;
    if(action==='close'){modal.close();return;}
    if (['copy','adopt'].includes(action)) {draft = document.querySelector('#draft').value;replyDrafts.set(draftId,draft);}
    busy=true;button.disabled=true;
    try {
      switch(action){
        case 'wechat':window.location.assign(wechatHref);break;
        case 'select':selected=button.dataset.id;draftId='';render();break;
        case 'toggle-assistant':assistantOpen=!assistantOpen;render();break;
        case 'assistant-settings':openDialog(renderAssistantSettings(person()));break;
        case 'save-assistant-settings':{
          const conversationType=document.querySelector('#assistant-type').value;
          const selfId=document.querySelector('#assistant-self').value;
          const focusIds=[...modal.querySelectorAll('[name="assistant-focus"]:checked:not(:disabled)')].map(el=>el.value)
            .filter(id=>conversationType!=='group'||id!=='counterpart');
          await mutate('assistant-settings',{conversationType,selfId,focusIds});
          modal.close();notice('关注与身份设置已保存。点击“更新分析”生成会话摘要和画像。');break;
        }
        case 'view-member':{
          profileViews.set(selected,{activeMember:button.dataset.member,profileTab:'profile'});render();break;
        }
        case 'profile-tab':{
          profileViews.set(selected,{...(profileViews.get(selected)||{}),profileTab:button.dataset.tab});render();break;
        }
        case 'use-reply':{
          const value=person().analysis?.version===2?person().analysis.response.reply:'';
          if(!value)break;
          if(draft.trim() && draft!==value) {
            openDialog(`<h2>替换当前回复草稿？</h2><p class="muted">你已经有一段草稿。确认后用下面的建议替换。</p><p>${esc(value)}</p><button class="primary wide" data-action="confirm-use-reply">替换草稿</button>`);
          } else {draft=value;replyDrafts.set(draftId,draft);render();notice('已放入回复框，可以继续编辑。');}
          break;
        }
        case 'confirm-use-reply':draft=person().analysis?.response?.reply||draft;replyDrafts.set(draftId,draft);modal.close();render();break;
        case 'import-chat':openDialog(`<h2>补充聊天记录</h2><p class="muted">按先后顺序加入对话。单聊以 对方：、我： 或 备注： 开头；群聊使用 成员姓名：内容。</p><textarea id="follow" aria-label="后续对话" rows="7" placeholder="对方：……&#10;我：……&#10;备注：……">${esc(followDrafts.get(selected)||'')}</textarea><p class="disclosure">加入后会用此人的全部上下文调用 DeepSeek，更新回复和理解。</p><button class="primary wide" data-action="append">加入聊天并更新理解</button>`);break;
        case 'new':newDialog();break;
        case 'sample':await sample();break;
        case 'list-feishu':{
          const platform=document.querySelector('#new-platform');
          const picker=document.querySelector('#new-feishu-chat');
          const status=document.querySelector('#feishu-read-status');
          if(platform?.value!=='feishu')break;
          platform.disabled=true;picker.disabled=true;
          modal.querySelector('[data-action="read-feishu-selected"]').disabled=true;
          status.textContent='正在读取飞书会话列表…';
          try {
            const result=await api('feishu-chats',{});
            if(!modal.open || !picker.isConnected || platform.value!=='feishu')break;
            if(result.status!=='ok') {status.textContent=result.detail || '列表读取未完成。';break;}
            picker.innerHTML='<option value="">请选择一个会话</option>'+result.chats.map(c=>`<option value="${esc(c.choiceId)}">${esc(c.title)}</option>`).join('');
            picker.disabled=false;status.textContent=`已加载 ${result.chats.length} 个会话。${result.detail || '选择后点击读取所选会话。'}`;
          } catch(error) {if(status.isConnected)status.textContent=error.message;}
          finally {if(platform.isConnected)platform.disabled=false;}
          break;
        }
        case 'read-feishu-selected':
        case 'read-feishu':{
          const platform=document.querySelector('#new-platform');
          const group=document.querySelector('#new-group');
          const text=document.querySelector('#new-text');
          const status=document.querySelector('#feishu-read-status');
          if(platform?.value!=='feishu') break;
          const picker=document.querySelector('#new-feishu-chat');
          const selectedRead=action==='read-feishu-selected';
          if(selectedRead && !picker.value)break;
          const controls=[platform,group,text,picker,modal.querySelector('[data-action="create"]'),modal.querySelector('[data-action="list-feishu"]')];
          controls.forEach(el=>el.disabled=true);
          if(newCapture){group.value='';text.value='';}
          newCapture=null;status.textContent=selectedRead?'正在打开并读取所选飞书会话…':'正在读取飞书当前会话…';
          try {
            const result=await api(selectedRead?'feishu-select':'feishu-capture',selectedRead?{choiceId:picker.value}:{});
            if(!modal.open || !text.isConnected || platform.value!=='feishu') break;
            if(result.status!=='ok') {status.textContent=result.detail || '读取未完成，请重试。';break;}
            group.value=result.chatTitle;text.value=result.text;newCapture=result;
            status.textContent=`已读取「${result.chatTitle}」的 ${result.messages.length} 条消息。${result.truncated?'内容超过容量，已截取部分预览。':''}请查看下方原文后保存。`;
          } catch(error) {if(status.isConnected)status.textContent=error.message;}
          finally {controls.forEach(el=>{if(el.isConnected)el.disabled=false;});if(picker.isConnected)picker.disabled=picker.options.length<=1;}
          break;
        }
        case 'create':{
          const p=await api('create',{platform:document.querySelector('#new-platform').value,conversationType:document.querySelector('#new-conversation-type').value,group:document.querySelector('#new-group').value,goal:document.querySelector('#new-goal').value,text:document.querySelector('#new-text').value,source:'imported',captureId:newCapture?.captureId});
          selected=p.id;modal.close();await refresh();break;
        }
        case 'analyze':await mutate('analyze');break;
        case 'cancel':await api('cancel',{});await refresh();notice('已取消本次分析，没有保存新结论。');break;
        case 'copy':await navigator.clipboard.writeText(draft);notice('已复制。你可以到聊天窗口自行编辑、发送。');break;
        case 'adopt':{
          const submittedText=draft, submittedPerson=selected;
          await mutate('adopt',{text:submittedText,analysisId:person().analysis.id});
          if(acknowledgeSentDraft(replyDrafts,submittedPerson,submittedText) && selected===submittedPerson) draft='';
          render();notice('已记录你确认发出的内容。等有后续回应，再补充进来。');break;
        }
        case 'append':{
          const value=document.querySelector('#follow').value;
          if (!pendingAppend || pendingAppend.text !== value || pendingAppend.personId !== selected) pendingAppend={text:value,personId:selected,id:crypto.randomUUID()};
          const p=person();
          await api('append',{id:p.id,revision:p.revision,text:value,operationId:pendingAppend.id});
          if ((followDrafts.get(selected) ?? value) === value) followDrafts.delete(selected);
          pendingAppend=null;
          modal.close();
          await refresh();
          await mutate('analyze');
          break;
        }
        case 'show-history':case 'evidence':{
          assistantOpen=false;render();
          const el=document.getElementById('msg-'+button.dataset.mid);
          if(el){el.scrollIntoView({behavior:'smooth',block:'center'});el.classList.add('highlight');}
          break;
        }
        case 'correct':{
          const m=person().messages.find(m=>m.id===button.dataset.mid);
          openDialog(`<h2>纠正这条记录</h2><p class="muted">纠正后，当前人物的旧分析和建议会失效。归属错误请删除后按正确角色重新导入。</p><textarea id="correction" rows="6">${esc(m.text)}</textarea><button class="primary wide" data-action="save-correction" data-mid="${esc(m.id)}">保存纠正</button>`);break;
        }
        case 'save-correction':await mutate('correct',{messageId:button.dataset.mid,text:document.querySelector('#correction').value});modal.close();notice('记录已纠正，旧建议已撤销。');break;
        case 'remove-message':openDialog(`<h2>删除这条记录？</h2><p>原文将从本地数据库删除，当前派生建议也会撤销。</p><button class="primary wide" data-action="confirm-remove" data-mid="${esc(button.dataset.mid)}">删除记录</button>`);break;
        case 'confirm-remove':await mutate('remove-message',{messageId:button.dataset.mid});modal.close();notice('已删除原文并撤销旧建议。');break;
        case 'delete-person':openDialog('<h2>删除这个人物的数据？</h2><p>本地保存的全部原文和派生分析将被删除。此操作不可撤销。</p><button class="primary wide" data-action="confirm-delete">删除全部数据</button>');break;
        case 'confirm-delete':await mutate('delete');modal.close();notice('这个人物的本地数据已删除。');break;
        case 'about':openDialog(`<h2>这是一个真正运行的初版。</h2><div class="about"><p><b>后端</b><br>dsh-java 内核加载 garden-demo 插件，复用 model-registry 与 model-deepseek。</p><p><b>前端</b><br>原版模块加载器 + Cordis 的 garden-ui 插件。</p><p><b>保存</b><br>独立 PostgreSQL 数据库，刷新或重启后保留。数据库管理员仍可访问数据。</p><p><b>模型</b><br>${esc(data.model)} · ${data.modelConfigured ? '已配置凭据' : '尚未配置凭据'}。只发送当前人物名称、目标和最多80条上下文。推理过程不保存。</p><p><b>边界</b><br>飞书可通过独立插件读取当前客户端会话，预览后保存；微信可手动导入对话。聊天日志只在本机识别指定群的可见页面，按成员保存；不会全量后台同步，也不会自动分析或代发。模型解读是推测，你可以纠正原文并重新分析。删除只清除本地数据库，无法撤回已发送给模型服务的内容。</p></div>`);break;
      }
    } catch(error){if(error.name!=='AbortError') notice(error.message);}
    finally{busy=false;if(button.isConnected)button.disabled=false;}
  }
  const onInput=e=>{
    if(['new-text','new-group'].includes(e.target.id) && newCapture) {
      newCapture=null;
      const status=document.querySelector('#feishu-read-status');
      if(status)status.textContent='内容已编辑，将按手动内容保存。重新读取可恢复飞书来源标记。';
    }
    if(e.target.id==='follow') followDrafts.set(selected,e.target.value);
    if(e.target.id==='draft') {
      draft=e.target.value;
      replyDrafts.set(draftId,draft);
      app.querySelectorAll('[data-action="copy"],[data-action="adopt"]').forEach(b=>b.disabled=!draft.trim() || (b.dataset.action==='adopt' && !person()?.analysis));
    }
  };
  document.addEventListener('click',handle,{signal:abort.signal});
  document.addEventListener('input',onInput,{signal:abort.signal});
  document.addEventListener('change',e=>{
    if(e.target.id==='assistant-self') {
      modal.querySelectorAll('[name="assistant-focus"]').forEach(el=>{el.disabled=el.value===e.target.value;if(el.disabled)el.checked=false;});
    }
    if(e.target.id==='new-feishu-chat') {
      modal.querySelector('[data-action="read-feishu-selected"]').disabled=!e.target.value;
      if(newCapture) {document.querySelector('#new-group').value='';document.querySelector('#new-text').value='';newCapture=null;}
      document.querySelector('#feishu-read-status').textContent=e.target.value?'已选择会话，点击“读取所选会话”获取消息。':'请选择一个会话。';
    }
    if(e.target.id==='new-platform') {
      document.querySelector('#feishu-import').hidden=e.target.value!=='feishu';
      if(newCapture) {
        document.querySelector('#new-group').value='';document.querySelector('#new-text').value='';newCapture=null;
      }
    }
  },{signal:abort.signal});
  refresh().catch(e=>{app.textContent='加载失败：'+e.message;});
  return ()=>{abort.abort();clearTimeout(timer);clearTimeout(noticeTimer);app.replaceChildren();modal.close();};
}
