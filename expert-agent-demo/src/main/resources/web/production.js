(function (global) {
  'use strict';
  const A=global.ProductionAPI;
  const statusText={active:'进行中',paused:'已暂停',cancelled:'已取消',completed:'已完成',queued:'排队中',running:'处理中',succeeded:'生成就绪',failed:'处理失败',pending:'待确认',needs_reason:'待补原因',changes_requested:'要求修改',current:'有效',stale:'待复核',open:'待澄清',resolved:'已解决'};
  const fields={name:'名称',title:'标题',description:'描述',summary:'摘要',outline:'内容结构',duty:'职责',responsibility:'职责',model:'模型',tools:'工具',capabilities:'特色能力',boundaries:'边界',when:'适用场景',keywords:'关键词',matchOptions:'匹配规则',match_options:'匹配规则',questions:'问答',examples:'示例',question:'问题',answer:'回答',explanation:'说明',workflow:'完整工作流程',settings:'生产配置',limitations:'限制与覆盖缺口',origin:'内容来源类型',sourceIds:'来源片段',steps:'方法步骤',limits:'边界',members:'成员清单',routes:'路由配置',target:'目标',specialistKey:'目标专业分工',key:'分工标识',methodTitles:'对应方法',agentId:'目标专家',routesTo:'路由目标',dependencies:'依赖',coverage:'资料覆盖',specialists:'专业分工',specialistPlan:'专业分工计划',learningUnits:'学习成果',qa:'问答',rules:'规则',flow:'完整工作流程',chapters:'对应篇章',overlapAnalysis:'分工重叠与遗漏分析',multiMatch:'命中多个专家',noMatch:'未命中时',metric:'相似度算法',threshold:'阈值',comparison:'阈值比较方式',embeddingProvider:'向量模型提供方',schemaVersion:'结构版本'};
  const kindText={book_summary:'整本书概要',learning_unit:'预学习成果',agent:'专家稿',keyword_rule:'关键词规则',qa_example:'示例问答',team_manifest:'团队完整流程'};
  const display=v=>typeof v==='string'?v:JSON.stringify(v,null,2);
  function el(tag,cls,text){const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;}
  function makeButton(text,cls,click,id){const node=el('button',cls,text);node.type='button';if(id)node.id=id;node.addEventListener('click',click);return node;}
  function valueNode(value){
    if(Array.isArray(value)){const list=el('ul','ep-items');for(const item of value){const li=el('li');li.append(valueNode(item));list.append(li);}if(!value.length)list.append(el('li','muted','无'));return list;}
    if(value&&typeof value==='object'){const list=el('dl','ep-object');for(const [key,item] of Object.entries(value)){list.append(el('dt','',fields[key]||key));const dd=el('dd');dd.append(valueNode(item));list.append(dd);}return list;}
    return el('pre','ep-value',value===null||value===undefined?'未提供':String(value));
  }
  function field(title,value){const section=el('section','ep-field');section.append(el('h4','',title),valueNode(value));return section;}
  function currentName(t){return t?.revision.body?.name||t?.revision.body?.title||kindText[t?.artifact.kind]||'当前成果';}
  function create({root,client,onRoute=()=>{},onNew=()=>{}}) {
    let state=null,buildId='',focusArtifact='',focusRevision='',epoch=0,timer=null,active=false,posting=false,loading=false,needsReload=false,pendingRequest=null,draftContext=null;
    let older=[],olderCursor='',messageKey='';
    const nodes={};
    function button(text,cls,click,id){const node=makeButton(text,cls,click,id);if(id)nodes[id]=node;return node;}
    const add=(id,tag='div',cls='')=>{const node=el(tag,cls);node.id=id;nodes[id]=node;return node;};
    root.classList.add('ep-root');
    const head=el('div','page-heading');const intro=el('div');intro.append(el('div','eyebrow','ADMINISTRATOR / EXPERT PRODUCTION'),el('h1','','专家生产'),el('p','','从资料理解到完整团队，逐稿审阅，逐步确认。'));head.append(intro,button('＋ 新建生产构建','primary',onNew));
    const banner=add('ep-banner','div','notice');banner.setAttribute('role','status');banner.hidden=true;
    const listPanel=el('section','ep-builds panel');const listHead=el('div','section-title');listHead.append(el('h2','','生产构建'),button('刷新列表','text-button',()=>loadList()));listPanel.append(listHead,add('ep-list'));
    const detail=add('ep-detail');detail.hidden=true;
    const toolbar=el('div','ep-toolbar');const titlebox=el('div');titlebox.append(add('ep-build-title','h2'),add('ep-position','p','muted'));
    const controls=el('div','row');for(const [type,text] of [['pause','暂停生成'],['resume','恢复'],['retry','重试失败任务'],['cancel','取消构建']]){controls.append(button(text,'secondary',()=>type==='cancel'?nodes['ep-cancel-dialog'].showModal():submit(text,{type}),'ep-'+type));}
    controls.append(button('刷新状态','text-button',()=>refreshSnapshot(),'ep-refresh'));toolbar.append(titlebox,controls);detail.append(toolbar,add('ep-jobs','div','ep-jobs'));
    const grid=el('div','ep-grid');const nav=add('ep-stages','nav','ep-stages');nav.setAttribute('aria-label','专家生产阶段');
    const work=el('div','ep-work');const art=el('section','ep-artifact');
    const artHead=el('div','ep-card-head');artHead.append(add('ep-artifact-title','h2'),add('ep-review-badge','span','tag'));art.append(artHead);
    const selectrow=el('div','ep-selectors');const alabel=el('label','','查看成果');const aselect=add('ep-artifact-select','select');aselect.setAttribute('aria-label','查看成果');alabel.append(aselect);const rlabel=el('label','','查看版本');const rselect=add('ep-revision-select','select');rselect.setAttribute('aria-label','查看版本');rlabel.append(rselect);selectrow.append(alabel,rlabel,button('回到当前待办','text-button',()=>selectFocus('',''),'ep-current'));
    art.append(selectrow,add('ep-revision-meta','div','ep-meta'),add('ep-history-warning','div','ep-warning'),add('ep-body'),add('ep-prompt'),add('ep-sources'));
    const history=el('details','ep-diff');history.append(el('summary','','修订差异与审核历史'),add('ep-diff'));art.append(history);
    const actions=el('div','ep-review-actions');actions.append(button('否决此版本','secondary',()=>{const t=target();return submit(`不通过${currentName(t)} v${t?.revision.revisionNo}`,{type:'reject'});},'ep-reject'),button('确认完整稿','primary',()=>{const t=target();return submit(`确认${currentName(t)} v${t?.revision.revisionNo} 的完整内容${t?.artifact.kind==='agent'?'及完整提示词':''}`,{type:'approve'});},'ep-approve'));
    art.append(actions,add('ep-scope','p','ep-meta'));
    const chat=el('section','ep-chat');const chatHead=el('div','ep-card-head');chatHead.append(el('h2','','与构建 Master 审阅'),el('span','ep-meta','原话与处理结果由服务端保存'));chat.append(chatHead,add('ep-clarifications'),button('加载更早记录','text-button',()=>loadOlder(),'ep-older'),add('ep-messages','div','ep-messages'));
    nodes['ep-messages'].setAttribute('role','log');nodes['ep-messages'].setAttribute('aria-label','生产审阅聊天');
    const form=add('ep-chat-form','form','ep-composer');const label=el('label','sr-only','管理员审阅意见');label.setAttribute('for','ep-input');const input=add('ep-input','textarea');input.rows=4;input.placeholder='提出疑问、说明修改原因，或明确确认所见版本。';const foot=el('div','ep-composer-foot');foot.append(el('span','ep-meta','自由聊天由构建 Master 处理；讨论不等于确认。'),button('发送意见 ↑','primary',()=>{},'ep-send'));nodes['ep-send'].type='submit';form.append(label,input,foot);chat.append(form);
    work.append(art,chat);grid.append(nav,work);detail.append(grid,add('ep-completion','div','ep-completion'));
    const resend=button('重发原请求（同一身份）','secondary',()=>resendPending(),'ep-resend');resend.hidden=true;
    root.replaceChildren(head,banner,resend,listPanel,detail);
    const sourceDialog=add('ep-source-dialog','dialog');const sourceHead=el('div','dialog-heading');sourceHead.append(el('h2','','原文核对'),button('关闭','text-button',()=>sourceDialog.close()));sourceDialog.append(sourceHead,add('ep-source-meta','p','muted'),add('ep-source-text','pre'),add('ep-source-link','a'));root.append(sourceDialog);
    const cancelDialog=add('ep-cancel-dialog','dialog');cancelDialog.append(el('h2','','取消本次构建？'),el('p','','取消后不能恢复此构建。已保存的资料、修订和审核记录将保留；正在进行的外部调用可能无法撤销。'));const cancelActions=el('div','row');cancelActions.append(button('继续审阅','secondary',()=>cancelDialog.close()),button('确认取消构建','primary',async()=>{cancelDialog.close();await submit('取消本次构建',{type:'cancel'});}));cancelDialog.append(cancelActions);root.append(cancelDialog);
    form.addEventListener('submit',e=>{e.preventDefault();return submit(input.value);});
    input.addEventListener('input',()=>{if(!input.value)draftContext=null;else if(!draftContext&&state)draftContext={state,target:target()};});
    aselect.addEventListener('change',()=>selectFocus(aselect.value,''));rselect.addEventListener('change',()=>selectFocus(target()?.artifact.id||'',rselect.value));
    function notify(message,error=false){banner.textContent=message;banner.className='notice'+(error?' error':'');banner.hidden=!message;}
    function target(){return A.target(state,focusArtifact,focusRevision);}
    function selectFocus(a,r){focusArtifact=a;focusRevision=r;onRoute(A.hashFor(buildId,a,r));render();}
    async function loadList(){try{const result=await client.list();nodes['ep-list'].replaceChildren();const list=result.builds||[];if(!list.length){nodes['ep-list'].append(el('p','muted','还没有生产构建。请选择已有资料并新建。'));return;}for(const b of list){const item=button('','ep-build',()=>enter({buildId:b.buildId}));item.append(el('strong','',b.name||'未命名构建'),el('span','',`${A.phases.find(p=>p[0]===b.phase)?.[1]||b.phase} · ${statusText[b.status]||b.status}`),el('small','',b.updatedAt?new Date(b.updatedAt).toLocaleString('zh-CN'):'已保存'));nodes['ep-list'].append(item);}}catch(error){nodes['ep-list'].replaceChildren(el('p','ep-error',error.message));notify(error.message,true);}}
    async function enter(route={}) {
      active=true;const token=++epoch;clearTimeout(timer);buildId=route.buildId||'';focusArtifact=route.artifactId||'';focusRevision=route.revisionId||'';state=null;older=[];olderCursor='';pendingRequest=null;draftContext=null;input.value='';messageKey='';needsReload=true;posting=false;loading=false;detail.hidden=!buildId;nodes['ep-resend'].hidden=true;
      onRoute(A.hashFor(buildId,focusArtifact,focusRevision));notify('');render();
      await loadList();if(token!==epoch||!active)return;
      if(buildId)await refreshSnapshot(token);schedule();
    }
    function leave(){active=false;epoch++;clearTimeout(timer);sourceDialog.close();cancelDialog.close();}
    function schedule(){clearTimeout(timer);if(active&&buildId)timer=setTimeout(poll,2500);}
    async function poll(){const token=epoch;if(active&&buildId&&!posting&&!loading){try{const events=await client.events(buildId,state?.lastEventSeq||'0');if(token!==epoch)return;if(!state||needsReload||events.events?.length)await refreshSnapshot(token);}catch(error){if(token===epoch){needsReload=true;notify('连接暂时中断：'+error.message+'。审核已暂停，恢复后刷新状态。',true);render();}}}schedule();}
    async function refreshSnapshot(token=epoch) {
      if(!buildId)return;loading=true;
      try {const next=await client.snapshot(buildId);if(token!==epoch||!active)return;state=next;needsReload=false;if(!older.length)olderCursor=next.olderMessagesBeforeSeq||'';render();}
      catch(error){if(token===epoch){needsReload=true;notify(error.message,true);render();}}
      finally{if(token===epoch){loading=false;render();}}
    }
    async function loadOlder(){if(!state?.hasOlderMessages&&!olderCursor)return;const token=epoch;try{const page=await client.snapshot(buildId,olderCursor);if(token!==epoch)return;older=[...(page.messages||[]),...older];olderCursor=page.hasOlderMessages?page.olderMessagesBeforeSeq:'';renderMessages();}catch(e){notify(e.message,true);}}
    function renderMessages(){if(!state)return;const records=[...older,...(state.messages||[])];if(state.waitingQuestionMessage&&!records.some(m=>m.id===state.waitingQuestionMessage.id))records.push(state.waitingQuestionMessage);const seen=new Set();const unique=records.filter(m=>!seen.has(m.id)&&(seen.add(m.id),true)).sort((a,b)=>{try{return BigInt(a.seq||0)<BigInt(b.seq||0)?-1:1;}catch{return 0;}});const key=JSON.stringify(unique);
      if(key!==messageKey){const log=nodes['ep-messages'];const nearBottom=log.scrollHeight-log.scrollTop-log.clientHeight<70;log.replaceChildren(...unique.map(m=>{const row=el('div','ep-message '+(m.role==='user'||m.role==='admin'?'ep-admin':''));row.append(el('div','ep-message-role',m.role==='assistant'?'构建 Master':m.role==='user'||m.role==='admin'?'管理员':'生产记录'),el('div','ep-bubble',m.content||''));const ps=m.processingStatus;const error=m.result?.error;const label=ps?({queued:'等待处理',running:'处理中',succeeded:'已处理',failed:'处理失败',cancelled:'已取消'}[ps]||ps):'';row.append(el('div','ep-meta',label+(m.result?.httpStatus>=400?' · '+(typeof error==='string'?error:error?.message||m.result?.message||`处理未完成 (${m.result.httpStatus})`):'')));return row;}));if(nearBottom||!messageKey)log.scrollTop=log.scrollHeight;messageKey=key;}
      nodes['ep-older'].hidden=!(older.length?olderCursor:state.hasOlderMessages);}
    function render() {
      nodes['ep-resend'].hidden=!pendingRequest;nodes['ep-resend'].disabled=posting;
      if(!state){nodes['ep-completion'].hidden=true;nodes['ep-scope'].textContent='';nodes['ep-revision-meta'].textContent='';aselect.replaceChildren();rselect.replaceChildren();nodes['ep-older'].hidden=true;nodes['ep-build-title'].textContent=buildId?'正在读取构建…':'';nodes['ep-position'].textContent='';for(const id of ['ep-jobs','ep-stages','ep-body','ep-prompt','ep-sources','ep-diff','ep-clarifications','ep-messages'])nodes[id].replaceChildren();nodes['ep-artifact-title'].textContent='等待服务端状态';nodes['ep-review-badge'].textContent='';nodes['ep-history-warning'].hidden=true;for(const type of ['approve','reject','pause','resume','cancel','retry','send'])nodes['ep-'+type].disabled=true;return;}
      const t=target(),a=t?.artifact,r=t?.revision,all=A.artifacts(state),current=state.currentArtifact;
      nodes['ep-build-title'].textContent=state.name||'生产构建';nodes['ep-position'].textContent=`${statusText[state.status]||state.status} · 当前阶段：${A.phases.find(p=>p[0]===state.phase)?.[1]||state.phase} · 查看：${t?currentName(t)+' v'+r.revisionNo:'等待稿件'}`;
      nodes['ep-stages'].replaceChildren(...A.phases.map(([phase,label],i)=>{const available=all.filter(x=>A.phaseFor(x)===phase);const b=button('','ep-stage'+(state.phase===phase?' active':''),()=>{if(available.length)selectFocus(available[0].id,'');});b.disabled=!available.length;b.append(el('span','ep-step',String(i+1).padStart(2,'0')),el('span','',label),el('small','',available.length?`${available.length} 份成果`:state.phase===phase?'处理中':'待生成'));if(state.phase===phase)b.setAttribute('aria-current','step');return b;}));
      const blocked=posting||needsReload||loading;
      nodes['ep-pause'].disabled=blocked||state.status!=='active';nodes['ep-resume'].disabled=blocked||state.status!=='paused';nodes['ep-cancel'].disabled=blocked||['cancelled','completed'].includes(state.status);nodes['ep-retry'].disabled=blocked||state.status!=='active'||!(state.jobs||[]).some(j=>j.status==='failed');nodes['ep-send'].disabled=posting||needsReload||['cancelled','completed'].includes(state.status);input.disabled=posting||['cancelled','completed'].includes(state.status);
      nodes['ep-jobs'].replaceChildren(...(state.jobs||[]).filter(j=>['queued','running','failed'].includes(j.status)).map(j=>{const box=el('div','ep-job'+(j.status==='failed'?' ep-error':''));const msg=typeof j.error==='string'?j.error:j.error?.message||'';box.append(el('strong','',`${j.kind||'任务'} · ${statusText[j.status]||j.status}`),el('span','',msg||j.phase||''));return box;}));
      aselect.replaceChildren(...all.map(x=>{const o=el('option','',`${kindText[x.kind]||x.kind} · ${x.currentRevision?.body?.name||x.currentRevision?.body?.title||x.logicalKey||x.id}`);o.value=x.id;o.selected=x.id===a?.id;return o;}));
      const revisions=a?[a.currentRevision,...(a.revisions||[])].filter(Boolean).filter((x,i,list)=>list.findIndex(y=>y.id===x.id)===i).sort((x,y)=>Number(y.revisionNo)-Number(x.revisionNo)):[];
      rselect.replaceChildren(...revisions.map(v=>{const o=el('option','',`v${v.revisionNo} · ${statusText[v.reviewStatus]||v.reviewStatus}${v.id===a.currentRevision?.id?' · 当前稿':''}`);o.value=v.id;o.selected=v.id===r?.id;return o;}));
      nodes['ep-artifact-title'].textContent=t?currentName(t):'等待稿件';nodes['ep-review-badge'].textContent=r?statusText[r.reviewStatus]||r.reviewStatus:'';
      nodes['ep-revision-meta'].textContent=r?`生成：${statusText[r.generationStatus]||r.generationStatus} · 依赖：${statusText[a.dependencyState]||a.dependencyState||'未说明'} · 内容摘要：${r.contentSha256||'尚未封存'}`:'服务端正在准备当前阶段成果，已有草稿将保存并恢复。';
      const historical=!!t&&(a.id!==(current?.id||state.currentArtifactId)||r.id!==a.currentRevision?.id);nodes['ep-history-warning'].hidden=!historical;nodes['ep-history-warning'].textContent='正在回看其他成果或历史稿。生产待办未改变，不能用旧确认批准新稿。';
      nodes['ep-body'].replaceChildren();nodes['ep-prompt'].replaceChildren();nodes['ep-sources'].replaceChildren();nodes['ep-diff'].replaceChildren();
      if(r){for(const [key,value] of Object.entries(r.body||{})){nodes['ep-body'].append(field(fields[key]||key,value));}
        if(a.kind==='agent'){nodes['ep-prompt'].append(field('完整业务提示词',r.systemPrompt||'尚未生成完整提示词，不能确认此专家。'));}
        else if(r.systemPrompt)nodes['ep-prompt'].append(field('完整业务提示词',r.systemPrompt));
        const sh=el('div','ep-sources');sh.append(el('h3','','资料来源'));if(!(r.sources||[]).length)sh.append(el('p','muted','此版本未返回直接来源片段；请核对上游依赖与覆盖说明。'));for(const source of r.sources||[]){const b=button(`${source.title||source.documentTitle||'来源片段'}${source.pageNo!==undefined?' · 第 '+source.pageNo+' 页':''}${source.chapterPath?' · '+source.chapterPath:''}`,'ep-source',()=>showSource(source));sh.append(b);if(source.qualityFlags?.length)sh.append(el('p','ep-warning',display(source.qualityFlags)));}nodes['ep-sources'].append(sh);
        const prev=revisions.filter(v=>Number(v.revisionNo)<Number(r.revisionNo)).sort((x,y)=>Number(y.revisionNo)-Number(x.revisionNo))[0];
        if(prev){nodes['ep-diff'].append(el('h4','',`v${prev.revisionNo} → v${r.revisionNo}`));const before={...(prev.body||{}),systemPrompt:prev.systemPrompt,sources:prev.sources},after={...(r.body||{}),systemPrompt:r.systemPrompt,sources:r.sources};for(const key of new Set([...Object.keys(before),...Object.keys(after)])){if(display(before[key])!==display(after[key])){const diff=el('section','ep-diff-field');diff.append(el('h4','',fields[key]||({systemPrompt:'完整提示词',sources:'来源片段'}[key])||key),el('pre','ep-before',display(before[key])||'无'),el('pre','ep-after',display(after[key])||'无'));nodes['ep-diff'].append(diff);}}}else nodes['ep-diff'].append(el('p','muted','这是首个修订，尚无前一版差异。'));
        if(r.changeReason)nodes['ep-diff'].append(field('修改原因',r.changeReason));
        const clarifications=(state.clarifications||[]).filter(c=>!c.artifactId||c.artifactId===a.id);
        for(const c of clarifications)nodes['ep-diff'].append(field('澄清记录 · '+(statusText[c.status]||c.status),(c.question||'')+(c.answer?'\n管理员回答：'+c.answer:'')));
        for(const review of (state.reviews||r.reviews||[]).filter(x=>!x.revisionId||x.revisionId===r.id))nodes['ep-diff'].append(field('审核记录',review));
      }
      nodes['ep-approve'].disabled=blocked||!A.canReview(state,t);nodes['ep-approve'].textContent=a?.kind==='team_manifest'?'确认完整团队流程':a?.kind==='agent'?'确认完整专家稿与提示词':'确认此版本完整内容';
      nodes['ep-reject'].disabled=blocked||!t||historical||state.status!=='active'||r.generationStatus!=='succeeded';
      nodes['ep-scope'].textContent=t?`确认范围：${A.scopeFor(a)}${a.kind==='agent'?' · 包含职责、模型、工具、特色能力、边界和完整提示词':''}${!A.presentation(state,t)?' · 尚无匹配的服务端展示记录，不能确认':''}${(state.clarifications||[]).some(c=>c.status==='open')?' · 先解决待澄清事项':''}`:'';
      nodes['ep-clarifications'].replaceChildren(...(state.clarifications||[]).filter(c=>c.status==='open').map(c=>{const box=el('div','ep-clarification');box.append(el('strong','','待澄清'),el('p','',c.question||c.content||''));return box;}));
      renderMessages();nodes['ep-completion'].hidden=state.status!=='completed';nodes['ep-completion'].textContent='管理员已确认本构建的完整生产流程。团队生产完成；这不代表用户侧部署或能力评测已经通过。';
    }
    async function showSource(source){const token=epoch;nodes['ep-source-meta'].textContent='正在读取本构建所选资料原文…';nodes['ep-source-text'].textContent='';nodes['ep-source-link'].hidden=true;sourceDialog.showModal();try{const value=await client.source(buildId,source);if(token!==epoch)return;nodes['ep-source-meta'].textContent=`资料版本：${value.documentVersionId||''} · ${value.chapterPath||''} · 页码：${value.pageNo??'未返回'}${source.startOffset!==undefined?' · 引用范围：'+source.startOffset+'–'+source.endOffset+'（Unicode 码点）':''}${value.qualityFlags?.length?' · '+display(value.qualityFlags):''}`;nodes['ep-source-text'].textContent=value.text||'该片段没有返回正文。';if(value.originalPageUrl){const url=new URL(value.originalPageUrl,location.origin);if(url.origin===location.origin&&url.pathname.startsWith('/api/expert-production/v1/builds/'+encodeURIComponent(buildId)+'/')){nodes['ep-source-link'].href=url.href;nodes['ep-source-link'].textContent='打开原文件页面 ↗';nodes['ep-source-link'].target='_blank';nodes['ep-source-link'].rel='noopener';nodes['ep-source-link'].hidden=false;}}}catch(error){nodes['ep-source-text'].textContent='原文读取失败：'+error.message;}}
    async function submit(content,action) {
      if(posting||!state||needsReload||!content.trim())return;
      const t=target();if(action?.type==='approve'&&!A.canReview(state,t)){notify('此稿尚不满足确认条件，请核对当前版本和待澄清事项。',true);return;}
      const context=!action&&draftContext?draftContext:{state,target:t};const request=A.messageRequest(context.state,context.target,content,crypto.randomUUID(),action);return sendRequest(request);
    }
    async function sendRequest(request){const token=epoch,id=buildId;posting=true;pendingRequest=null;render();
      try{await client.message(id,request);if(token!==epoch)return;if(input.value===request.content){input.value='';draftContext=null;}pendingRequest=null;notify('意见已保存，正在读取服务端处理结果。');await refreshSnapshot(token);}
      catch(error){if(token!==epoch)return;if(error.status===409){draftContext=null;await refreshSnapshot(token);notify(error.message+'。原话保留，请核对最新版本后重新发送；没有自动批准新稿。',true);}else{if(!error.status||error.status>=500)pendingRequest={buildId:id,request};notify(error.message+(pendingRequest?'。结果尚不确定，可按原身份重发；不会自动重复提交。':''),true);}}
      finally{if(token===epoch){posting=false;render();schedule();}}
    }
    async function resendPending(){if(pendingRequest&&!posting&&pendingRequest.buildId===buildId)return sendRequest(pendingRequest.request);}
    return {enter,leave};
  }
  global.ProductionUI={create};
})(globalThis);
