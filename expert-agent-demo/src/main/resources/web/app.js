'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const formatText = value => esc(value).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/^#{1,4} (.+)$/gm, '<strong>$1</strong>');
let state = {documents:[],experts:[]}, csrf = '', currentExpert = '', currentVersion = '', conversationId = '', busy = false, jobId = '', view = 'library';
let pollTimer, importTimer;
const date = value => new Date(value).toLocaleString('zh-CN', {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});
function notice(message, error = false) { $('notice').textContent = message; $('notice').classList.toggle('error', error); $('notice').hidden = !message; }
async function api(path, body, raw = false) {
  const options = body === undefined ? {} : {method:'POST', headers:{'X-Lab-Token':csrf, 'Content-Type':raw?'application/pdf':'application/json'}, body:raw?body:JSON.stringify(body)};
  const response = await fetch(path, options); const data = await response.json();
  if (!response.ok) { const error = new Error(data.error || '服务暂不可用'); error.status = response.status; throw error; }
  return data;
}
function showView(name) {
  view = name; document.querySelectorAll('.view').forEach(el => el.hidden = el.id !== 'view-' + name);
  document.querySelectorAll('.nav').forEach(el => el.classList.toggle('active', el.dataset.view === name));
  $('breadcrumb').textContent = '专家实验室 / ' + ({library:'资料库',experts:'专家工作台',chat:'试聊与验证'}[name]);
  history.replaceState(null, '', '#' + name);
}
function selectedExpert() { return state.experts.find(e => e.id === currentExpert); }
function selectedVersion() { return selectedExpert()?.versions.find(v => v.id === currentVersion); }
async function refresh() {
  state = await api('/api/state'); csrf = state.csrf;
  if (currentExpert && !selectedExpert()) { currentExpert = ''; currentVersion = ''; }
  renderLibrary(); renderSelectors(); renderTeam();
}
function renderLibrary() {
  $('imports').innerHTML=(state.imports||[]).filter(r=>r.status!=='completed').map(r=>`<article class="document"><div class="document-header"><div class="doc-info"><h3>${esc(r.title||'PDF 云解析')}</h3><p>${esc(r.message||r.status)}</p></div>${r.status==='running'?`<button class="secondary" data-watch-import="${esc(r.id)}">查看进度</button>`:''}${r.canSplit?`<button class="secondary" data-split-import="${esc(r.id)}">分成每份最多200页，解析后合并</button>`:''}${r.canResume?`<button class="secondary" data-resume-import="${esc(r.id)}">继续原任务</button>`:''}</div></article>`).join('');
  $('stat-docs').textContent = state.documents.length; $('stat-versions').textContent = state.documents.reduce((n,d)=>n+d.versions.length,0); $('stat-experts').textContent = state.experts.length;
  $('documents').innerHTML = state.documents.length ? state.documents.map(doc => `<article class="document"><div class="document-header"><span class="pdf-icon">PDF</span><div class="doc-info"><h3>${esc(doc.title)}</h3><p>${doc.versions.length} 个版本 · 最新上传 ${esc(date(doc.versions.at(-1).createdAt))}</p></div><button class="secondary add-version" data-id="${esc(doc.id)}">＋ 新版本</button></div><div class="versions">${[...doc.versions].reverse().map(v=>`<div class="version-row"><b>V${v.number}</b><span>${v.pageCount} 页 · ${v.charCount.toLocaleString()} 字 · ${v.chunkCount} 个片段</span><span>${esc(v.filename)}</span><a href="/api/original?versionId=${encodeURIComponent(v.id)}#page=1" target="_blank" rel="noopener">原 PDF ↗</a><button class="text-button" data-document-version="${esc(v.id)}">查看解析</button>${v.blankPages?`<span class="warning">${v.blankPages} 页未提取文字，请核查扫描页</span>`:''}</div>`).join('')}</div></article>`).join('') : `<div class="empty"><span class="empty-symbol">▤</span><h3>先放入第一份资料</h3><p>支持书籍、文章与方法手册。使用 MinerU 云 OCR，保留版本和原 PDF 页码。</p><button class="secondary" id="empty-upload">上传一份 PDF →</button></div>`;
  const old = new Map([...document.querySelectorAll('.selection')].map(el=>[el.dataset.doc,{checked:el.querySelector('input').checked,version:el.querySelector('select').value}]));
  $('selections').innerHTML = state.documents.length ? state.documents.map(doc => {
    const prior = old.get(doc.id); const chosen = prior?.version || doc.versions.at(-1).id;
    return `<label class="selection" data-doc="${esc(doc.id)}"><input type="checkbox" ${prior?.checked?'checked':''} aria-label="选择 ${esc(doc.title)}"><span>${esc(doc.title)}</span><select aria-label="${esc(doc.title)} 的版本">${[...doc.versions].reverse().map(v=>`<option value="${esc(v.id)}" ${v.id===chosen?'selected':''}>V${v.number} · ${v.pageCount}页 · ${esc(date(v.createdAt))}</option>`).join('')}</select></label>`;
  }).join('') : `<p class="muted">资料库还没有 PDF，请先上传。</p>`;
  $('upload-document').innerHTML = `<option value="">新建资料</option>` + state.documents.map(d=>`<option value="${esc(d.id)}">${esc(d.title)} · 添加新版本</option>`).join('');
}
function renderSelectors() {
  const choices = state.experts.map(e=>`<option value="${esc(e.id)}">${esc(e.name)}</option>`).join('');
  $('edit-expert').innerHTML = '<option value="">＋ 创建新专家</option>' + choices; $('edit-expert').value = currentExpert;
  $('chat-expert').innerHTML = '<option value="">请选择专家</option>' + choices; $('chat-expert').value = currentExpert;
  const e = selectedExpert();
  if (e && !e.versions.some(v=>v.id===currentVersion)) currentVersion = e.versions.at(-1).id;
  const versions = e ? [...e.versions].reverse().map(v=>`<option value="${esc(v.id)}">V${v.number} · ${e.activeVersionId===v.id?'已启用':v.tested?'已试聊':'待试聊'}</option>`).join('') : '<option value="">暂无版本</option>';
  ['expert-version','chat-version'].forEach(id=>{$(id).innerHTML=versions;$(id).value=currentVersion;});
  $('expert-result').hidden = !e;
}
function sourceButtons(ids, refs = []) {
  return `<div class="sources">${[...new Set(ids)].map((id,i)=>{const r=refs.find(x=>x.id===id);return `<button type="button" class="source-link" data-source="${esc(id)}">${r?esc(r.title)+' · 第'+r.page+'页':'原文依据 '+(i+1)}</button>`;}).join('')}</div>`;
}
function renderTeam() {
  const e=selectedExpert(),v=selectedVersion(); if(!e||!v){$('expert-team').innerHTML='';$('chat-status').textContent='请先生成专家';return;}
  $('chat-status').textContent=e.activeVersionId===v.id?'正式版本':'草稿试聊';
  $('activate').disabled=busy||!v.tested||e.activeVersionId===v.id;
  $('activate').textContent=e.activeVersionId===v.id?'此版本已启用':v.tested?'启用此版本':'试聊后可启用';
  $('expert-team').innerHTML=`<article class="lead-agent"><div class="mini">LEAD EXPERT · V${v.number}</div><h3>${esc(v.name)}</h3><p>${esc(v.duty)}</p><p>${esc(v.summary)}</p><div class="sources"><span class="tag">${v.subagents.length} 个子 Agent</span><span class="tag">${v.processedChunks} 个原文片段</span><span class="tag">${v.methodCount} 条方法候选</span><span class="tag">${esc(v.model)}</span></div></article><div class="agents-grid">${v.subagents.map((a,i)=>`<article class="agent-card"><div class="agent-num">SPECIALIST ${String(i+1).padStart(2,'0')}</div><h3>${esc(a.name)}</h3><p>${esc(a.duty)}</p><p><strong>何时调用：</strong>${esc(a.when)}</p><details><summary>${a.methods.length} 条方法与原文依据</summary>${a.methods.map(m=>`<div class="method"><strong>${esc(m.title)}</strong><p>适用：${esc(m.when)}</p><p>方法：${esc(m.steps)}</p><p>边界：${esc(m.limits)}</p>${sourceButtons(m.sourceIds)}</div>`).join('')}</details></article>`).join('')}</div>`;
}
function loadForm() {
  const v=selectedVersion(); $('expert-name').value=v?.name||''; $('expert-duty').value=v?.duty||'';
  document.querySelectorAll('.selection').forEach(el=>{const s=v?.selections.find(s=>s.documentId===el.dataset.doc);el.querySelector('input').checked=!!s;if(s)el.querySelector('select').value=s.versionId;});
}
function resetChat(){conversationId='';$('messages').innerHTML='<div class="empty chat-empty"><span class="empty-symbol">✳</span><h3>开始一次新的试聊</h3><p>描述一个具体问题，观察专家如何分析和调用专业分工。</p></div>';$('chat-trace').innerHTML='<div class="trace-placeholder">等待一个具体问题。</div>';}
function chooseExpert(id, version=''){currentExpert=id;currentVersion=version;renderSelectors();renderTeam();loadForm();resetChat();}
function uploadDialog(id=''){$('upload-form').reset();$('upload-error').textContent='';$('file-label').textContent='点击选择或拖入 PDF';$('upload-document').value=id;uploadDocumentChanged();$('upload-dialog').showModal();}
function uploadDocumentChanged(){const d=state.documents.find(d=>d.id===$('upload-document').value);$('document-title').value=d?.title||'';$('document-title').readOnly=!!d;}
function fileChanged(){const f=$('pdf-file').files[0];$('file-label').textContent=f?f.name:'点击选择或拖入 PDF';if(f&&!$('document-title').value)$('document-title').value=f.name.replace(/\.pdf$/i,'');}
function setBusy(value){busy=value;$('expert-fields').disabled=value;['edit-expert','expert-version','chat-expert','chat-version','new-chat','send','chat-input'].forEach(id=>$(id).disabled=value);renderTeam();}
function addMessage(role,content,refs=[]){$('messages').querySelector('.empty')?.remove();const el=document.createElement('div');el.className='message '+role;el.innerHTML=`<div class="message-label">${role==='user'?'你':esc(selectedVersion()?.name||'专家')}</div><div class="message-content">${formatText(content)}</div>${sourceButtons(refs.map(r=>r.id),refs)}`;$('messages').append(el);$('messages').scrollTop=$('messages').scrollHeight;}
async function watchJob(id,kind){jobId=id;setBusy(true);$('job-panel').hidden=false;$('job-spinner').hidden=false;$('cancel-job').hidden=false;$('job-title').textContent=kind==='generate'?'正在生成专家团队':'专家正在分析';sessionStorage.setItem('expertLabJob',JSON.stringify({id,kind}));
  const poll=async()=>{try{const j=await api('/api/jobs/'+id);$('job-message').textContent=j.message||'正在准备';$('job-events').innerHTML=j.events.map(e=>`<div>${esc(e.message)}</div>`).join('');if(kind==='chat')$('chat-trace').innerHTML=j.events.map(e=>`<div class="trace-event">${esc(e.message)}</div>`).join('');
    if(j.status==='running'){pollTimer=setTimeout(poll,1000);return;}
    if(j.status!=='completed'){ sessionStorage.removeItem('expertLabJob');jobId='';setBusy(false);$('job-spinner').hidden=true;$('cancel-job').hidden=true; $('job-title').textContent=j.status==='cancelled'?'任务已取消':'任务未完成';notice(j.error||'任务失败',true);$('job-message').textContent=j.error||'';return; }
    const r=j.result; currentExpert=r.expertId;currentVersion=r.versionId;await refresh();
    if(kind==='generate'){resetChat();showView('experts');loadForm();notice('专家草稿已生成。请查看方法与原文，再进入试聊。');$('expert-result').scrollIntoView({behavior:'smooth',block:'start'});}
    else{conversationId=r.conversationId;showView('chat');addMessage('assistant',r.answer,r.references);$('chat-trace').innerHTML+=r.reports.map(a=>`<details class="report"><summary>${esc(a.name)} · 分析结果</summary><p>${formatText(a.analysis)}</p>${sourceButtons(a.sourceIds,a.references)}</details>`).join('');notice(r.verifiedTrial?'本轮专业试聊完成，可在专家工作台启用此版本。':'本轮直接回答已完成；请用专业问题验证子 Agent 后再启用。');}
    sessionStorage.removeItem('expertLabJob');jobId='';setBusy(false);$('job-spinner').hidden=true;$('cancel-job').hidden=true;$('job-panel').hidden=true;
  }catch(e){notice(e.message,true);if(e.status===400||e.status===404){sessionStorage.removeItem('expertLabJob');jobId='';setBusy(false);$('job-spinner').hidden=true;$('cancel-job').hidden=true;$('job-title').textContent='无法读取任务';$('job-message').textContent='任务已不存在，请重新执行。';}else{$('job-title').textContent='连接暂时中断，正在重试';$('job-message').textContent='任务记录已保留，可刷新恢复或取消任务。';pollTimer=setTimeout(poll,3000);}}};poll();
}
document.addEventListener('click',async e=>{const nav=e.target.closest('[data-view]');if(nav)showView(nav.dataset.view);const source=e.target.closest('[data-source]');if(source){try{const p=await api('/api/passage?id='+encodeURIComponent(source.dataset.source));$('source-title').textContent=p.title;$('source-meta').textContent='第 '+p.page+' 页 · 来源版本 '+p.versionId.slice(0,8);$('source-text').textContent=p.text;$('source-original').href='/api/original?versionId='+encodeURIComponent(p.versionId)+'#page='+p.page;$('source-dialog').showModal();}catch(error){notice(error.message,true);}}const version=e.target.closest('.add-version');if(version)uploadDialog(version.dataset.id);if(e.target.id==='empty-upload')uploadDialog();});
$('upload-open').onclick=()=>uploadDialog();$('upload-close').onclick=()=>$('upload-dialog').close();$('source-close').onclick=()=>$('source-dialog').close();$('upload-document').onchange=uploadDocumentChanged;$('pdf-file').onchange=fileChanged;
['dragenter','dragover'].forEach(ev=>$('dropzone').addEventListener(ev,e=>{e.preventDefault();$('dropzone').classList.add('dragover');}));['dragleave','drop'].forEach(ev=>$('dropzone').addEventListener(ev,e=>{e.preventDefault();$('dropzone').classList.remove('dragover');if(ev==='drop'&&e.dataTransfer.files.length){$('pdf-file').files=e.dataTransfer.files;fileChanged();}}));
$('upload-form').onsubmit=async e=>{e.preventDefault();if(busy)return;const f=$('pdf-file').files[0];if(!f||f.size>20*1024*1024){$('upload-error').textContent='请选择 20MB 内的 PDF';return;}$('upload-submit').disabled=true;$('upload-submit').textContent='正在接收 PDF…';$('upload-error').textContent='';try{const q=new URLSearchParams({title:$('document-title').value.trim(),documentId:$('upload-document').value,filename:f.name});const r=await api('/api/documents?'+q,f,true);$('upload-dialog').close();watchImport(r.importId);}catch(err){$('upload-error').textContent=err.message;}finally{$('upload-submit').disabled=false;$('upload-submit').textContent='上传并解析';}};
$('edit-expert').onchange=e=>chooseExpert(e.target.value);$('chat-expert').onchange=e=>chooseExpert(e.target.value);
['expert-version','chat-version'].forEach(id=>$(id).onchange=e=>{currentVersion=e.target.value;renderSelectors();renderTeam();loadForm();resetChat();});
$('expert-form').onsubmit=async e=>{e.preventDefault();if(busy)return;const selections=[...document.querySelectorAll('.selection')].filter(el=>el.querySelector('input').checked).map(el=>({documentId:el.dataset.doc,versionId:el.querySelector('select').value}));if(!selections.length){notice('请至少选择一份 PDF 资料。',true);return;}try{setBusy(true);const r=await api('/api/experts',{expertId:currentExpert,name:$('expert-name').value.trim(),duty:$('expert-duty').value.trim(),selections});notice('');watchJob(r.jobId,'generate');}catch(err){setBusy(false);notice(err.message,true);}};
$('trial-open').onclick=()=>{showView('chat');$('chat-input').focus();};$('new-chat').onclick=resetChat;
$('activate').onclick=async()=>{try{await api('/api/activate',{expertId:currentExpert,versionId:currentVersion});await refresh();notice('此专家版本已启用；之后生成的草稿不会替换它。');}catch(err){notice(err.message,true);}};
$('chat-form').onsubmit=async e=>{e.preventDefault();if(busy)return;const message=$('chat-input').value.trim();if(!selectedVersion()){notice('请先选择一个已生成的专家版本。',true);return;}try{setBusy(true);const r=await api('/api/chat',{expertId:currentExpert,versionId:currentVersion,conversationId,message});addMessage('user',message);$('chat-input').value='';notice('');watchJob(r.jobId,'chat');}catch(err){setBusy(false);notice(err.message,true);}};
$('cancel-job').onclick=async()=>{if(jobId)try{await api('/api/jobs/'+jobId+'/cancel',{});}catch(err){notice(err.message,true);}};

