(function (global) {
  'use strict';
  const A=global.ProductionAPI;
  const statusText={active:'进行中',paused:'已暂停',cancelled:'已取消',completed:'已完成',queued:'排队中',running:'处理中',succeeded:'生成就绪',failed:'处理失败',pending:'待确认',needs_reason:'待补原因',changes_requested:'要求修改',current:'有效',stale:'待复核',open:'待澄清',resolved:'已解决'};
  const fields={name:'名称',title:'标题',description:'描述',summary:'摘要',outline:'内容结构',duty:'职责',responsibility:'职责',model:'模型',tools:'工具',capabilities:'特色能力',boundaries:'边界',when:'适用场景',keywords:'关键词',matchOptions:'匹配规则',match_options:'匹配规则',questions:'问答',examples:'示例',question:'问题',answer:'回答',explanation:'说明',workflow:'完整工作流程',settings:'生产配置',limitations:'限制与覆盖缺口',origin:'内容来源类型',sourceIds:'来源片段',steps:'方法步骤',limits:'边界',members:'成员清单',routes:'路由配置',target:'目标',specialistKey:'目标专业分工',key:'分工标识',methodTitles:'对应方法',agentId:'目标专家',routesTo:'路由目标',dependencies:'依赖',coverage:'资料覆盖',specialists:'专业分工',specialistPlan:'专业分工计划',learningUnits:'学习成果',qa:'问答',rules:'规则',flow:'完整工作流程',chapters:'对应篇章',overlapAnalysis:'分工重叠与遗漏分析',multiMatch:'命中多个专家',noMatch:'未命中时',metric:'相似度算法',threshold:'阈值',comparison:'阈值比较方式',embeddingProvider:'向量模型提供方',schemaVersion:'结构版本',typicalQuestions:'典型问题',methods:'学习到的方法',noMethodReason:'未提取方法的原因',purpose:'引用说明',decision:'审核结果',reason:'原因',createdAt:'记录时间',scope:'确认范围',role:'角色',agentRole:'专家角色',generationStatus:'生成状态',reviewStatus:'审核状态',dependencyState:'依据状态',revisionNo:'版本',pageNo:'页码',chapterPath:'章节',text:'原文',quote:'引文'};
  const kindText={book_summary:'整本书概要',learning_unit:'预学习成果',agent:'专家稿',keyword_rule:'关键词规则',qa_example:'示例问答',team_manifest:'团队完整流程'};
  const technicalFields=new Set(['key','logicalKey','schemaVersion','processedSourceIds','stepsOriginal','id','artifactId','revisionId','documentVersionId','chunkId','contentSha256','textSha256','createdBy','actorId','messageId','presentedMessageId','reviewedSha256','basedOnRevisionId','startOffset','endOffset']);
  const jobText={prelearn:'阅读资料',generate_summary:'生成概要',revise_summary:'修改概要',generate_artifact:'生成成果',revise_artifact:'修改成果',interpret_message:'理解审阅意见'};
  const scopeText={'artifact.full':'当前成果的完整内容','summary.full':'概要的完整内容','team.full':'团队的完整流程'};
  const fieldLabel=key=>fields[key]||'补充信息';
  const display=v=>typeof v==='string'?v:JSON.stringify(v,null,2);
  function el(tag,cls,text){const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;}
  function makeButton(text,cls,click,id){const node=el('button',cls,text);node.type='button';if(id)node.id=id;node.addEventListener('click',click);return node;}
  function valueNode(value,context={},key=''){
    const special=context.render?.(key,value);if(special)return special;
    if(Array.isArray(value)){const list=el('ul','ep-items');const items=value.filter(item=>item!==null&&item!==undefined&&(typeof item!=='string'||item.trim()));for(const item of items){const li=el('li');li.append(valueNode(item,context,key));list.append(li);}if(!items.length)list.append(el('li','muted','未提供'));return list;}
    if(value&&typeof value==='object'){const list=el('dl','ep-object');for(const [name,item] of Object.entries(value)){if(context.review&&technicalFields.has(name))continue;list.append(el('dt','',fieldLabel(name)));const dd=el('dd');dd.append(valueNode(item,context,name));list.append(dd);}return list;}
    const shown=context.format?context.format(key,value):value;
    return el('pre','ep-value',shown===null||shown===undefined?'未提供':typeof shown==='boolean'?(shown?'是':'否'):String(shown));
  }
  function field(title,value,context={},key=''){const section=el('section','ep-field');section.append(el('h4','',title),valueNode(value,context,key));return section;}
  function currentName(t){return t?.revision.body?.name||t?.revision.body?.title||kindText[t?.artifact.kind]||'当前成果';}
  function create({root,client,onRoute=()=>{},onNew=()=>{},refreshConnection}) {
    let state=null,buildId='',focusArtifact='',focusRevision='',epoch=0,timer=null,active=false,posting=false,loading=false,needsReload=false,pendingRequest=null,draftContext=null;
    let older=[],olderCursor='',messageKey='',sourceRequest=0,regenerateContext=null,connectionExpired=false,reconnecting=false;
    const nodes={};
    function button(text,cls,click,id){const node=makeButton(text,cls,click,id);if(id)nodes[id]=node;return node;}
    const add=(id,tag='div',cls='')=>{const node=el(tag,cls);node.id=id;nodes[id]=node;return node;};
    root.classList.add('ep-root');
    const head=el('div','page-heading');const intro=el('div');intro.append(el('div','eyebrow','管理员 · 专家团队搭建'),el('h1','','专家生产'),el('p','','从资料理解到完整团队，逐稿审阅，逐步确认。'));head.append(intro,button('＋ 新建生产构建','primary',onNew));
    const banner=add('ep-banner','div','notice');banner.setAttribute('role','status');banner.hidden=true;
    const listPanel=el('section','ep-builds panel');const listHead=el('div','section-title');listHead.append(el('h2','','生产构建'),button('刷新列表','text-button',()=>loadList()));listPanel.append(listHead,add('ep-list'));
    const detail=add('ep-detail');detail.hidden=true;
    const toolbar=el('div','ep-toolbar');const titlebox=el('div');titlebox.append(add('ep-build-title','h2'),add('ep-position','p','muted'));
    const controls=add('ep-controls','div','row');controls.append(button('下一步','primary',()=>{const t=target();return submit(`确认${currentName(t)} v${t?.revision.revisionNo} 的完整内容${t?.artifact.kind==='agent'?'及完整专家工作指令':''}`,{type:'approve'});},'ep-approve'));for(const [type,text] of [['pause','暂停生成'],['resume','恢复'],['retry','重试失败任务'],['cancel','取消']]){controls.append(button(text,'secondary',()=>type==='cancel'?nodes['ep-cancel-dialog'].showModal():submit(text,{type}),'ep-'+type));}
    controls.append(button('重新生成','secondary',()=>openRegenerate(),'ep-regenerate'));
    controls.append(button('刷新状态','text-button',()=>refreshSnapshot(),'ep-refresh'));toolbar.append(titlebox,controls,add('ep-next-hint','p','ep-meta'));detail.append(toolbar,add('ep-reading-status','p','notice'),add('ep-jobs','div','ep-jobs'));
    const jobHistory=add('ep-job-history','details','ep-diff');jobHistory.hidden=true;jobHistory.append(add('ep-job-history-title','summary'),el('p','muted','以下是此前尝试的失败记录，不代表当前任务状态。'),add('ep-job-history-items'));
    const grid=el('div','ep-grid');const nav=add('ep-stages','nav','ep-stages');nav.setAttribute('aria-label','专家生产阶段');
    const work=el('div','ep-work');const art=el('section','ep-artifact');
    const artHead=el('div','ep-card-head');artHead.append(add('ep-artifact-title','h2'),add('ep-review-badge','span','tag'));art.append(artHead);
    const selectrow=el('div','ep-selectors');const alabel=el('label','','查看成果');const aselect=add('ep-artifact-select','select');aselect.setAttribute('aria-label','查看成果');alabel.append(aselect);const rlabel=el('label','','查看版本');const rselect=add('ep-revision-select','select');rselect.setAttribute('aria-label','查看版本');rlabel.append(rselect);selectrow.append(alabel,rlabel,button('回到当前待办','text-button',()=>selectFocus('',''),'ep-current'));
    art.append(selectrow,add('ep-revision-meta','div','ep-meta'),add('ep-history-warning','div','ep-warning'),add('ep-body'),add('ep-prompt'),add('ep-sources'));
    const technical=add('ep-technical','details','ep-diff');technical.append(el('summary','','技术详情（内部标识与原始记录）'),jobHistory,add('ep-technical-body'));art.append(technical);
    const history=el('details','ep-diff');history.append(el('summary','','修订差异与审核历史'),add('ep-diff'));art.append(history);
    art.append(add('ep-scope','p','ep-meta'));
    const chat=el('section','ep-chat');const chatHead=el('div','ep-card-head');chatHead.append(el('h2','','与搭建助手 审阅'),el('span','ep-meta','原话与处理结果由服务端保存'));chat.append(chatHead,add('ep-clarifications'),button('加载更早记录','text-button',()=>loadOlder(),'ep-older'),add('ep-messages','div','ep-messages'));
    nodes['ep-messages'].setAttribute('role','log');nodes['ep-messages'].setAttribute('aria-label','生产审阅聊天');
    const form=add('ep-chat-form','form','ep-composer');const label=el('label','sr-only','管理员审阅意见');label.setAttribute('for','ep-input');const input=add('ep-input','textarea');input.rows=4;input.placeholder='提出疑问、说明修改原因，或明确确认所见版本。';const foot=el('div','ep-composer-foot');foot.append(el('span','ep-meta','自由聊天由搭建助手 处理；讨论不等于确认。'),button('发送意见 ↑','primary',()=>{},'ep-send'));nodes['ep-send'].type='submit';form.append(label,input,foot);chat.append(form);
    const sendStatus=add('ep-send-status','p','notice');sendStatus.setAttribute('role','status');sendStatus.hidden=true;form.append(sendStatus,button('更新连接（保留意见）','secondary',()=>reconnect(),'ep-reconnect'));nodes['ep-reconnect'].hidden=true;
    work.append(art,chat);grid.append(nav,work);detail.append(grid,add('ep-completion','div','ep-completion'));
    const resend=button('重发原请求（同一身份）','secondary',()=>resendPending(),'ep-resend');resend.hidden=true;
    form.append(resend);root.replaceChildren(head,banner,listPanel,detail);
    const sourceDialog=add('ep-source-dialog','dialog');const sourceHead=el('div','dialog-heading');sourceHead.append(el('h2','','原文核对'),button('关闭','text-button',()=>sourceDialog.close()));const sourceTech=el('details','ep-diff');sourceTech.append(el('summary','','来源技术详情'),add('ep-source-technical','pre','ep-value'));sourceDialog.append(sourceHead,add('ep-source-meta','p','muted'),add('ep-source-text','pre'),add('ep-source-link','a'),sourceTech);root.append(sourceDialog);
    const cancelDialog=add('ep-cancel-dialog','dialog');cancelDialog.append(el('h2','','取消本次构建？'),el('p','','取消后不能恢复此构建。已保存的资料、修订和审核记录将保留；正在进行的外部调用可能无法撤销。'));const cancelActions=el('div','row');cancelActions.append(button('继续审阅','secondary',()=>cancelDialog.close()),button('确认取消构建','primary',async()=>{cancelDialog.close();await submit('取消本次构建',{type:'cancel'});}));cancelDialog.append(cancelActions);root.append(cancelDialog);
    const regenerateDialog=add('ep-regenerate-dialog','dialog');regenerateDialog.append(el('h2','','重新生成当前稿'),add('ep-regenerate-target','p'),el('p','','仅重做当前稿，保留旧稿供对比，不重新阅读整本书。不填修改要求时，按原要求重新生成。'));
    const regenerateLabel=el('label','','修改要求（可选）');regenerateLabel.setAttribute('for','ep-regenerate-reason');regenerateDialog.append(regenerateLabel,add('ep-regenerate-reason','textarea'),add('ep-regenerate-error','p','ep-error'));
    const regenerateActions=el('div','row');regenerateActions.append(button('返回审阅','secondary',()=>regenerateDialog.close(),'ep-regenerate-back'),button('确认重新生成','primary',()=>regenerate(),'ep-regenerate-confirm'));regenerateDialog.append(regenerateActions);root.append(regenerateDialog);
    form.addEventListener('submit',e=>{e.preventDefault();return submit(input.value);});
    input.addEventListener('input',()=>{if(!input.value)draftContext=null;else if(!draftContext&&state)draftContext={state,target:target()};});
    aselect.addEventListener('change',()=>selectFocus(aselect.value,''));rselect.addEventListener('change',()=>selectFocus(target()?.artifact.id||'',rselect.value));
    function notify(message,error=false){for(const notice of [banner,sendStatus]){notice.textContent=message;notice.className='notice'+(error?' error':'');notice.hidden=!message;}}
    async function reconnect(){
      if(reconnecting||!connectionExpired||!refreshConnection)return;const token=epoch;reconnecting=true;notify('正在更新连接，输入的意见会保留。');render();
      try{await refreshConnection();if(token!==epoch||!active)return;await refreshSnapshot(token);if(token!==epoch||!active)return;if(needsReload)throw new Error('当前稿件暂时无法读取');connectionExpired=false;notify(pendingRequest?'连接已更新，请使用“重发原请求”核对原请求结果，避免重复提交。':'连接已更新，意见已保留。请核对当前稿后再次发送。');}
      catch(error){if(token===epoch)notify('更新连接失败：'+error.message+'。意见已保留，可重试。',true);}
      finally{if(token===epoch){reconnecting=false;render();}}
    }
    function target(){const selected=A.target(state,focusArtifact,focusRevision);return selected?.artifact.kind==='learning_unit'?null:selected;}
    function hasPendingWork(){return !!pendingRequest||(state?.jobs||[]).some(j=>['queued','running'].includes(j.status));}
    function canRegenerate(){const t=target();return !!t&&state.status==='active'&&!posting&&!connectionExpired&&!reconnecting&&!needsReload&&!loading&&!hasPendingWork()&&t.artifact.id===(state.currentArtifact?.id||state.currentArtifactId)&&t.revision.id===t.artifact.currentRevision?.id&&t.revision.generationStatus==='succeeded'&&t.revision.reviewStatus!=='completed'&&t.artifact.dependencyState!=='stale'&&!!A.presentation(state,t);}
    function openRegenerate(){if(!canRegenerate())return;const t=target();if(regenerateContext?.buildId!==buildId||regenerateContext?.target.artifact.id!==t.artifact.id||regenerateContext?.target.revision.id!==t.revision.id)nodes['ep-regenerate-reason'].value='';regenerateContext={state,target:t,buildId};nodes['ep-regenerate-target'].textContent=`当前稿：${currentName(regenerateContext.target)} · 第 ${regenerateContext.target.revision.revisionNo} 版`;nodes['ep-regenerate-error'].textContent='';regenerateDialog.showModal();}
    async function regenerate(){
      const context=regenerateContext,t=target();if(!context)return;
      if(context.buildId!==buildId||String(context.state.lockVersion)!==String(state?.lockVersion)||context.target.revision.id!==t?.revision.id){nodes['ep-regenerate-error'].textContent='版本已变化，请返回审阅并核对最新稿，再重新打开此操作。';return;}
      if(!canRegenerate()){nodes['ep-regenerate-error'].textContent='当前有任务处理中或稿件尚不可修改，请返回审阅核对状态。';return;}
      const changes=nodes['ep-regenerate-reason'].value.trim(),reason=changes||'管理员要求按原要求重新生成当前稿，未提供额外修改要求。';
      const request=A.messageRequest(context.state,context.target,`重新生成${currentName(context.target)}第 ${context.target.revision.revisionNo} 版。${changes?'修改要求：'+changes:'按原要求重新生成当前稿。'}`,crypto.randomUUID(),{type:'reject'});request.reason=reason;
      regenerateDialog.close();const accepted=await sendRequest(request);if(accepted&&regenerateContext===context){nodes['ep-regenerate-reason'].value='';regenerateContext=null;}
    }
    function normalizeFocus(){
      if(!focusArtifact||A.target(state,focusArtifact)?.artifact.kind!=='learning_unit')return;
      const summary=A.artifacts(state).find(a=>a.kind==='book_summary');focusArtifact=summary?.id||'';focusRevision='';onRoute(A.hashFor(buildId,focusArtifact));
    }
    function selectFocus(a,r){focusArtifact=a;focusRevision=r;onRoute(A.hashFor(buildId,a,r));render();}
    async function loadList(){try{const result=await client.list();nodes['ep-list'].replaceChildren();const list=result.builds||[];if(!list.length){nodes['ep-list'].append(el('p','muted','还没有生产构建。请选择已有资料并新建。'));return;}for(const b of list){const item=button('','ep-build',()=>enter({buildId:b.buildId}));item.append(el('strong','',b.name||'未命名构建'),el('span','',`${A.phases.find(p=>p[0]===b.phase)?.[1]||b.phase} · ${statusText[b.status]||b.status}`),el('small','',b.updatedAt?new Date(b.updatedAt).toLocaleString('zh-CN'):'已保存'));nodes['ep-list'].append(item);}}catch(error){nodes['ep-list'].replaceChildren(el('p','ep-error',error.message));notify(error.message,true);}}
    async function enter(route={}) {
      regenerateDialog.close();regenerateContext=null;reconnecting=false;nodes['ep-regenerate-reason'].value='';active=true;const token=++epoch;clearTimeout(timer);buildId=route.buildId||'';focusArtifact=route.artifactId||'';focusRevision=route.revisionId||'';state=null;older=[];olderCursor='';pendingRequest=null;draftContext=null;input.value='';messageKey='';needsReload=true;posting=false;loading=false;detail.hidden=!buildId;nodes['ep-resend'].hidden=true;
      onRoute(A.hashFor(buildId,focusArtifact,focusRevision));notify('');render();
      await loadList();if(token!==epoch||!active)return;
      if(buildId)await refreshSnapshot(token);schedule();
    }
    function leave(){active=false;epoch++;clearTimeout(timer);sourceDialog.close();cancelDialog.close();regenerateDialog.close();regenerateContext=null;nodes['ep-regenerate-reason'].value='';}
    function schedule(){clearTimeout(timer);if(active&&buildId)timer=setTimeout(poll,2500);}
    async function poll(){const token=epoch;if(active&&buildId&&!posting&&!loading){try{const events=await client.events(buildId,state?.lastEventSeq||'0');if(token!==epoch)return;if(!state||needsReload||events.events?.length)await refreshSnapshot(token);}catch(error){if(token===epoch){needsReload=true;notify('连接暂时中断：'+error.message+'。审核已暂停，恢复后刷新状态。',true);render();}}}schedule();}
    async function refreshSnapshot(token=epoch) {
      if(!buildId)return;loading=true;
      try {const next=await client.snapshot(buildId);if(token!==epoch||!active)return;state=next;normalizeFocus();needsReload=false;if(!older.length)olderCursor=next.olderMessagesBeforeSeq||'';render();}
      catch(error){if(token===epoch){needsReload=true;notify(error.message,true);render();}}
      finally{if(token===epoch){loading=false;render();}}
    }
    async function loadOlder(){if(!state?.hasOlderMessages&&!olderCursor)return;const token=epoch;try{const page=await client.snapshot(buildId,olderCursor);if(token!==epoch)return;older=[...(page.messages||[]),...older];olderCursor=page.hasOlderMessages?page.olderMessagesBeforeSeq:'';renderMessages();}catch(e){notify(e.message,true);}}
    function renderMessages(){if(!state)return;const records=[...older,...(state.messages||[])];if(state.waitingQuestionMessage&&!records.some(m=>m.id===state.waitingQuestionMessage.id))records.push(state.waitingQuestionMessage);const seen=new Set();const unique=records.filter(m=>!seen.has(m.id)&&(seen.add(m.id),true)).sort((a,b)=>{try{return BigInt(a.seq||0)<BigInt(b.seq||0)?-1:1;}catch{return 0;}});const key=JSON.stringify(unique);
      if(key!==messageKey){const log=nodes['ep-messages'];const nearBottom=log.scrollHeight-log.scrollTop-log.clientHeight<70;log.replaceChildren(...unique.map(m=>{const row=el('div','ep-message '+(m.role==='user'||m.role==='admin'?'ep-admin':''));row.append(el('div','ep-message-role',m.role==='assistant'?'搭建助手':m.role==='user'||m.role==='admin'?'管理员':'生产记录'),el('div','ep-bubble',m.content||''));const ps=m.processingStatus;const error=m.result?.error;const label=ps?({queued:'等待处理',running:'处理中',succeeded:'已处理',failed:'处理失败',cancelled:'已取消'}[ps]||ps):'';row.append(el('div','ep-meta',label+(m.result?.httpStatus>=400?' · '+(typeof error==='string'?error:error?.message||m.result?.message||`处理未完成 (${m.result.httpStatus})`):'')));return row;}));if(nearBottom||!messageKey)log.scrollTop=log.scrollHeight;messageKey=key;}
      nodes['ep-older'].hidden=!(older.length?olderCursor:state.hasOlderMessages);}
    function sourceMeta(source,revision=target()?.revision){
      const catalog=(state?.proposal?.sources||[]).find(x=>x.chunkId===source.chunkId)||{};
      const reference=(revision?.sources||[]).find(x=>x.chunkId===source.chunkId)||{};
      const meta={...catalog,...reference,...source};
      // Bare chunk references open the whole passage; explicit citations retain their exact offsets.
      if(source.startOffset===undefined){delete meta.startOffset;delete meta.endOffset;}
      return meta;
    }
    function sourceLabel(source,index){
      const title=source.title||source.documentTitle||'所选资料';
      const page=source.pageNo!==undefined&&source.pageNo!==null?`第 ${source.pageNo} 页`:'页码待核对';
      return `${title} · 来源 ${index+1} · ${page}${source.chapterPath?' · '+source.chapterPath:''} · 查看原文`;
    }
    function sourceButton(source,index,revision=target()?.revision){const meta=sourceMeta(source,revision);return button(sourceLabel(meta,index),'ep-source',()=>showSource(meta));}
    function reviewContext(revision){
      const plans=state?.summary?.body?.specialists||[];
      return {review:true,render(key,value){
        if(key==='outline'&&Array.isArray(value)){
          const sections=el('div','ep-outline');
          value.forEach((entry,index)=>{const section=el('section','ep-outline-item');if(entry&&typeof entry==='object'&&!Array.isArray(entry)){section.append(el('h4','',typeof entry.title==='string'&&entry.title.trim()?entry.title:`主题 ${index+1}（名称待核对）`));const {title,...content}=entry;section.append(valueNode(content,reviewContext(revision)));}else section.append(valueNode(entry));sections.append(section);});
          if(!value.length)sections.append(el('p','muted','当前稿尚未提供篇章或主题说明，请提出补充意见。'));return sections;
        }
        if(key==='members'&&Array.isArray(value)){
          const list=el('ul','ep-items');
          for(const member of value){const item=el('li');const artifact=A.artifacts(state).find(a=>a.id===member.artifactId);const saved=[artifact?.currentRevision,...(artifact?.revisions||[])].find(r=>r?.id===member.revisionId);item.append(el('strong','',saved?.body?.name||saved?.body?.title||(saved&&['keyword_rule','qa_example'].includes(member.kind)?kindText[member.kind]:'成员名称待核对')),el('p','muted',`${kindText[member.kind]||'团队成果'} · ${saved?'第 '+saved.revisionNo+' 版':'版本待核对（见技术详情）'}`));list.append(item);}if(!value.length)list.append(el('li','muted','未提供成员'));return list;
        }
        if(key!=='sourceIds')return null;
        const list=el('div','ep-source-list');const ids=(Array.isArray(value)?value:[]).filter(id=>typeof id==='string'&&id.trim());
        ids.forEach((id,index)=>list.append(sourceButton({chunkId:id},index,revision)));
        if(!ids.length)list.append(el('p','muted','未提供原文依据'));return list;
      },format(key,value){
        if(['specialistKey','agentId','routesTo','target'].includes(key)&&typeof value==='string'){
          const plan=plans.find(p=>p.key===value);const agent=A.artifacts(state).find(a=>a.id===value||a.logicalKey===value||a.logicalKey==='specialist:'+value);
          return plan?.name||agent?.currentRevision?.body?.name||'专家名称待核对（见技术详情）';
        }
        const labels={metric:{cosine:'余弦相似度'},comparison:{'>':'严格大于','>=':'大于或等于'},embeddingProvider:{'aliyun-bailian':'阿里云百炼'},multiMatch:{L1:'关键词匹配',L2:'问答匹配',L3:'主专家路由'},noMatch:{L1:'关键词匹配',L2:'问答匹配',L3:'主专家路由'},scope:scopeText,decision:{approve:'通过',reject:'不通过'},origin:{book:'资料原文',inferred:'基于资料推导',workbench:'资料工作台'},role:{specialist:'专业子专家',fallback:'兜底专家',router:'主专家'},generationStatus:statusText,reviewStatus:statusText,dependencyState:statusText,tools:{read_passage:'查阅原文',source:'查阅原文'}};
        return labels[key]?.[value]??value;
      }};
    }
    function renderJobs() {
      const jobs=state.jobs||[],prelearning=state.phase==='prelearning',revision=A.target(state)?.revision;
      // Retry follows the server's current generation target, never the viewed historical revision or chat jobs.
      const relevant=jobs.filter(j=>prelearning?j.kind==='prelearn':!!revision&&j.kind!=='interpret_message'&&j.targetRevisionId===revision.id);
      const latest=relevant.at(-1),pending=relevant.some(j=>['queued','running'].includes(j.status));
      const retryable=!pending&&['failed','cancelled'].includes(latest?.status)&&(prelearning||['failed','cancelled'].includes(revision?.generationStatus));
      const currentFailure=retryable?latest:null;
      function row(j,label,error=false){const box=el('div','ep-job'+(error?' ep-error':''));const msg=typeof j.error==='string'?j.error:j.error?.message||'';box.append(el('strong','',`${jobText[j.kind]||'生产任务'} · ${label}`),el('span','',msg||''));return box;}
      const live=jobs.filter(j=>['queued','running'].includes(j.status)||j===currentFailure);
      nodes['ep-jobs'].replaceChildren(...live.map(j=>row(j,statusText[j.status]||j.status,j.status==='failed')));
      if(!prelearning&&revision?.generationStatus==='succeeded'&&!pending)nodes['ep-jobs'].append(el('p','muted','当前生成已就绪，请继续审阅。'));
      const history=jobs.filter(j=>j.status==='failed'&&j!==currentFailure);
      jobHistory.hidden=!history.length;nodes['ep-job-history-title'].textContent=`历史失败记录（${history.length}）`;
      nodes['ep-job-history-items'].replaceChildren(...history.map(j=>{
        const recovered=j.kind!=='interpret_message'&&jobs.slice(jobs.indexOf(j)+1).some(next=>next.status==='succeeded'&&(j.kind==='prelearn'?next.kind==='prelearn':next.kind!=='interpret_message'&&next.kind!=='prelearn'&&!!j.targetRevisionId&&next.targetRevisionId===j.targetRevisionId));
        return row(j,recovered?'此前失败，后续尝试已成功':'历史处理失败');
      }));
      return retryable;
    }
    function render() {
      nodes['ep-resend'].hidden=!pendingRequest;nodes['ep-resend'].disabled=posting||reconnecting||connectionExpired;
      nodes['ep-reconnect'].hidden=!connectionExpired||!refreshConnection;nodes['ep-reconnect'].disabled=reconnecting;nodes['ep-send'].textContent=posting?'正在发送…':'发送意见 ↑';
      if(!state){nodes['ep-next-hint'].hidden=true;nodes['ep-approve'].hidden=true;nodes['ep-regenerate'].hidden=true;nodes['ep-reading-status'].hidden=true;jobHistory.hidden=true;nodes['ep-job-history-items'].replaceChildren();nodes['ep-completion'].hidden=true;nodes['ep-scope'].textContent='';nodes['ep-revision-meta'].textContent='';aselect.replaceChildren();rselect.replaceChildren();nodes['ep-older'].hidden=true;nodes['ep-build-title'].textContent=buildId?'正在读取构建…':'';nodes['ep-position'].textContent='';for(const id of ['ep-technical-body','ep-jobs','ep-stages','ep-body','ep-prompt','ep-sources','ep-diff','ep-clarifications','ep-messages'])nodes[id].replaceChildren();nodes['ep-artifact-title'].textContent='等待服务端状态';nodes['ep-review-badge'].textContent='';nodes['ep-history-warning'].hidden=true;for(const type of ['approve','regenerate','pause','resume','cancel','retry','send'])nodes['ep-'+type].disabled=true;return;}
      const t=target(),a=t?.artifact,r=t?.revision,all=A.artifacts(state).filter(x=>x.kind!=='learning_unit'),current=state.currentArtifact;
      nodes['ep-build-title'].textContent=state.name||'生产构建';nodes['ep-position'].textContent=`${statusText[state.status]||state.status} · 当前阶段：${A.phases.find(p=>p[0]===state.phase)?.[1]||state.phase} · 查看：${t?currentName(t)+' v'+r.revisionNo:'等待稿件'}`;
      nodes['ep-reading-status'].hidden=state.phase!=='prelearning';nodes['ep-reading-status'].textContent='阅读资料尚未完成。完成后将在这里展示全书概要、篇章与主题理解及专家分工；如任务失败，请查看原因后重试。';
      nodes['ep-stages'].replaceChildren(...A.phases.filter(([phase])=>phase!=='prelearning').map(([phase,label],i)=>{const available=all.filter(x=>A.phaseFor(x)===phase);const b=button('','ep-stage'+(state.phase===phase?' active':''),()=>{if(available.length)selectFocus(available[0].id,'');});b.disabled=!available.length;b.append(el('span','ep-step',String(i+1).padStart(2,'0')),el('span','',label),el('small','',available.length?`${available.length} 份成果`:state.phase===phase?'处理中':'待生成'));if(state.phase===phase)b.setAttribute('aria-current','step');return b;}));
      const blocked=posting||needsReload||loading||connectionExpired||reconnecting,retryable=renderJobs(),reviewReady=state.status==='active'&&state.phase!=='prelearning'&&A.target(state)?.artifact.kind!=='learning_unit'&&A.target(state)?.revision.generationStatus==='succeeded';
      for(const id of ['pause','resume','retry','refresh'])nodes['ep-'+id].hidden=reviewReady;
      nodes['ep-approve'].hidden=!reviewReady;nodes['ep-regenerate'].hidden=!reviewReady;nodes['ep-regenerate'].disabled=!canRegenerate();
      nodes['ep-next-hint'].hidden=!reviewReady;nodes['ep-next-hint'].textContent=a?.kind==='team_manifest'?'最后一步：点击“下一步”即确认完整团队流程并完成搭建。':'点击“下一步”即确认当前完整稿，并进入下一项审阅。';
      nodes['ep-pause'].disabled=blocked||state.status!=='active';nodes['ep-resume'].disabled=blocked||state.status!=='paused';nodes['ep-cancel'].disabled=blocked||['cancelled','completed'].includes(state.status);nodes['ep-retry'].disabled=blocked||state.status!=='active'||!retryable;nodes['ep-send'].disabled=blocked||!!pendingRequest||['cancelled','completed'].includes(state.status);input.disabled=posting||['cancelled','completed'].includes(state.status);
      selectrow.hidden=!all.length;
      aselect.replaceChildren(...all.map(x=>{const o=el('option','',`${kindText[x.kind]||x.kind} · ${x.currentRevision?.body?.name||x.currentRevision?.body?.title||kindText[x.kind]||'待命名成果'}`);o.value=x.id;o.selected=x.id===a?.id;return o;}));
      const revisions=a?[a.currentRevision,...(a.revisions||[])].filter(Boolean).filter((x,i,list)=>list.findIndex(y=>y.id===x.id)===i).sort((x,y)=>Number(y.revisionNo)-Number(x.revisionNo)):[];
      rselect.replaceChildren(...revisions.map(v=>{const o=el('option','',`v${v.revisionNo} · ${statusText[v.reviewStatus]||v.reviewStatus}${v.id===a.currentRevision?.id?' · 当前稿':''}`);o.value=v.id;o.selected=v.id===r?.id;return o;}));
      nodes['ep-artifact-title'].textContent=t?currentName(t):'等待全书概要';nodes['ep-review-badge'].textContent=r?statusText[r.reviewStatus]||r.reviewStatus:'';
      nodes['ep-revision-meta'].textContent=r?`第 ${r.revisionNo} 版 · ${statusText[r.reviewStatus]||'待核对'} · 生成：${statusText[r.generationStatus]||'待核对'} · 依据：${statusText[a.dependencyState]||'未说明'}`:'服务端正在准备当前阶段成果，已有草稿将保存并恢复。';
      const historical=!!t&&(a.id!==(current?.id||state.currentArtifactId)||r.id!==a.currentRevision?.id);nodes['ep-history-warning'].hidden=!historical;nodes['ep-history-warning'].textContent='正在回看其他成果或历史稿。生产待办未改变，不能用旧确认批准新稿。';
      nodes['ep-body'].replaceChildren();nodes['ep-prompt'].replaceChildren();nodes['ep-sources'].replaceChildren();nodes['ep-diff'].replaceChildren();
      nodes['ep-technical-body'].replaceChildren();
      if(r){const context=reviewContext(r);nodes['ep-technical-body'].append(field('原始记录',display({artifactId:a.id,...r})));const body=r.body||{};
        const summaryLabels={summary:'全书概要',outline:'篇章与主题理解',specialists:'专家分工与依据',limitations:'待核对事项与理解边界'};
        const entries=a.kind==='book_summary'?[...Object.keys(summaryLabels).map(key=>[key,body[key]??(key==='summary'?'尚未提供全书概要':[])]),...Object.entries(body).filter(([key])=>key!=='title'&&!(key in summaryLabels))]:Object.entries(body);
        for(const [key,value] of entries){if(!technicalFields.has(key))nodes['ep-body'].append(field(a.kind==='book_summary'?(summaryLabels[key]||fieldLabel(key)):fieldLabel(key),value,context,key));}
        if(a.kind==='agent'){nodes['ep-prompt'].append(el('p','ep-prompt-note','这是当前专家实际使用的完整指令，请确认是否符合职责、边界和资料要求。'),field('专家工作指令（提示词）',r.systemPrompt||'尚未生成完整工作指令，不能确认此专家。'));}
        const sh=el('div','ep-sources');sh.append(el('h3','','资料来源'));if(!(r.sources||[]).length)sh.append(el('p','muted','此版本未返回直接来源片段；请核对上游依赖与覆盖说明。'));for(const [index,source] of (r.sources||[]).entries()){sh.append(sourceButton(source,index,r));if(source.purpose)sh.append(el('p','muted',source.purpose));if(source.qualityFlags?.length)sh.append(el('p','ep-warning','该原文存在质量提示，请结合原文件核对。'));}nodes['ep-sources'].append(sh);
        const prev=revisions.filter(v=>Number(v.revisionNo)<Number(r.revisionNo)).sort((x,y)=>Number(y.revisionNo)-Number(x.revisionNo))[0];
        if(prev){nodes['ep-diff'].append(el('h4','',`v${prev.revisionNo} → v${r.revisionNo}`));const before={...(prev.body||{}),systemPrompt:prev.systemPrompt,sources:prev.sources},after={...(r.body||{}),systemPrompt:r.systemPrompt,sources:r.sources};for(const key of new Set([...Object.keys(before),...Object.keys(after)])){if(display(before[key])!==display(after[key])){const diff=el('section','ep-diff-field');if(technicalFields.has(key)||(key==='systemPrompt'&&a.kind!=='agent'))continue;diff.append(el('h4','',({systemPrompt:'专家工作指令（提示词）',sources:'引用依据'}[key])||fieldLabel(key)));for(const [value,version,cls] of [[before[key],prev,'ep-before'],[after[key],r,'ep-after']]){const box=el('div',cls);if(key==='sources'){for(const [i,source] of (value||[]).entries()){box.append(sourceButton(source,i,version));if(source.purpose)box.append(field('引用说明',source.purpose));if(source.quote)box.append(field('引文',source.quote));}if(!value?.length)box.append(el('p','muted','无引用依据'));if(display(prev.sources)!==display(r.sources))box.append(el('p','muted','引用依据已修改，请分别打开原文核对引用范围。'));}else box.append(valueNode(value,reviewContext(version),key));diff.append(box);}nodes['ep-diff'].append(diff);}}}else nodes['ep-diff'].append(el('p','muted','这是首个修订，尚无前一版差异。'));
        if(r.changeReason)nodes['ep-diff'].append(field('修改原因',r.changeReason));
        const clarifications=(state.clarifications||[]).filter(c=>!c.artifactId||c.artifactId===a.id);
        for(const c of clarifications)nodes['ep-diff'].append(field('澄清记录 · '+(statusText[c.status]||c.status),(c.question||'')+(c.answer?'\n管理员回答：'+c.answer:'')));
        for(const review of (state.reviews||r.reviews||[]).filter(x=>!x.revisionId||x.revisionId===r.id))nodes['ep-diff'].append(field('审核记录',review,context));
      }
      nodes['ep-approve'].disabled=blocked||hasPendingWork()||!A.canReview(state,t);
      nodes['ep-scope'].textContent=t?`确认范围：${scopeText[A.scopeFor(a)]||'当前完整内容'}${a.kind==='agent'?' · 包含职责、模型、工具、特色能力、边界和完整提示词':''}${!A.presentation(state,t)?' · 尚无匹配的服务端展示记录，不能确认':''}${(state.clarifications||[]).some(c=>c.status==='open')?' · 先解决待澄清事项':''}`:'';
      nodes['ep-clarifications'].replaceChildren(...(state.clarifications||[]).filter(c=>c.status==='open').map(c=>{const box=el('div','ep-clarification');box.append(el('strong','','待澄清'),el('p','',c.question||c.content||''));return box;}));
      renderMessages();nodes['ep-completion'].hidden=state.status!=='completed';nodes['ep-completion'].textContent='管理员已确认本构建的完整生产流程。团队生产完成；这不代表用户侧部署或能力评测已经通过。';
    }
    async function showSource(source){
      const token=epoch,request=++sourceRequest;nodes['ep-source-meta'].textContent='正在读取所选资料原文…';nodes['ep-source-text'].textContent='';nodes['ep-source-technical'].textContent='';nodes['ep-source-link'].hidden=true;sourceTech.open=false;sourceDialog.showModal();
      try{const value=await client.source(buildId,source);if(token!==epoch||request!==sourceRequest)return;
        const page=value.pageNo??source.pageNo;nodes['ep-source-meta'].textContent=`${source.title||source.documentTitle||'所选资料'} · ${page===undefined||page===null?'页码未提供':'第 '+page+' 页'}${value.chapterPath?' · '+value.chapterPath:''}${value.qualityFlags?.length?' · 原文存在质量提示，请结合原文件核对。':''}`;
        nodes['ep-source-technical'].textContent=display({...source,...value,text:undefined});nodes['ep-source-text'].textContent=value.text||'该片段没有返回正文。';
        if(value.originalPageUrl){const url=new URL(value.originalPageUrl,location.origin);if(url.origin===location.origin&&url.pathname.startsWith('/api/expert-production/v1/builds/'+encodeURIComponent(buildId)+'/')){nodes['ep-source-link'].href=url.href;nodes['ep-source-link'].textContent='打开原文件页面 ↗';nodes['ep-source-link'].target='_blank';nodes['ep-source-link'].rel='noopener';nodes['ep-source-link'].hidden=false;}}
      }catch(error){if(token===epoch&&request===sourceRequest)nodes['ep-source-text'].textContent='原文读取失败：'+error.message;}
    }
    async function submit(content,action) {
      if(posting||pendingRequest||connectionExpired||reconnecting||!state||needsReload||!content.trim())return;
      const t=target();if(action?.type==='approve'&&(hasPendingWork()||!A.canReview(state,t))){notify('此稿尚不满足确认条件，请核对当前版本和待澄清事项。',true);return;}
      const context=!action&&draftContext?draftContext:{state,target:t};const request=A.messageRequest(context.state,context.target,content,crypto.randomUUID(),action);return sendRequest(request);
    }
    async function sendRequest(request){const token=epoch,id=buildId,uncertain=pendingRequest?.request===request?pendingRequest:null;posting=true;pendingRequest=null;notify('正在发送意见…');render();
      try{const response=await client.message(id,request);if(token!==epoch)return;if(input.value===request.content){input.value='';draftContext=null;}pendingRequest=null;notify('意见已保存，正在读取服务端处理结果。');await refreshSnapshot(token);return response?.processingStatus!=='failed'&&!(response?.httpStatus>=400)&&!(response?.result?.httpStatus>=400);}
      catch(error){if(token!==epoch)return;if(error.status===403){connectionExpired=true;pendingRequest=uncertain;notify(error.message+(uncertain?'。此前请求结果仍不确定，原请求已保留，请更新连接后按原身份重发。':refreshConnection?'。意见尚未提交，原话已保留，请先更新连接。':'。意见尚未提交，请先复制保留输入内容。'),true);}else if(error.status===409){draftContext=null;await refreshSnapshot(token);notify(error.message+'。原话保留，请核对最新版本后重新发送；没有自动批准新稿。',true);}else{if(!error.status||error.status>=500)pendingRequest={buildId:id,request};notify(error.message+(pendingRequest?'。结果尚不确定，可按原身份重发；不会自动重复提交。':''),true);}}
      finally{if(token===epoch){posting=false;render();schedule();}}
    }
    async function resendPending(){if(pendingRequest&&!posting&&pendingRequest.buildId===buildId)return sendRequest(pendingRequest.request);}
    return {enter,leave};
  }
  global.ProductionUI={create};
})(globalThis);
