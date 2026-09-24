(() => {
  'use strict';

  const root = document.querySelector('#wechat-app');
  const toast = document.querySelector('#toast');
  const requestAbort = new AbortController();
  const targetGroup = '卧底不追高';
  let state = null;
  let memberFilter = 'all';
  let pollTimer = 0;
  let toastTimer = 0;
  let actionPending = false;
  let requestGeneration = 0;
  let suspended = false;

  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[character]));

  const numberText = value => Number.isFinite(Number(value)) ? String(Number(value)) : '0';

  function formatDate(value, fallback = '尚无') {
    if (!value) return fallback;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return fallback;
    return date.toLocaleString('zh-CN', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    });
  }

  function collectorPresentation(collector = {}) {
    const status = collector.status || (collector.enabled ? 'running' : 'idle');
    const presentations = {
      running: ['正在采集', 'live', '请保持微信打开目标群；每次滚动停稳后会继续读取。'],
      starting: ['正在启动', 'live', '正在连接本机截图与 OCR。'],
      capturing: ['正在读取页面', 'live', '正在使用本机 OCR 读取当前可见的目标群消息。'],
      stabilizing: ['等待页面停稳', 'live', '滚动结束后稍停片刻，页面稳定时会自动保存。'],
      waiting_content: ['等待可见消息', 'live', '请打开目标群并滚动到有消息的页面。'],
      idle: ['尚未开始', 'idle', '点击开始后，打开目标微信群并缓慢滚动。'],
      paused: ['已暂停', 'idle', '已保存的消息仍可查看；继续时再点击开始。'],
      permission_required: ['需要屏幕录制权限', 'warning', '请在系统设置中允许爱聊读取屏幕，然后重新开始。'],
      wechat_not_running: ['未找到微信', 'warning', '请打开微信桌面客户端和目标群。'],
      window_unavailable: ['未找到聊天窗口', 'warning', '请让微信聊天窗口保持可见。'],
      other_chat: ['当前不是目标群', 'warning', `请切换到“${targetGroup}”，采集不会读取其他会话。`],
      capture_error: ['本次读取失败', 'error', '采集会继续重试；若持续失败，可暂停后重新开始。'],
      helper_missing: ['采集组件不可用', 'error', '请重新启动完整的爱聊采集服务。'],
      error: ['采集服务异常', 'error', '请暂停后重新开始；已保存的消息不会丢失。']
    };
    const view = presentations[status] || [status, 'idle', '正在等待下一次状态更新。'];
    return {
      status,
      title: view[0],
      tone: view[1],
      detail: collector.detail || view[2]
    };
  }

  function segmentLabel(segment, index) {
    const created = formatDate(segment?.createdAt, '时间未记录');
    return `片段 ${index + 1} · ${created}`;
  }

  function gapText(gap) {
    if (!gap) return '';
    if (typeof gap === 'string') return `覆盖缺口 · ${gap}`;
    if (typeof gap === 'object' && gap.description) return `覆盖缺口 · ${gap.description}`;
    return '覆盖缺口 · 相邻页面没有可靠重叠，期间可能还有未采集消息';
  }

  function messageText(message) {
    const kind = String(message.kind || 'text').toLowerCase();
    if (message.text) return String(message.text);
    if (['image', 'video', 'voice', 'file', 'media', 'sticker'].includes(kind)) return '媒体内容（OCR 无法读取）';
    return '未识别到文字';
  }

  function messageView(message) {
    const kind = String(message.kind || 'text').toLowerCase();
    const media = kind !== 'text' && kind !== 'message';
    const author = message.author || '成员未识别';
    const initial = author === '成员未识别' ? '?' : Array.from(String(author))[0];
    const hasConfidence = message.confidence !== null && message.confidence !== undefined && message.confidence !== '';
    const confidence = Number(message.confidence);
    const confidenceText = hasConfidence && Number.isFinite(confidence)
      ? `OCR ${Math.round(Math.max(0, Math.min(1, confidence)) * 100)}%`
      : 'OCR 置信度未知';
    const originalTime = message.timeLabel ? `可见时间标签：${message.timeLabel}` : '原消息时间不可见';
    const capturedTime = `采集于 ${formatDate(message.capturedAt, '时间未记录')}`;
    return `<article class="chat-message${media ? ' media' : ''}" data-message-id="${escapeHtml(message.id)}">
      <span class="chat-avatar" aria-hidden="true">${escapeHtml(initial)}</span>
      <div class="message-copy">
        <div class="message-meta">${escapeHtml(author)}<span class="ocr">来源：本机截图 OCR</span></div>
        <div class="bubble">${escapeHtml(messageText(message))}</div>
        <div class="message-foot">${escapeHtml(originalTime)} · ${escapeHtml(capturedTime)} · ${escapeHtml(confidenceText)}</div>
      </div>
    </article>`;
  }

  function segmentsView(segments = []) {
    let visibleCount = 0;
    const html = segments.map((segment, index) => {
      const messages = (segment.messages || []).filter(message => memberFilter === 'all' || String(message.memberId) === memberFilter);
      if (!messages.length) return '';
      visibleCount += messages.length;
      const gap = gapText(segment.gapBefore);
      return `<section class="segment" data-segment-id="${escapeHtml(segment.id)}">
        ${gap ? `<div class="gap-label">${escapeHtml(gap)}</div>` : ''}
        <div class="segment-label"><span>${escapeHtml(segmentLabel(segment, index))}</span></div>
        ${messages.map(messageView).join('')}
      </section>`;
    }).join('');
    if (!visibleCount) {
      const text = memberFilter === 'all'
        ? '开始采集后，滚动目标微信群。停稳的可见消息会保存在这里。'
        : '这个成员在当前已保存片段里还没有消息。';
      return `<div class="empty-state"><b>还没有可显示的消息</b>${escapeHtml(text)}</div>`;
    }
    return `<div class="timeline-intro">当前筛选下共 ${visibleCount} 条 · 按覆盖片段显示</div>${html}<div class="timeline-end">以上是当前已保存的可见记录</div>`;
  }

  const membersSignature = members => JSON.stringify(members.map(member => [String(member.id), String(member.name || '')]));

  function memberOptions(members) {
    return `<option value="all">全部成员</option>${members.map(member => `<option value="${escapeHtml(member.id)}"${String(member.id) === memberFilter ? ' selected' : ''}>${escapeHtml(member.name || '成员未识别')}</option>`).join('')}`;
  }

  function updateView(nextState) {
    const log = document.querySelector('#message-log');
    const scroll = log ? {
      top: log.scrollTop,
      pinned: log.scrollHeight - log.scrollTop - log.clientHeight < 56
    } : null;
    state = nextState;
    const group = state.group || {};
    const collector = state.collector || {};
    const members = state.members || [];
    const segments = state.segments || [];
    const stats = state.stats || {};
    const coverage = state.coverage || {};
    const status = collectorPresentation(collector);
    if (memberFilter !== 'all' && !members.some(member => String(member.id) === memberFilter)) memberFilter = 'all';

    const statusCard = document.querySelector('#collector-status');
    statusCard.dataset.tone = status.tone;
    document.querySelector('#collector-title').textContent = status.title;
    document.querySelector('#collector-detail').textContent = status.detail;
    document.querySelector('#last-captured').textContent = formatDate(collector.lastCapturedAt);
    document.querySelector('#last-attempt').textContent = formatDate(collector.lastAttemptAt);

    const enabled = Boolean(collector.enabled);
    const actionButton = document.querySelector('#collector-action');
    actionButton.className = `action-button${enabled ? ' pause' : ''}`;
    actionButton.dataset.action = enabled ? 'pause' : 'start';
    actionButton.disabled = actionPending;
    actionButton.textContent = actionPending ? '请稍候…' : enabled ? '暂停采集' : '开始采集';

    document.querySelector('#message-count').textContent = numberText(group.messageCount);
    document.querySelector('#member-count').textContent = numberText(group.memberCount);
    document.querySelector('#observation-count').textContent = numberText(stats.observations);
    document.querySelector('#duplicate-count').textContent = numberText(stats.duplicates);
    document.querySelector('#coverage-description').textContent = coverage.description || '只保存滚动时实际出现在屏幕上的页面，未显示的历史无法自动补齐。';
    document.querySelector('#segment-summary').textContent = `${targetGroup} · ${numberText(segments.length)} 个覆盖片段`;

    const select = document.querySelector('#member-filter');
    const signature = membersSignature(members);
    if (select.dataset.members !== signature) {
      select.innerHTML = memberOptions(members);
      select.dataset.members = signature;
    }
    select.value = memberFilter;

    log.innerHTML = segmentsView(segments);
    if (scroll) log.scrollTop = scroll.pinned ? log.scrollHeight : scroll.top;
  }

  function render(nextState) {
    if (document.querySelector('.capture-shell')) {
      updateView(nextState);
      return;
    }
    state = nextState;
    const group = state.group || {};
    const collector = state.collector || {};
    const members = state.members || [];
    const segments = state.segments || [];
    const stats = state.stats || {};
    const coverage = state.coverage || {};
    const status = collectorPresentation(collector);
    const knownMember = memberFilter === 'all' || members.some(member => String(member.id) === memberFilter);
    if (!knownMember) memberFilter = 'all';
    const enabled = Boolean(collector.enabled);
    const displayGroup = targetGroup;
    const coverageDescription = coverage.description || '只保存滚动时实际出现在屏幕上的页面，未显示的历史无法自动补齐。';

    root.innerHTML = `<div class="capture-shell">
      <aside class="side-nav">
        <div class="brand"><span class="brand-mark" aria-hidden="true">芽</span><div><strong>爱聊 <span>ai chat</span></strong><small>在每一次交流里，更懂一点</small></div></div>
        <div class="nav-heading">工具</div>
        <div class="nav-item active" aria-current="page"><span class="nav-icon">微</span><span class="nav-copy"><b>微信采集</b><span>${escapeHtml(targetGroup)}</span></span></div>
        <a class="nav-item back-link" href="/"><span class="nav-icon">聊</span><span class="nav-copy"><b>返回人物聊天</b><span>查看原有聊天与理解</span></span></a>
        <p class="side-note"><b>本机读取</b><br>只读目标群的可见页面，不发送消息，也不会自动调用模型。</p>
      </aside>

      <aside class="control-panel" aria-label="采集控制">
        <div class="control-title"><div class="eyebrow">固定采集目标</div><h1>${escapeHtml(displayGroup)}</h1><p class="group-summary">仅匹配这个微信群，不读取其他会话</p></div>
        <section id="collector-status" class="status-card" data-tone="${escapeHtml(status.tone)}">
          <div class="status-line"><span class="status-dot" aria-hidden="true"></span><span id="collector-title" class="status-title">${escapeHtml(status.title)}</span></div>
          <p id="collector-detail" class="status-detail">${escapeHtml(status.detail)}</p>
          <p class="status-time">最后成功：<span id="last-captured">${escapeHtml(formatDate(collector.lastCapturedAt))}</span><br>最后尝试：<span id="last-attempt">${escapeHtml(formatDate(collector.lastAttemptAt))}</span></p>
          <button id="collector-action" class="action-button${enabled ? ' pause' : ''}" data-action="${enabled ? 'pause' : 'start'}" ${actionPending ? 'disabled' : ''}>${actionPending ? '请稍候…' : enabled ? '暂停采集' : '开始采集'}</button>
        </section>
        <div class="metric-grid" aria-label="采集统计">
          <div class="metric"><b id="message-count">${escapeHtml(numberText(group.messageCount))}</b><span>消息</span></div>
          <div class="metric"><b id="member-count">${escapeHtml(numberText(group.memberCount))}</b><span>成员</span></div>
          <div class="metric"><b id="observation-count">${escapeHtml(numberText(stats.observations))}</b><span>页面观察</span></div>
          <div class="metric"><b id="duplicate-count">${escapeHtml(numberText(stats.duplicates))}</b><span>重复已跳过</span></div>
        </div>
        <section class="coverage-card"><h2><span class="coverage-mark">◌</span>覆盖说明</h2><p id="coverage-description">${escapeHtml(coverageDescription)}</p></section>
        <section class="filter-block"><h2>按成员查看</h2><label><select id="member-filter" aria-label="按成员筛选消息">${memberOptions(members)}</select></label></section>
      </aside>

      <main class="message-pane">
        <div class="mobile-nav"><a href="/">← 返回人物聊天</a><span>本机 OCR · 只读，不发送消息、不调用模型</span></div>
        <header class="message-header"><div><h2>已保存的微信记录</h2><p id="segment-summary">${escapeHtml(displayGroup)} · ${escapeHtml(numberText(segments.length))} 个覆盖片段</p></div><span class="source-badge">来源：本机截图 OCR</span></header>
        <div id="message-log" class="message-log" role="region" aria-label="已采集消息">${segmentsView(segments)}</div>
      </main>
    </div>`;

    document.querySelector('#member-filter').dataset.members = membersSignature(members);
  }

  function showToast(message) {
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.add('show');
    toastTimer = window.setTimeout(() => toast.classList.remove('show'), 5200);
  }

  async function api(path, method = 'GET') {
    const options = { method, signal: requestAbort.signal, credentials: 'same-origin' };
    if (method === 'POST') {
      options.headers = { 'Content-Type': 'application/json', 'X-Garden-Request': '1' };
      options.body = '{}';
    }
    const response = await fetch(path, options);
    let result;
    try {
      result = await response.json();
    } catch (_) {
      throw new Error('采集服务返回了无法读取的响应');
    }
    if (!response.ok) throw new Error(result.error || '请求未完成');
    return result;
  }

  function schedulePoll() {
    clearTimeout(pollTimer);
    if (requestAbort.signal.aborted || suspended) return;
    pollTimer = window.setTimeout(async () => {
      const generation = requestGeneration;
      try {
        const nextState = await api('/api/wechat/state');
        if (generation === requestGeneration && !actionPending && JSON.stringify(nextState) !== JSON.stringify(state)) render(nextState);
      } catch (error) {
        if (error.name !== 'AbortError') showToast('连接采集服务失败，正在继续重试。');
      } finally {
        if (generation === requestGeneration && !actionPending) schedulePoll();
      }
    }, 2000);
  }

  async function handleClick(event) {
    const button = event.target.closest('[data-action]');
    if (!button || button.disabled || actionPending) return;
    const action = button.dataset.action;
    if (action !== 'start' && action !== 'pause') return;
    clearTimeout(pollTimer);
    const generation = ++requestGeneration;
    actionPending = true;
    if (state) render(state);
    try {
      const nextState = await api(`/api/wechat/${action}`, 'POST');
      if (generation !== requestGeneration) return;
      render(nextState);
      showToast(action === 'pause' ? '采集已暂停。' : '采集已开始，请打开目标群并滚动。');
    } catch (error) {
      if (error.name !== 'AbortError') showToast(error.message);
    } finally {
      actionPending = false;
      if (state) render(state);
      schedulePoll();
    }
  }

  function handleChange(event) {
    if (event.target.id !== 'member-filter') return;
    memberFilter = event.target.value;
    if (state) render(state);
  }

  document.addEventListener('click', handleClick, { signal: requestAbort.signal });
  document.addEventListener('change', handleChange, { signal: requestAbort.signal });
  window.addEventListener('pagehide', event => {
    suspended = Boolean(event.persisted);
    clearTimeout(pollTimer);
    if (!event.persisted) {
      requestAbort.abort();
      clearTimeout(toastTimer);
    }
  });
  window.addEventListener('pageshow', event => {
    if (!event.persisted) return;
    suspended = false;
    schedulePoll();
  });

  api('/api/wechat/state')
    .then(render)
    .catch(error => {
      if (error.name !== 'AbortError') {
        root.innerHTML = `<main class="loading-shell"><div class="brand-mark" aria-hidden="true">芽</div><p>加载失败：${escapeHtml(error.message)}</p><a class="action-button" href="/">返回爱聊</a></main>`;
      }
    })
    .finally(schedulePoll);
})();
