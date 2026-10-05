(function () {
  'use strict';
  const C = ReviewDemo, KEY = 'ailiao.design.expert-summary.prototype.v1';
  const $ = id => document.getElementById(id);
  const labels = {pending:'待确认',needs_reason:'待补充原因',changes_requested:'已否决 · 已出新稿',completed:'已确认'};
  let state, savedRaw=null, storageOK=true, lastRequest=null, restored=false;
  function notice(text, success=false) {$('notice').textContent=text;$('notice').hidden=false;$('notice').className=success?'success':'';}
  function storageError(error) {storageOK=false;$('save-status').textContent='保存不可用 · 操作已暂停';notice('无法可靠保存演示记录，未执行本次审核。可能是浏览器禁用本地存储或记录损坏。可重置演示，或在允许本地存储的浏览器打开本页。');console.warn('Demo storage unavailable:',error.name);}
  function read() {const raw=localStorage.getItem(KEY);if(raw===null)return {raw,state:C.seed()};const s=JSON.parse(raw);if(!C.isValid(s))throw new Error('Invalid demo state');return {raw,state:s};}
  try {const found=read();state=found.state;savedRaw=found.raw;restored=!!savedRaw;if(!savedRaw){savedRaw=JSON.stringify(state);localStorage.setItem(KEY,savedRaw);}} catch(error) {state=C.seed();storageError(error);}
  function commit(next, force=false) {
    if(!storageOK&&!force)return false;
    try {
      const current=localStorage.getItem(KEY);
      if(!force&&current!==savedRaw){const fresh=read();state=fresh.state;savedRaw=fresh.raw;render();notice('另一标签页已更新演示记录，本次没有提交。已载入最新版本，请重新查看后发送；输入框原话仍保留。');return false;}
      const raw=JSON.stringify(next);localStorage.setItem(KEY,raw);state=next;savedRaw=raw;storageOK=true;return true;
    } catch(error){storageError(error);render();return false;}
  }
  function element(tag,className,text) {const el=document.createElement(tag);if(className)el.className=className;if(text!==undefined)el.textContent=text;return el;}
  function viewVersion(n) {if(commit(C.focus(state,n))){render();notice(`正在查看概要 v${n}。生产进度保持不变。`,true);}}
  function render() {
    const r=state.revisions.find(x=>x.n===state.focus), current=state.revisions.find(x=>x.n===state.current), finished=state.phase==='specialists';
    $('save-status').textContent=storageOK?(restored?'已恢复本机演示记录':'已保存到本机 · 仅演示'):'保存不可用 · 操作已暂停';
    $('build-position').textContent=finished?'待审子专家入口（未实现）':'概要审阅';$('focus-position').textContent=`概要 v${r.n}${r.n===state.current?'':' · 历史'}`;
    $('summary-step').className=finished?'done':'active';$('summary-step').removeAttribute('aria-current');if(!finished)$('summary-step').setAttribute('aria-current','step');
    $('specialist-step').className=finished?'active':'';$('specialist-step').removeAttribute('aria-current');if(finished)$('specialist-step').setAttribute('aria-current','step');
    $('step-caption').textContent=finished?'已确认 · 保留审阅记录':'当前阶段 · 等待管理员';
    $('review-badge').textContent=labels[r.review_status];$('review-badge').className='badge'+(r.review_status==='completed'?' complete':'');
    $('version').replaceChildren(...state.revisions.slice().reverse().map(v=>{const o=element('option','',`v${v.n} · ${labels[v.review_status]}${v.n===state.current?' · 当前':''}`);o.value=v.n;o.selected=v.n===r.n;return o;}));
    $('version').disabled=!storageOK;$('current').disabled=!storageOK||r.n===state.current;
    $('revision-meta').textContent=`生成：演示就绪 / succeeded · 正文已封存 · 内容标识：${r.digest}（演示摘要，非安全哈希）`;
    $('history-warning').hidden=r.n===state.current;
    $('summary-body').replaceChildren(...r.body.split('\n\n').map(text=>element('p',text.startsWith('管理员自定义')?'admin-note':'',text)));
    $('history-count').textContent=`${state.revisions.length} 个版本`;
    const prev=state.revisions.find(x=>x.n===r.n-1);const diff=element('div','diff-block');
    if(!prev){diff.append(element('p','','v1 是初始演示稿，尚无历史差异。'));}
    else {
      diff.append(element('strong','',`v${prev.n} → v${r.n}`),element('p','','修改依据（管理员原话）：'+r.reason));
      const previous=prev.body.split('\n\n'),next=r.body.split('\n\n');
      const adds=next.filter(p=>!previous.includes(p)),removes=previous.filter(p=>!next.includes(p));
      for(const p of removes)diff.append(element('p','diff-remove','移除：'+p));
      for(const p of adds)diff.append(element('p','diff-add','新增：'+p));
      if(!adds.length&&!removes.length)diff.append(element('p','','正文相同，但作为新修订仍须重新确认。'));
      diff.append(element('p','micro','差异按段落展示；固定演示补充不代表模型已执行任意修改要求。'));
    }
    $('diff').replaceChildren(diff);
    $('history').replaceChildren(...state.revisions.slice().reverse().map(v=>{
      const item=element('div','history-item');const btn=element('button','',`查看 v${v.n}`);btn.type='button';btn.disabled=!storageOK;btn.addEventListener('click',()=>viewVersion(v.n));item.append(btn,element('span','',labels[v.review_status]));
      if(v.reason)item.append(element('p','','修订原因：'+v.reason));
      const decision=state.reviews.filter(x=>x.version===v.n);
      for(const d of decision)item.append(element('p','micro',`${d.decision==='approve'?'确认':'否决'} · 完整概要 · ${d.digest}\n管理员原话：${d.original}${d.reason?'\n原因：'+d.reason:''}`));
      const full=element('details','');full.append(element('summary','','展开封存全文'),element('div','history-body',v.body));item.append(full);return item;
    }));
    $('approve').textContent=`确认概要 v${r.n}`;$('approve').disabled=!storageOK||finished||r.n!==state.current||current.review_status==='needs_reason';$('reject').disabled=!storageOK||finished||r.n!==state.current;
    const pending=state.clarifications.filter(c=>c.status==='open');$('pending').hidden=!pending.length;$('pending').textContent=pending.map(c=>`待回答 · 概要 v${c.version}：${c.question}`).join('\n');
    $('messages').replaceChildren(...state.messages.map(m=>{
      const box=element('div','message '+m.role);box.append(element('div','who',m.role==='admin'?'管理员':'构建 Master · 演示'),element('div','bubble',m.text),element('div','meta',`关联概要 v${m.version}`));return box;
    }));
    $('messages').scrollTop=$('messages').scrollHeight;
    $('input').placeholder=`可以自由提问。明确确认请写：确认概要 v${state.current}`;$('send').disabled=!storageOK;$('input').disabled=!storageOK;
    $('next-stage').hidden=!finished;$('old-confirm').disabled=!storageOK||state.revisions.length<2;$('retry').disabled=!storageOK||!lastRequest;
    $('result-code').textContent=`phase=${state.phase} · build.status=${state.status} · generation_status=${current.generation_status} · review_status=${current.review_status} · 最近结果=${state.lastResult.code}`;
  }
  function dispatch(text, snapshot=C.snapshot(state), reuse=null) {
    if(!text.trim())return;
    const request=reuse||{id:globalThis.crypto?.randomUUID?crypto.randomUUID():`demo-${Date.now()}-${Math.random().toString(36).slice(2)}`,text,snapshot};
    const next=C.send(state,request);
    if(commit(next)){lastRequest=request;restored=false;$('input').value='';render();notice(state.lastResult.message||'已保留演示记录。',['APPROVED','REVISED','DISCUSSION','REPLAY'].includes(state.lastResult.code));}
  }
  $('chat-form').addEventListener('submit',e=>{e.preventDefault();dispatch($('input').value);});
  $('approve').addEventListener('click',()=>dispatch(`确认概要 v${state.focus}`));
  $('reject').addEventListener('click',()=>dispatch('不通过'));
  $('version').addEventListener('change',e=>viewVersion(Number(e.target.value)));
  $('current').addEventListener('click',()=>viewVersion(state.current));
  document.querySelectorAll('[data-example]').forEach(b=>b.addEventListener('click',()=>{$('input').value=b.dataset.example;$('input').focus();}));
  $('old-confirm').addEventListener('click',()=>{const r=state.revisions[state.revisions.length-2];const oldState=C.focus(state,r.n);dispatch(`确认概要 v${r.n}`,C.snapshot(oldState));});
  $('retry').addEventListener('click',()=>{if(lastRequest)dispatch(lastRequest.text,lastRequest.snapshot,lastRequest);});
  $('refresh').addEventListener('click',()=>location.reload());
  $('reset').addEventListener('click',()=>$('reset-dialog').showModal());
  $('cancel-reset').addEventListener('click',()=>$('reset-dialog').close());
  $('confirm-reset').addEventListener('click',()=>{if(commit(C.seed(),true)){restored=false;lastRequest=null;render();$('input').value='';notice('已重置此原型的演示记录。',true);$('reset-dialog').close();}});
  window.addEventListener('storage',e=>{if(e.key!==KEY)return;try{const found=read();state=found.state;savedRaw=found.raw;restored=true;render();notice('已载入另一标签页更新的演示状态。输入框原话保留，请核对版本后再发送。');}catch(error){storageError(error);render();}});
  render();
})();