async function watchImport(id){
  clearTimeout(importTimer);setBusy(true);$('job-panel').hidden=false;$('job-spinner').hidden=false;$('cancel-job').hidden=false;$('job-title').textContent='MinerU 云解析';
  sessionStorage.setItem('expertLabImport',id);
  const poll=async()=>{try{
    const r=await api('/api/imports/'+encodeURIComponent(id));jobId=r.jobId||'';
    $('job-message').textContent=r.message||'准备解析';$('job-events').textContent=(r.timeline||[]).map(e=>e.phase+' · '+date(e.at)).join('\n');
    if(r.status==='running'){importTimer=setTimeout(poll,5000);return;}
    await refresh();sessionStorage.removeItem('expertLabImport');jobId='';setBusy(false);$('job-spinner').hidden=true;$('cancel-job').hidden=true;
    if(r.status==='completed'){$('job-panel').hidden=true;notice('PDF 已完整解析并入库。请核对原文和异常页，再生成专家。');}
    else{$('job-title').textContent=r.status==='cancelled'?'本地处理已取消':r.status==='failed'?'解析失败':'解析已暂停';notice(r.error||r.message,true);}
  }catch(e){notice(e.message,true);$('job-message').textContent='连接中断，保留原任务，恢复后继续查看';importTimer=setTimeout(poll,5000);}};
  poll();
}
document.addEventListener('click',async e=>{
  const split=e.target.closest('[data-split-import]'),resume=e.target.closest('[data-resume-import]'),watch=e.target.closest('[data-watch-import]'),detail=e.target.closest('[data-document-version]');
  try{
    if(split&&!busy){setBusy(true);const r=await api('/api/imports/'+encodeURIComponent(split.dataset.splitImport)+'/split',{});watchImport(r.importId);}
    if(resume&&!busy){const r=await api('/api/imports/'+encodeURIComponent(resume.dataset.resumeImport)+'/resume',{});watchImport(r.importId);}
    if(watch&&!busy)watchImport(watch.dataset.watchImport);
    if(detail){const v=await api('/api/document-version?versionId='+encodeURIComponent(detail.dataset.documentVersion));const report=v.parser?.report;
      $('document-report').textContent='文件：'+v.filename+'\nPDF 物理页数：'+v.pageCount+'\n字符数：'+v.charCount+'\nSHA-256：'+v.sha256+'\n解析方式：'+(v.parser?.provider||'local')+'\n'+(report?JSON.stringify(report,null,2):'旧版资料无云解析报告');$('document-dialog').showModal();}
  }catch(error){if(split)setBusy(false);notice(error.message,true);}
});
$('document-close').onclick=()=>$('document-dialog').close();

(async()=>{try{await refresh();const initial=location.hash.slice(1);showView(['library','experts','chat'].includes(initial)?initial:'library');const pending=JSON.parse(sessionStorage.getItem('expertLabJob')||'null');const importing=sessionStorage.getItem('expertLabImport')||(state.imports||[]).find(r=>r.status==='running')?.id;if(importing)watchImport(importing);else if(pending)watchJob(pending.id,pending.kind);}catch(err){notice('无法连接专家实验室：'+err.message,true);}})();
