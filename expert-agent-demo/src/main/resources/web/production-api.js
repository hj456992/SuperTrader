(function (root) {
  'use strict';
  const BASE='/api/expert-production/v1';
  const phases=[['prelearning','预学习'],['summary','概要审阅'],['specialists','专业子专家'],['fallback','兜底专家'],['router','主专家'],['production_config','关键词与问答'],['final_review','整体确认']];
  const scopeFor=a=>a?.kind==='book_summary'?'summary.full':a?.kind==='team_manifest'?'team.full':'artifact.full';
  function artifacts(s) {const all=[...(s?.artifacts||[])];if(s?.currentArtifact&&!all.some(a=>a.id===s.currentArtifact.id))all.push(s.currentArtifact);return all;}
  function target(s,artifactId,revisionId) {
    if(!s)return null;
    const a=artifactId?artifacts(s).find(x=>x.id===artifactId):(s.currentArtifact||artifacts(s).find(x=>x.id===s.currentArtifactId));
    if(!a)return null;
    const r=revisionId?([a.currentRevision,...(a.revisions||[])].filter(Boolean).find(x=>x.id===revisionId)):a.currentRevision;
    return r?{artifact:a,revision:r}:null;
  }
  function presentation(s,t) {
    if(!t)return null;
    const scope=scopeFor(t.artifact),r=t.revision;
    const msgs=[...(s.messages||[]),...(s.waitingQuestionMessage?[s.waitingQuestionMessage]:[])];
    const m=msgs.reverse().find(m=>m.presentation&&m.presentation.revisionId===r.id&&m.presentation.contentSha256===r.contentSha256&&m.presentation.scope===scope&&(!m.presentation.artifactId||m.presentation.artifactId===t.artifact.id));
    return m?{artifactId:t.artifact.id,revisionId:r.id,presentedMessageId:m.id,contentSha256:r.contentSha256,scope}:null;
  }
  function canReview(s,t) {
    if(!s||!t||s.status!=='active')return false;
    const {artifact:a,revision:r}=t;
    return a.id===(s.currentArtifact?.id||s.currentArtifactId)&&r.id===a.currentRevision?.id&&r.generationStatus==='succeeded'&&r.reviewStatus==='pending'&&a.dependencyState!=='stale'&&!(s.clarifications||[]).some(c=>c.status==='open')&&(a.kind!=='agent'||typeof r.systemPrompt==='string'&&r.systemPrompt.trim().length>0)&&!!presentation(s,t);
  }
  function messageRequest(s,t,content,id,action) {
    const request={clientRequestId:id,content,expectedLockVersion:String(s.lockVersion)};
    const context=presentation(s,t);
    if(context){
      if(action || t.revision.id===t.artifact.currentRevision?.id && t.artifact.dependencyState!=='stale')request.reviewContext=context;
      request.replyToMessageId=t.artifact.id===(s.currentArtifact?.id||s.currentArtifactId)&&t.revision.id===t.artifact.currentRevision?.id?(s.waitingQuestionMessageId||context.presentedMessageId):context.presentedMessageId;
    }
    else if(s.waitingQuestionMessageId)request.replyToMessageId=s.waitingQuestionMessageId;
    if(action){request.action={type:action.type,scope:scopeFor(t?.artifact)};if(['pause','resume','cancel','retry'].includes(action.type)){delete request.reviewContext;delete request.replyToMessageId;}}
    return request;
  }
  function route(hash) {
    const match=/^#production(?:\/([^?]+))?(?:\?(.*))?$/.exec(hash||'');if(!match)return null;
    const q=new URLSearchParams(match[2]||'');try{return {buildId:match[1]?decodeURIComponent(match[1]):'',artifactId:q.get('artifact')||'',revisionId:q.get('revision')||''};}catch{return {buildId:'',artifactId:'',revisionId:''};}
  }
  function hashFor(id,artifactId='',revisionId=''){let h='#production'+(id?'/'+encodeURIComponent(id):'');const q=new URLSearchParams();if(artifactId)q.set('artifact',artifactId);if(revisionId)q.set('revision',revisionId);return h+(q.size?'?'+q:'');}
  function phaseFor(a){if(a.kind==='book_summary')return 'summary';if(a.kind==='learning_unit')return 'prelearning';if(a.kind==='team_manifest')return 'final_review';if(['keyword_rule','qa_example'].includes(a.kind))return 'production_config';return a.agentRole==='fallback'?'fallback':a.agentRole==='router'?'router':'specialists';}
  function client(fetcher,getToken) {
    async function request(path,method='GET',body) {
      const options={method,credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}};
      if(body!==undefined){options.headers['Content-Type']='application/json';options.headers['X-Lab-Token']=getToken();options.body=JSON.stringify(body);}
      const response=await fetcher(BASE+path,options);let data;
      try{data=await response.json();}catch{const e=new Error('服务返回了无法读取的响应，请刷新状态后检查。');e.status=response.status;throw e;}
      if(!response.ok){const info=data.error;const e=new Error(typeof info==='string'?info:info?.message||data.result?.message||data.message||'生产服务暂不可用');e.status=response.status;e.code=info?.code||data.result?.code||data.code;e.detail=data;throw e;}
      return data;
    }
    const bp=id=>'/builds/'+encodeURIComponent(id);
    return {
      list:()=>request('/builds'),create:(id,body)=>request(bp(id),'PUT',body),propose:documents=>request('/proposals','POST',{documents}),
      snapshot:(id,before)=>request(bp(id)+'/snapshot'+(before?'?messageBeforeSeq='+encodeURIComponent(before):'')),
      events:(id,after)=>request(bp(id)+'/events?afterSeq='+encodeURIComponent(after||'0')),
      message:(id,body)=>request(bp(id)+'/messages','POST',body),
      source:(id,source)=>{const q=new URLSearchParams();if(source.startOffset!==undefined)q.set('startOffset',source.startOffset);if(source.endOffset!==undefined)q.set('endOffset',source.endOffset);return request(bp(id)+'/sources/'+encodeURIComponent(source.chunkId)+(q.size?'?'+q:''));}
    };
  }
  root.ProductionAPI={client,scopeFor,artifacts,target,presentation,canReview,messageRequest,route,hashFor,phaseFor,phases};
})(globalThis);
