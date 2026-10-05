#!/usr/bin/env python3
"""Run an explicitly labelled, isolated administrator test build against the REAL service/model.
Does not approve any existing team. Private snapshots stay outside git under --output.
This verifies engineering flow, not the correctness of a book interpretation.
"""
import argparse, json, time, uuid, urllib.request, urllib.error
from pathlib import Path


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--base',default='http://127.0.0.1:48763')
    ap.add_argument('--document-title',default='原创演示资料：职场沟通方法')
    ap.add_argument('--output',required=True)
    ap.add_argument('--hold-after-summary',action='store_true')
    ap.add_argument('--natural-approvals',action='store_true',help='Route every test approval through the real intent model instead of explicit UI action')
    ap.add_argument('--resume-build',help='Resume only the build recorded in this private output directory')
    args=ap.parse_args()
    output=Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True);output.chmod(0o700)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token=''
    def call(method,path,data=None):
        raw=None if data is None else json.dumps(data,ensure_ascii=False).encode()
        req=urllib.request.Request(args.base+path,data=raw,method=method,headers={'Content-Type':'application/json','X-Lab-Token':token})
        try:
            with opener.open(req,timeout=20) as r: return json.load(r)
        except urllib.error.HTTPError as e:
            raise RuntimeError(f'HTTP {e.code}: {e.read().decode()}') from None
    state=call('GET','/api/state');token=state['csrf']
    docs=[d for d in state['documents'] if d['title']==args.document_title]
    if len(docs)!=1:raise RuntimeError('Select one exact document title')
    doc=docs[0];version=doc['versions'][-1]
    build=args.resume_build or str(uuid.uuid4());base='/api/expert-production/v1/builds/'+build
    if args.resume_build:
        prior=json.loads((output/'identity.json').read_text())
        if prior.get('buildId')!=build or not prior.get('testOnly') or prior.get('documentId')!=doc['id']:
            raise RuntimeError('Resume requires the same recorded test build and document')
    identity={'buildId':build,'documentId':doc['id'],'documentVersionId':version['id'],'testOnly':True,'realModel':True,'completed':False}
    def save(name,value):
        file=output/name;file.write_text(json.dumps(value,ensure_ascii=False,indent=2));file.chmod(0o600)
    save('identity.json',identity)
    if not args.resume_build:call('PUT',base,{'teamId':str(uuid.uuid4()),'teamName':'独立真实模型流程验收（测试）','name':'职场沟通验收团队（测试）','responsibility':'协助内部职场沟通，组织有依据的汇报、澄清需求和反馈。限于所选资料的方法；事实由提问者提供，信息不足先追问，不执行外部发送或公开发布。这是独立测试团队。','documents':[{'documentId':doc['id'],'documentVersionId':version['id']}]})
    print(json.dumps({'resumed' if args.resume_build else 'created':build,'url':args.base+'/#production/'+build}),flush=True)
    def snapshot():return call('GET',base+'/snapshot')
    def wait(predicate,label):
        deadline=time.monotonic()+600;last=0
        while time.monotonic()<deadline:
            s=snapshot();save('latest.json',s)
            jobs=s.get('jobs',[])
            failed=[j for index,j in enumerate(jobs) if j['status']=='failed' and j['kind']!='interpret_message' and not any(n['kind']==j['kind'] and n.get('targetRevisionId')==j.get('targetRevisionId') for n in jobs[index+1:])]
            if failed:raise RuntimeError('Task failed: '+json.dumps(failed,ensure_ascii=False))
            if predicate(s):return s
            if time.monotonic()-last>25:
                print(json.dumps({'waiting':label,'phase':s['phase'],'status':s['status']},ensure_ascii=False),flush=True);last=time.monotonic()
            time.sleep(1)
        raise RuntimeError('Timed out: '+label)
    def post(s,content,action=None):
        a=s.get('currentArtifact') or {};r=a.get('currentRevision') or {}
        req={'clientRequestId':str(uuid.uuid4()),'content':content,'expectedLockVersion':s['lockVersion']}
        shown=[m for m in s['messages'] if m.get('presentation',{}).get('revisionId')==r.get('id')]
        if shown:req['reviewContext']={**shown[-1]['presentation'],'presentedMessageId':shown[-1]['id']}
        if action:req['action']={'type':action,'scope':req.get('reviewContext',{}).get('scope','')}
        response=call('POST',base+'/messages',req);mid=response['messageId']
        final=wait(lambda x:any(m['id']==mid and m.get('processingStatus') in ['succeeded','failed'] for m in x['messages']),'message')
        m=next(m for m in final['messages'] if m['id']==mid)
        if m['processingStatus']=='failed' or m.get('result',{}).get('httpStatus')!=200:raise RuntimeError('Message failed: '+json.dumps(m,ensure_ascii=False))
        return final
    reviewed=[]
    for step in range(20):
        s=wait(lambda x:x['status']=='completed' or (x.get('currentArtifact') or {}).get('currentRevision',{}).get('generationStatus')=='succeeded','reviewable artifact')
        if s['status']=='completed':
            identity['completed']=True;identity['reviewed']=reviewed;save('identity.json',identity);save('completed.json',s)
            print(json.dumps({'completed':build,'reviewed':reviewed},ensure_ascii=False),flush=True);return
        a=s['currentArtifact'];r=a['currentRevision'];save(f'{step:02d}-{a["logicalKey"].replace(":","_")}.json',s)
        if args.hold_after_summary:
            print(json.dumps({'heldForAdministrator':build,'phase':s['phase'],'revision':r['id']},ensure_ascii=False),flush=True);return
        unresolved=[c for c in s.get('clarifications',[]) if c['status']=='open']
        if unresolved:raise RuntimeError('Administrator clarification requires test review: '+json.dumps(unresolved,ensure_ascii=False))
        if r['reviewStatus']!='pending':raise RuntimeError('Unexpected review status '+r['reviewStatus'])
        if a['kind']=='agent' and not r.get('systemPrompt','').strip():raise RuntimeError('Full prompt missing')
        for source in r.get('sources',[]):
            source_text=call('GET',base+'/sources/'+source['chunkId']+'?startOffset='+str(source['startOffset'])+'&endOffset='+str(source['endOffset']))
            if not source_text.get('text'):raise RuntimeError('Original passage empty')
        if step==0 and a['kind']=='book_summary':
            post(s,'请解释这份概要的适用范围，不要推进审核。')
            s=snapshot()
            if s['currentArtifact']['currentRevision']['reviewStatus']!='pending':raise RuntimeError('Discussion incorrectly approved summary')
        # Approval is confined to the newly created test build; it is automated test input.
        if args.natural_approvals:
            approval={
                'book_summary':'当前整书概要完整准确，我确认通过这个版本。',
                'agent':'当前这位专家的完整描述、职责、资料依据、模型、工具、能力、边界和完整系统提示词，我已完整审阅，确认通过当前版本。',
                'keyword_rule':'当前完整的关键词规则，我确认通过这个版本。',
                'qa_example':'当前完整的问答知识库和路由配置，我确认通过这个版本。',
                'team_manifest':'当前专家团队的完整流程和全部成员清单，我已核对，确认整体通过当前版本，完成团队生产。'
            }[a['kind']]
            post(s,approval)
        else:
            post(s,'自动化验收输入：确认当前展示的完整内容和提示词，通过本测试版本。','approve')
        reviewed.append({'kind':a['kind'],'role':a['agentRole'],'revision':r['id']})
        wait(lambda x:x['status']=='completed' or (x.get('currentArtifact') or {}).get('currentRevision',{}).get('id')!=r['id'],'next stage')
    raise RuntimeError('Too many stages')

if __name__=='__main__':main()
