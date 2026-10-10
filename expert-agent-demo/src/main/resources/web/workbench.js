(function (global) {
 'use strict';
 const STORE='expertWorkbenchSelectionV1';
 const terminal=new Set(['paused','failed','cancelled','completed']);
 function element(tag,cls,text){const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=String(text);return e;}
 function create({root,client,onReview}){
  let documents=[],selected=new Map(),active=false,generation=0,timer=null,poll=null,buildId='',snapshot=null,lastRequested='',lastError='',uncertain=null;
  const heading=element('div','wb-heading');const picker=element('div','wb-picker');const status=element('div','wb-status');const cards=element('div','wb-cards');const viewer=element('div','wb-viewer');
  root.replaceChildren(heading,picker,status,cards,viewer);
  function clearTimers(){clearTimeout(timer);clearTimeout(poll);timer=poll=null;}
  function readSaved(){try{const value=JSON.parse(global.localStorage.getItem(STORE)||'[]');return Array.isArray(value)?value:[];}catch{return [];}}
  function save(){try{global.localStorage.setItem(STORE,JSON.stringify([...selected].map(([documentId,documentVersionId])=>({documentId,documentVersionId}))));}catch{}}
  function choices(){return [...selected].map(([documentId,documentVersionId])=>({documentId,documentVersionId})).sort((a,b)=>a.documentId.localeCompare(b.documentId));}
  function key(){return JSON.stringify(choices());}
  function schedule(){generation++;clearTimers();buildId='';snapshot=null;cards.replaceChildren();viewer.replaceChildren();lastError='';uncertain=null;const list=choices();if(!list.length){status.textContent='选择资料版本后，自动分析同一团队的候选分工。';return;}status.textContent='选择已更新；此前启动的草稿仍留在生产列表，这里只显示当前选择。';if(active)timer=setTimeout(()=>request(generation),1200);}
  function setDocuments(next){documents=Array.isArray(next)?next:[];const before=key();const valid=new Map();for(const d of documents){const v=selected.get(d.id);if(v&&d.versions?.some(x=>x.id===v))valid.set(d.id,v);}selected=valid;if(active)save();renderPicker();if(key()!==before&&active)schedule();}
  function renderPicker(){heading.replaceChildren(element('h2','', '先选择资料版本'),element('p','muted','同一资料选一个版本；模型会分析 1–6 位专业候选，兜底与主专家在审核后生成。'));picker.replaceChildren();for(const d of documents){const row=element('div','wb-choice'),check=element('input'),label=element('label','',d.title),version=element('select');check.type='checkbox';check.id='wb-check-'+d.id;check.checked=selected.has(d.id);version.id='wb-version-'+d.id;version.setAttribute('aria-label',d.title+'的资料版本');for(const v of [...(d.versions||[])].reverse()){const option=element('option','',`V${v.number}`);option.value=v.id;version.append(option);}version.value=selected.get(d.id)||d.versions?.at(-1)?.id||'';check.addEventListener('change',()=>{if(check.checked&&version.value)selected.set(d.id,version.value);else selected.delete(d.id);save();schedule();});version.addEventListener('change',()=>{if(selected.has(d.id)){selected.set(d.id,version.value);save();schedule();}});row.append(check,label,version);picker.append(row);}}
  function button(text,id,action){const b=element('button','secondary',text);b.type='button';b.id=id;b.addEventListener('click',action);return b;}
  function latestGenerationJob(s){const kind=s.phase==='prelearning'?'prelearn':'generate_summary';return (s.jobs||[]).filter(j=>s.phase==='prelearning'?j.kind==='prelearn':(j.kind==='generate_summary'||j.kind==='revise_summary')&&(!s.summary?.id||j.targetRevisionId===s.summary.id)).at(-1);}
  function errorMessage(error,fallback){return typeof error==='string'?error:typeof error?.message==='string'?error.message:fallback;}
  function candidateState(s,summary){
   if(s.status==='completed')return {status:'团队已完成',note:'这是已完成团队的分工记录。',action:'查看已完成团队'};
   if(s.status==='cancelled')return {status:'构建已取消',note:'此构建已取消，可查看历史记录。',action:'查看已取消构建'};
   if(s.status==='paused')return {status:'构建已暂停',note:summary?.reviewStatus==='completed'?'分工已审；可恢复原任务或查看记录。':'候选待审；可恢复原任务或查看记录。',action:'查看已暂停构建'};
   if(summary?.reviewStatus==='completed')return {status:'分工已审，后续审核进行中',note:'专业分工已获审核；进入同一构建继续后续审核。',action:'继续后续审核'};
   return {status:'候选分工待管理员审核',note:'候选仅供审核，未批准。完整提示词、分工调整和后续生成在管理员审阅中完成。',action:'审阅并调整这支团队'};
  }
  function render(){cards.replaceChildren();if(!snapshot)return;const s=snapshot,summary=s.summary,revision=summary?.body?.specialists;const done=summary?.generationStatus==='succeeded'&&Array.isArray(revision);const completed=(s.artifacts||[]).filter(a=>a.kind==='learning_unit'&&a.currentRevision?.generationStatus==='succeeded').length;const total=(s.artifacts||[]).filter(a=>a.kind==='learning_unit').length;const latestJob=latestGenerationJob(s),view=candidateState(s,summary);status.replaceChildren(element('p','',done?`${view.status} · 已完成 ${completed}/${total} 批资料预学习`:`${s.status==='paused'?'已暂停 · ':''}预学习 ${completed}/${total} 批；候选尚在生成或等待。`));
   if(latestJob?.status==='failed')status.append(element('p','wb-error',errorMessage(latestJob.error,'本次生成任务失败。')));
   if(lastError)status.append(element('p','wb-error',lastError));
   if(s.status==='paused')status.append(button('恢复原任务','wb-resume',()=>control('resume')));
   if(s.status==='active'&&latestJob?.status==='failed')status.append(button('重试失败任务','wb-retry',()=>control('retry')));
   if(uncertain)status.append(button('网络结果未确定，重发同一请求','wb-resend',()=>sendControl(uncertain)));
   if(lastError)status.append(button('重新读取','wb-refresh',()=>request(generation,true)));
   if(!done)return;
   cards.append(element('h3','',`同一团队 · ${revision.length} 位专业候选`));
   for(const candidate of revision){const card=element('article','wb-card');card.append(element('h4','',candidate.name||'待命名分工'),element('p','',candidate.responsibility||''));
    if(candidate.methodTitles?.length)card.append(element('p','',`方法：${candidate.methodTitles.join('、')}`));
    if(candidate.typicalQuestions?.length){const list=element('ul');for(const q of candidate.typicalQuestions)list.append(element('li','',q));card.append(list);}
    const sources=element('div','wb-sources');for(const id of candidate.sourceIds||[]){const meta=(s.proposal?.sources||[]).find(x=>x.chunkId===id);sources.append(button(meta?`${meta.title} · 第${meta.pageNo}页`:'查看原文依据','wb-source-'+id,()=>showSource(id)));}card.append(sources);cards.append(card);
   }
   cards.append(element('p','muted',view.note));
   cards.append(button(view.action,'wb-review',()=>onReview(buildId)));
  }
  async function showSource(chunkId){const token=generation,id=buildId;viewer.replaceChildren(element('p','', '正在读取原文…'));try{const value=await client.source(id,{chunkId});if(!active||generation!==token||buildId!==id)return;viewer.replaceChildren(element('h4','',`原文 · 第${value.pageNo}页`),element('pre','',value.text||''));if(value.originalPageUrl){const url=new URL(value.originalPageUrl,global.location.origin);const prefix='/api/expert-production/v1/builds/'+encodeURIComponent(id)+'/source-versions/';if(url.origin===global.location.origin&&url.pathname.startsWith(prefix)){const link=element('a','','打开 PDF 原页 ↗');link.href=url.href;link.target='_blank';link.rel='noopener';viewer.append(link);}}}catch(e){if(active&&token===generation)viewer.textContent='原文读取失败：'+e.message;}}
  async function sendControl(body){const token=generation,id=buildId;try{const response=await client.message(id,body);if(!active||token!==generation)return;uncertain=null;await fetchSnapshot(token,id);if(!active||token!==generation)return;lastError=response?.processingStatus==='failed'?errorMessage(response.result,'操作未被服务端接受。'):'';render();}catch(e){if(!active||token!==generation)return;lastError=e.message;if(!e.status||e.status>=500)uncertain=body;render();}}
  function control(type){if(!snapshot||!buildId)return;return sendControl({clientRequestId:global.crypto.randomUUID(),content:type==='resume'?'恢复原任务':'重试失败任务',expectedLockVersion:String(snapshot.lockVersion),action:{type,scope:'summary.full'}});}
  async function fetchSnapshot(token,id){try{const value=await client.snapshot(id);if(!active||token!==generation||id!==buildId)return;snapshot=value;lastError='';render();clearTimeout(poll);const latest=latestGenerationJob(value);if(!terminal.has(value.status)&&latest?.status!=='failed'&&value.summary?.generationStatus!=='succeeded')poll=setTimeout(()=>fetchSnapshot(token,id),2000);}catch(e){if(!active||token!==generation||id!==buildId)return;snapshot=null;lastError=e.message;status.replaceChildren(element('p','wb-error','读取失败：'+e.message),button('重试读取','wb-refresh',()=>fetchSnapshot(token,id)));cards.replaceChildren();}}
  async function request(token,force=false){const list=choices(),fingerprint=key();if(!active||token!==generation||!list.length)return;if(!force&&fingerprint===lastRequested&&buildId)return;status.textContent='已提交分析请求，正在读取真实进度…';try{const accepted=await client.propose(list);if(!active||token!==generation||fingerprint!==key())return;lastRequested=fingerprint;buildId=accepted.buildId;await fetchSnapshot(token,buildId);}catch(e){if(!active||token!==generation)return;buildId='';snapshot=null;cards.replaceChildren();status.replaceChildren(element('p','wb-error','分析请求失败：'+e.message),button('重试请求','wb-refresh',()=>request(token,true)));}}
  function enter(){active=true;generation++;const restored=readSaved();if(!selected.size){for(const s of restored){const d=documents.find(x=>x.id===s.documentId);if(d?.versions?.some(v=>v.id===s.documentVersionId))selected.set(s.documentId,s.documentVersionId);}}renderPicker();if(choices().length){lastRequested='';timer=setTimeout(()=>request(generation),1200);}else status.textContent='选择资料版本后，自动分析同一团队的候选分工。';}
  function leave(){active=false;generation++;clearTimers();}
  return {setDocuments,enter,leave};
 }
 global.WorkbenchUI={create};
})(globalThis);
