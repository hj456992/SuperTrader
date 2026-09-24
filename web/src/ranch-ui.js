import {jobActive, jobForPage} from './ranch-job.js';
import {sourceSelection, sourceFields, conversationOptions, platformNames, sourcePlatform, createSourceRequestGate, sourceReady} from './ranch-sources.js';
import {esc, renderRanch} from './ranch-view.js';
export const name='ranch-ui';
export function requestPayload(body,currentRevision){return {...body,revision:body.revision??currentRevision};}
export function mutationOwnsContext(origin,current){return ['dialogEpoch','page','selected','tab'].every(key=>origin[key]===current[key]);}
export function apply(ctx){ctx.effect(()=>mount(),'ranch-ui mount');}
function mount(){
 const app=document.querySelector('#app'),modal=document.querySelector('#modal'),noticeEl=document.querySelector('#notice');
 const abort=new AbortController();
 let dialogEpoch=0,stateRequest=0,cancelPending=false;
 let data={revision:0,people:[],self:{name:'我',materials:[]},library:[],job:{status:'idle'}},ui={page:'ranch',tab:'profile',selected:null},busy=false,timer,noticeTimer,sources=[],disposed=false;
 const drafts=new Map();
 const sourceGate=createSourceRequestGate();
 let sourceStatus={loading:'',error:'',detail:''},sourceOrigin=null;
 const context=()=>({dialogEpoch,...ui});
 const target=()=>ui.page==='self'?'self':ui.selected;
 const person=()=>ui.page==='self'?data.self:data.people.find(p=>p.id===ui.selected);
 async function api(action,body){const r=await fetch('/api/ranch-'+action,{method:body===undefined?'GET':'POST',headers:body===undefined?{}:{'Content-Type':'application/json','X-Garden-Request':'1'},body:body===undefined?undefined:JSON.stringify(requestPayload(body,data.revision)),signal:abort.signal});let result;try{result=await r.json();}catch{throw new Error('服务返回了无法读取的内容，请稍后重试。');}if(!r.ok)throw new Error(r.status===409?'资料已在其他页面或操作中更新。你的输入仍保留，请先复制需要保留的修改，关闭并重新打开表单，核对最新内容后再保存。':result.error||result.message||'操作失败');return result;}
 function notice(text){if(!noticeEl)return;noticeEl.textContent=text;noticeEl.classList.add('show');clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>noticeEl.classList.remove('show'),6000);}
 function render(){if(disposed)return;const active=document.activeElement;const focusId=active?.id;const start=active?.selectionStart,end=active?.selectionEnd;app.innerHTML=renderRanch(data,{...ui,stopping:cancelPending,draft:drafts.get(target())||''});if(focusId&&active?.closest('#app')){const restored=document.getElementById(focusId);restored?.focus();if(restored?.setSelectionRange&&start!=null)restored.setSelectionRange(start,end);}}
 async function refresh(announce=false){
  const request=++stateRequest,next=await api('state');
  if(disposed||request!==stateRequest)return;
  const previous=data.job,changed=next.revision!==data.revision||JSON.stringify(next.job)!==JSON.stringify(previous);
  data=next;if(changed||!announce)render();
  if(announce&&jobActive(previous)&&previous.id===next.job?.id&&previous.targetId===next.job?.targetId&&next.job?.status==='done'&&jobForPage(next.job,ui))notice('已保存新的分析结果。');
  poll();
 }
 function poll(){if(disposed)return;clearTimeout(timer);timer=setTimeout(async()=>{try{await refresh(true);}catch(err){if(err.name!=='AbortError'&&!disposed){notice('暂时无法连接服务，正在重试。');poll();}}},jobActive(data.job)?1300:5000);}
 function open(title,content){dialogEpoch++;modal.className='ranch-dialog';modal.innerHTML=`<button class="dialog-close" data-action="close" aria-label="关闭">×</button><div class="eyebrow">THE LITTLE RANCH</div><h2>${title}</h2><p class="dialog-error" role="alert"></p>${content}`;modal.querySelectorAll('form').forEach(form=>{form.dataset.revision=String(data.revision);form.dataset.targetId=target()||'';});if(!modal.open)modal.showModal();modal.querySelector('input,textarea,select')?.focus();}
 const field=(label,name,value='',type='input',extra='')=>`<label class="field">${label}${type==='textarea'?`<textarea name="${name}" rows="3" ${extra}>${esc(value)}</textarea>`:`<input name="${name}" value="${esc(value)}" ${extra}/>`}</label>`;
 const submit=(text)=>`<div class="form-actions"><button type="button" data-action="close" class="secondary">取消</button><button type="submit" class="primary">${text}</button></div>`;
 function personDialog(edit=false){const p=edit?person():{};open(edit?'关于这位伙伴':'给在意的人，留一个位置',`<p class="dialog-intro">只需要一个名字，就可以先放下一颗蛋。真实材料与画像，之后慢慢补充。</p><form data-form="${edit?'person-update':'person-create'}">${field('名字 <span>必填</span>','name',p.name||'','input','required maxlength="80" placeholder="你如何称呼 TA？"')}<div class="field">我想怎样与 TA 相处<select name="goalType"><option value="friendship" ${p.goalType!=='romance'?'selected':''}>♧ 认识朋友</option><option value="romance" ${p.goalType==='romance'?'selected':''}>♡ 发展亲密关系</option></select></div>${field('具体的期待 <span>可选</span>','goal',p.goal||'','input','placeholder="比如：找到自然聊天的方式"')}<details class="form-details" ${edit?'open':''}><summary>背景与牧场形象</summary>${field('我的备注 <span>会标注为用户转述</span>','notes',p.notes||'','textarea')}<div class="form-grid"><label class="field">孵化后的形象<select name="creature">${[['rabbit','小兔'],['cat','小猫'],['fox','小狐狸'],['bear','小熊']].map(([v,t])=>`<option value="${v}" ${p.creature===v?'selected':''}>${t}</option>`).join('')}</select></label><label class="field">喜欢的颜色<select name="color">${[['sage','鼠尾草绿'],['lilac','淡紫'],['peach','蜜桃'],['sky','天空蓝']].map(([v,t])=>`<option value="${v}" ${p.color===v?'selected':''}>${t}</option>`).join('')}</select></label></div></details>${submit(edit?'保存修改':'放进牧场')}</form>${edit?'<button class="text-button danger delete-person" data-action="delete-person">移除这位伙伴…</button>':''}`);}
 async function mutate(action,body={},close=true,origin=context()){if(busy)return;busy=true;const originatedInDialog=modal.open;const owns=()=>mutationOwnsContext(origin,context());const buttons=owns()?[...modal.querySelectorAll('button[type="submit"]')]:[];buttons.forEach(b=>b.disabled=true);if(owns()&&modal.querySelector('#source-platform'))updateSourceSelection();try{await api(action,body);await refresh();if(close&&owns()&&originatedInDialog)modal.close();return owns();}catch(err){if(err.name!=='AbortError'){if(owns()&&originatedInDialog&&modal.open){const error=modal.querySelector('.dialog-error');if(error)error.textContent=err.message;}else notice(err.message);try{await refresh();}catch{}}return false;}finally{busy=false;if(owns())buttons.forEach(b=>b.disabled=false);if(modal.open&&modal.querySelector('#source-platform'))updateSourceSelection();}}

 function materialDialog(){const self=target()==='self';open(self?'补充我的材料':'补充一份了解材料',`<form data-form="material-add">${field('材料标题 <span>可选</span>','label','','input','placeholder="例如：周末的一次聊天"')}<label class="field">这份材料来自<select name="speaker"><option value="${self?'me':'them'}">${self?'我的发言':'对方的发言'}</option>${self?'':'<option value="me">我的发言</option>'}<option value="context">上下文 / 背景</option></select></label>${field('原始内容','text','','textarea','required rows="7" placeholder="每份材料只粘贴所选说话者的原文；多人的完整对话请选择上下文，或分别添加。"')}<p class="hint">每份材料只包含所选说话者的原文。多人的完整对话请选择“上下文 / 背景”，或按人分别添加；关联已有聊天会自动区分发言与上下文。</p>${submit('保存材料')}</form>`);}
 function sourceValues(){return {platform:modal.querySelector('#source-platform')?.value||'',conversationId:modal.querySelector('#source-conversation')?.value||'',memberId:modal.querySelector('#source-member')?.value||'',query:modal.querySelector('#source-search')?.value||''};}
 function sourceOwns(token,origin){return modal.open&&mutationOwnsContext(origin,context())&&sourceGate.accepts(token);}
 function updateSourceSelection(changed='member'){
  const platform=modal.querySelector('#source-platform');if(!platform)return;
  const conversation=modal.querySelector('#source-conversation'),member=modal.querySelector('#source-member'),empty=modal.querySelector('#source-empty');
  const previous=sourceValues();let selection=sourceSelection(sources,previous);
  if(changed!=='member'){
   conversation.innerHTML=platform.value?conversationOptions(selection.conversations):'<option value="">请先选择平台</option>';
   conversation.value=selection.conversation?.id||'';
   if(previous.conversationId&&!conversation.value){sourceGate.conversation('');if(sourceStatus.loading==='members')sourceStatus.loading='';}
   selection=sourceSelection(sources,{...previous,conversationId:conversation.value});
   const ready=sourceReady(selection.conversation);
   member.innerHTML='<option value="">'+(sourceStatus.loading==='members'?'正在读取会话成员…':selection.conversation?'请选择成员':'请先选择会话')+'</option>'+(ready?selection.conversation.members||[]:[]).map(m=>`<option value="${esc(m.id)}">${esc(m.name)} · ${esc(m.count||0)} 条发言</option>`).join('');
   member.value=changed==='search'&&selection.member?selection.member.id:'';
  }
  selection=sourceSelection(sources,sourceValues());
  const loading=!!sourceStatus.loading;
  platform.disabled=busy;
  conversation.disabled=busy||sourceStatus.loading==='list'||!selection.conversations.length;
  member.disabled=busy||loading||!selection.conversation||!sourceReady(selection.conversation)||!selection.conversation.members?.length;
  modal.querySelector('#source-search').disabled=busy||!platform.value||sourceStatus.loading==='list';
  modal.querySelector('[data-action="source-refresh"]').disabled=busy||!platform.value||sourceStatus.loading==='list';
  modal.querySelector('#source-count').textContent=sourceStatus.loading==='list'?`正在读取${platformNames[platform.value]}会话目录…`:platform.value?`显示 ${selection.conversations.length} / ${selection.total} 个可用会话`:'选择平台后读取本机可用会话目录';
  const message=sourceStatus.error||(sourceStatus.loading==='members'?'正在按需读取所选会话的当前可用消息与成员…':!loading&&platform.value&&!selection.total?'当前平台没有返回可用会话。可刷新目录，或检查客户端与本机数据来源。':!loading&&selection.total&&!selection.conversations.length?'没有匹配的会话名称，请换个关键词。':!loading&&selection.conversation&&!selection.conversation.members?.length?'当前读取范围没有可识别的成员发言。请选择其他会话或刷新后重试。':'');
  empty.hidden=!message;empty.textContent=message;
  modal.querySelector('#source-detail').textContent=sourceStatus.detail;
  modal.querySelector('#source-scope').textContent=selection.conversation?`${selection.conversation.live===true?'本机实时读取':'已保存记录'} · 覆盖范围：${selection.conversation.scope||'选择后读取的可用消息'}。仅导入你明确选择的身份及标注的上下文。`:'先读取会话目录；仅在你选择某个会话后，读取其当前可用消息与成员。';
  modal.querySelector('button[type="submit"]').disabled=loading||!!sourceStatus.error||!selection.member||busy;
 }
 async function loadSourcePlatform(resetSearch=true){
  const platform=modal.querySelector('#source-platform')?.value||'';if(!sourceOrigin)return;
  const origin=sourceOrigin,token=sourceGate.platform(platform);sourceStatus={loading:platform&&platform!=='other'?'list':'',error:'',detail:''};
  modal.querySelector('#source-conversation').value='';modal.querySelector('#source-member').value='';
  if(resetSearch)modal.querySelector('#source-search').value='';
  if(platform&&platform!=='other')sources=sources.filter(s=>sourcePlatform(s)!==platform);
  updateSourceSelection('platform');if(!platform||platform==='other'){sourceStatus.detail=platform?'其他平台的已保存记录；不是客户端实时会话目录。':'';updateSourceSelection('platform');return;}
  try{const result=await api('source-list',{platform});if(!sourceOwns(token,origin))return;
   sources=[...sources.filter(s=>sourcePlatform(s)!==platform),...(result.conversations||[]).filter(s=>sourcePlatform(s)===platform).map(s=>({...s,members:s.live===true?[]:s.members||[],membersLoaded:false}))];
   sourceStatus={loading:'',error:'',detail:[result.detail,...(result.warnings||[])].filter(Boolean).join('；')};updateSourceSelection('platform');
  }catch(err){if(!sourceOwns(token,origin))return;sourceStatus={loading:'',error:err.message,detail:'目录读取失败，未使用旧记录冒充实时会话。请检查来源后点击刷新。'};updateSourceSelection('platform');}
 }
 async function loadSourceMembers(){
  const selection=sourceSelection(sources,sourceValues());const conversation=selection.conversation;
  const token=sourceGate.conversation(conversation?.id||''),origin=sourceOrigin;sourceStatus.error='';
  if(!conversation||conversation.live!==true){sourceStatus.loading='';updateSourceSelection('conversation');return;}
  sources=sources.map(s=>s.id===conversation.id?{...s,members:[],membersLoaded:false}:s);
  sourceStatus.loading='members';updateSourceSelection('conversation');
  try{const result=await api('source-members',{conversationId:conversation.id});if(!sourceOwns(token,origin))return;
   if(result.id!==conversation.id)throw new Error('读取返回的会话不匹配，请重新选择。');
   if(typeof result.snapshotId!=='string'||!result.snapshotId.trim())throw new Error('会话读取缺少有效快照，请重新选择后再导入。');
   sources=sources.map(s=>s.id===conversation.id?{...s,...result,members:result.members||[],membersLoaded:true}:s);
   sourceStatus.loading='';updateSourceSelection('loaded');
  }catch(err){if(!sourceOwns(token,origin))return;sourceStatus.loading='';sourceStatus.error=err.message;updateSourceSelection('loaded');}
 }
 async function sourceDialog(){open('从平台聊天中，关联一个人','<p class="muted">正在准备来源选择…</p>');const origin=context();let result={conversations:[],warnings:[]};try{result=await api('sources');}catch(err){result.warnings=['已存记录暂不可用：'+err.message];}if(!modal.open||!mutationOwnsContext(origin,context()))return;
  sources=result.conversations||[];sourceGate.platform('');sourceStatus={loading:'',error:'',detail:''};
  open('从平台聊天中，关联一个人',`<p class="dialog-intro">先选平台，读取本机可用的会话目录；再选择会话，按需读取成员。${target()==='self'?'请选择代表你自己的成员身份。':'同一个人出现在不同聊天中时，由你确认并关联。'}</p><form data-form="link-source">${sourceFields(sources)}${(result.warnings||[]).map(w=>`<p class="hint">${esc(w)}</p>`).join('')}${submit('关联并导入材料')}</form>`);sourceOrigin=context();updateSourceSelection('platform');
 }

 function selfDialog(){const p=data.self;open('先说说，你自己',`<form data-form="self-update">${field('称呼','name',p.name||'我','input','required maxlength="80"')}${field('关于我','about',p.about||'','textarea','placeholder="兴趣、生活状态，以及你希望建立什么样的关系"')}${field('表达习惯','style',p.style||'','textarea','placeholder="比如：喜欢直接但温和的表达，不擅长找话题"')}${field('我的边界','boundaries',p.boundaries||'','textarea','placeholder="比如：不想频繁追问，也不愿用套路试探"')}${submit('保存我的自述')}</form>`);}
 function uploadDialog(){open('给书架添一份参考',`<form data-form="knowledge-upload">${field('资料名称 <span>可选，默认文件名</span>','title')}<label class="upload-zone">选择资料文件<input type="file" name="file" accept=".txt,.md,.pdf,.docx,.epub" required/></label><p class="hint">支持 TXT、Markdown、PDF、DOCX、EPUB，单份最多 10 MB。扫描版 PDF 暂不支持文字提取。</p>${submit('上传并建立索引')}</form>`);}
 function confirmDialog(title,description,action,id){open(title,`<p class="dialog-intro">${esc(description)}</p><form data-form="${action}" data-id="${esc(id)}">${submit('确认移除')}</form>`);}
 async function handleClick(event){const b=event.target.closest('[data-action]');if(!b||b.disabled||(!app.contains(b)&&!modal.contains(b)))return;const action=b.dataset.action;try{
 if(action==='close'){dialogEpoch++;modal.close();return;}
 if(['ranch','self','library'].includes(action)){ui.page=action;ui.tab='profile';render();return;}
 if(action==='person'){ui.page='person';ui.selected=b.dataset.id;ui.tab='profile';render();return;}
 if(action==='tab'){ui.tab=b.dataset.tab;render();return;}
 if(action==='new'){personDialog();return;}if(action==='edit'){personDialog(true);return;}
 if(action==='self-edit'){selfDialog();return;}if(action==='material-add'){materialDialog();return;}
 if(action==='source-refresh'){await loadSourcePlatform(false);return;}
 if(action==='link-source'){await sourceDialog();return;}if(action==='upload'){uploadDialog();return;}
 if(action==='delete-person'){confirmDialog('移除这位伙伴？','此人的材料、画像与攻略将一并移除；原有聊天记录保留。','person-delete',target());return;}
 if(action==='material-remove'){confirmDialog('移除这份材料？','依赖这份材料的画像与攻略会失效，需要重新生成。','material-remove',b.dataset.id);return;}
 if(action==='knowledge-delete'){confirmDialog('从书架移除资料？','这份资料及其保留书摘会被移除；引用它的攻略会一并移除，其余攻略标记为待更新。','knowledge-delete',b.dataset.id);return;}
 if(action==='knowledge-toggle'){const book=data.library.find(v=>v.id===b.dataset.id);await mutate(action,{id:book.id,enabled:!book.enabled},false);return;}
 if(action==='analyze'||action==='strategy'){if(jobActive(data.job))return;await mutate(action,{id:target(),...(action==='strategy'?{situation:drafts.get(target())||''}:{})},false);return;}
 if(action==='cancel'){
  if(cancelPending||data.job?.status==='cancelling')return;
  const runId=b.dataset.runId;if(runId&&data.job?.id!==runId)return;
  cancelPending=true;render();
  try{await api('cancel',runId?{runId}:{});await refresh();}
  catch(err){try{await refresh();}catch{}throw err;}
  finally{cancelPending=false;render();}return;
 }
 if(action==='copy'){await navigator.clipboard.writeText(b.dataset.reply||'');notice('参考回应已复制，可按自己的语气调整。');}
 }catch(err){notice(err.message);}}
 async function handleSubmit(event){const form=event.target.closest('form[data-form]');if(!form||!modal.contains(form))return;event.preventDefault();if(busy)return;const origin=context();const action=form.dataset.form;const body={...Object.fromEntries(new FormData(form)),revision:Number(form.dataset.revision)};const formTarget=form.dataset.targetId;if(['person-update','material-add','link-source','analyze','strategy'].includes(action))body.id=formTarget;if(action==='material-remove'){body.id=formTarget;body.materialId=form.dataset.id;}if(['person-delete','knowledge-delete'].includes(action))body.id=form.dataset.id;
 if(action==='link-source'){
  const selected=sourceSelection(sources,{...body,query:sourceValues().query});
  if(sourceStatus.loading||sourceStatus.error||!selected.member){modal.querySelector('.dialog-error').textContent='请按顺序选择平台、会话和成员。';return;}
  if(selected.conversation.live===true)body.snapshotId=selected.conversation.snapshotId;
 }
 if(action==='knowledge-upload'){const file=body.file;if(!file?.size){modal.querySelector('.dialog-error').textContent='请选择包含文字的文件。';return;}if(file.size>10*1024*1024){modal.querySelector('.dialog-error').textContent='文件超过 10 MB，请缩小后再上传。';return;}delete body.file;body.filename=file.name;body.title=body.title.trim()||file.name.replace(/\.[^.]+$/,'');busy=true;try{body.dataBase64=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result.split(',')[1]);reader.onerror=()=>reject(new Error('无法读取该文件'));reader.readAsDataURL(file);});}finally{busy=false;}}
 const oldIds=new Set(data.people.map(p=>p.id));if(await mutate(action,body,true,origin)){if(action==='person-create'){const created=data.people.find(p=>!oldIds.has(p.id));if(created){ui.page='person';ui.selected=created.id;ui.tab='profile';render();}}if(action==='person-delete'){ui.page='ranch';render();}notice(action==='link-source'?'关联完成。重复导入会自动跳过已有材料。':action==='knowledge-upload'?'资料已入架，可以用于攻略参考。':'已保存。');}}
 modal.addEventListener('cancel',()=>{dialogEpoch++;},{signal:abort.signal});
 document.addEventListener('click',handleClick,{signal:abort.signal});document.addEventListener('submit',e=>{const origin=context();handleSubmit(e).catch(err=>{if(modal.open&&mutationOwnsContext(origin,context()))modal.querySelector('.dialog-error').textContent=err.message;else notice(err.message);});},{signal:abort.signal});
 app.addEventListener('input',e=>{if(e.target.id==='situation')drafts.set(target(),e.target.value);},{signal:abort.signal});
 modal.addEventListener('input',event=>{if(event.target.id==='source-search')updateSourceSelection('search');},{signal:abort.signal});
 modal.addEventListener('change',event=>{if(event.target.id==='source-platform')loadSourcePlatform();else if(event.target.id==='source-conversation')loadSourceMembers();else if(event.target.id==='source-member')updateSourceSelection('member');},{signal:abort.signal});
 app.innerHTML='<div class="ranch-loading"><span class="spinner"></span><p>正在打开你的小牧场…</p></div>';
 refresh().catch(err=>{if(err.name!=='AbortError')app.innerHTML=`<div class="ranch-loading"><h2>暂时无法打开牧场</h2><p>${esc(err.message)}</p><a href="/" class="secondary">重新加载</a></div>`;});
 return()=>{disposed=true;abort.abort();clearTimeout(timer);clearTimeout(noticeTimer);app.replaceChildren();modal.close();};
}
