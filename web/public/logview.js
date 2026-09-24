(() => {
  'use strict';
  const el = id => document.getElementById(id);
  const logger = document.body.dataset.mode === 'logger';
  const endpoint = logger ? '/v1/state' : '/api/wechat/state';
  const attachmentBase = logger ? '/v1/attachment/' : '/api/wechat/attachment/';
  const abort = new AbortController();
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let state, timer, busy = false, signature = '';
  el('import-panel').hidden = !logger;
  el('viewer-note').hidden = logger;
  el('brand-name').textContent = logger ? '聊天日志' : '爱聊';
  el('app-role').textContent = logger ? '自动采集与保存' : '日志阅读';
  el('connection-copy').textContent = logger ? '微信本机数据库 · 自动日志' : '来自独立聊天日志应用';

  function renderMessages() {
    if (!state) return;
    const member = el('member-filter').value || 'all';
    const query = el('search').value.trim().toLocaleLowerCase();
    let shown = 0;
    const html = state.segments.map((batch, index) => {
      const messages = batch.messages.filter(m => (member === 'all' || m.memberId === member) && (!query || `${m.author}\n${m.text}`.toLocaleLowerCase().includes(query)));
      if (!messages.length) return '';
      shown += messages.length;
      return `<section class="batch"><header class="batch-heading"><span>${['wechat_database','wechat_cli_history'].includes(batch.source) ? '自动同步' : '导入批次'} ${index + 1}</span><span>${escape(batch.filename)}</span></header>${messages.map(m => `<article class="message"><span class="avatar">${escape(Array.from(m.author)[0] || '·')}</span><div class="message-body"><div class="message-meta"><b>${escape(m.author)}</b><time>${escape(m.sentAtLabel)}</time></div><div class="bubble">${escape(m.text || '（无文字正文）')}</div>${m.attachments.map(a => `<a class="attachment" href="${attachmentBase}${encodeURIComponent(a.id)}" download>${escape(a.name)} <span>↓ 下载原始附件</span></a>`).join('')}</div></article>`).join('')}</section>`;
    }).join('');
    const log = el('messages'), previous = log.scrollTop;
    log.innerHTML = html || `<div class="empty"><div class="empty-mark">记</div><h2>${state.group.messageCount ? '没有匹配的记录' : ['wechat_database','wechat_cli_history'].includes(state.source.type) ? (state.source.status === 'setup_required' ? '等待首次连接' : '尚未保存本机消息') : '等待第一份原生消息包'}</h2><p>${state.group.messageCount ? '更换成员或搜索词后查看。' : ['wechat_database','wechat_cli_history'].includes(state.source.type) ? escape(state.source.detail || '日志服务正在检查目标群记录。') : logger ? '可以展开历史消息包导入，保存已有导出包。' : '日志应用保存记录后，这里会自动更新。'}</p></div>`;
    log.scrollTop = previous;
    el('shown-count').textContent = `${shown} 条当前显示`;
  }

  function render(next) {
    state = next;
    const offline = state.source.status === 'offline';
    const automatic = ['wechat_database','wechat_cli_history'].includes(state.source.type);
    const labels = {setup_required:'自动采集 · 待首次连接',syncing:'正在同步本机记录',connected:'自动采集已连接',error:'自动采集暂未就绪'};
    el('connection').textContent = offline ? '日志应用未连接' : automatic ? (labels[state.source.status] || '正在检查自动采集') : logger ? '正在接收消息包' : '已连接聊天日志';
    if (el('source-tag')) el('source-tag').textContent = automatic ? '来源 · 微信本机数据库' : '来源 · 微信原生导出包';
    el('connection').dataset.status = offline || ['setup_required','error'].includes(state.source.status) ? 'offline' : 'online';
    el('count-messages').textContent = String(state.group.messageCount);
    el('count-members').textContent = String(state.group.memberCount);
    el('count-batches').textContent = String(state.stats.observations);
    el('count-duplicates').textContent = String(state.stats.duplicates);
    el('coverage').textContent = state.coverage.description;
    el('inbox-status').textContent = state.source.detail || state.inbox?.error || (logger ? '原生 ZIP 导入后自动归档；重复包自动跳过。' : '本页仅从日志应用读取。');
    const select = el('member-filter'), saved = select.value || 'all';
    const key = JSON.stringify(state.members);
    if (key !== signature) {
      select.innerHTML = `<option value="all">全部成员</option>${state.members.map(m => `<option value="${escape(m.id)}">${escape(m.name)}</option>`).join('')}`;
      signature = key;
    }
    select.value = state.members.some(m => m.id === saved) ? saved : 'all';
    renderMessages();
  }

  async function refresh() {
    try {
      const response = await fetch(endpoint, {credentials:'same-origin', signal:abort.signal});
      if (!response.ok) throw new Error('日志服务暂时未连接');
      render(await response.json());
    } catch (error) {
      if (error.name !== 'AbortError') { el('connection').textContent = '日志服务暂时未连接'; el('connection').dataset.status = 'offline'; }
    }
  }
  async function poll() {
    await refresh();
    if (!abort.signal.aborted) timer = window.setTimeout(poll, 2000);
  }

  async function importFiles(files) {
    if (!logger || busy || !files?.length) return;
    busy = true; el('choose-file').disabled = true;
    try {
      for (const file of files) {
        if (!file.name.toLowerCase().endsWith('.zip') || file.size > 64 * 1024 * 1024) throw new Error('请选择不超过 64 MB 的微信原生 ZIP 消息包。');
        el('import-result').textContent = '正在保存原文和附件…';
        const response = await fetch('/v1/import', {method:'POST', credentials:'same-origin', signal:abort.signal,
          headers:{'Content-Type':'application/zip','X-Logbook-Request':'1','X-Logbook-Target':'target','X-Logbook-Filename':encodeURIComponent(file.name)}, body:file});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || '消息包未导入');
        el('import-result').textContent = result.duplicate ? '这份消息包已经保存，未重复新增。' : `已保存 ${result.added} 条原文消息。`;
      }
      await refresh();
    } catch (error) { if(error.name !== 'AbortError') el('import-result').textContent = error.message; }
    finally { busy = false; el('choose-file').disabled = false; el('file-input').value = ''; }
  }

  el('member-filter').addEventListener('change', renderMessages);
  el('search').addEventListener('input', renderMessages);
  if (logger) {
    el('choose-file').addEventListener('click', () => el('file-input').click());
    el('file-input').addEventListener('change', e => importFiles(e.target.files));
    el('dropzone').addEventListener('dragover', e => {e.preventDefault(); el('dropzone').classList.add('over');});
    el('dropzone').addEventListener('dragleave', () => el('dropzone').classList.remove('over'));
    el('dropzone').addEventListener('drop', e => {e.preventDefault(); el('dropzone').classList.remove('over'); importFiles(e.dataTransfer.files);});
  }
  window.addEventListener('pagehide', () => {abort.abort(); window.clearTimeout(timer);});
  poll();
})();
