// Real upload acceptance: consumes the configured MinerU account; no model generation is triggered.
import {chromium} from '/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
if(process.env.MINERU_REAL_CONFIRM!=='1')throw new Error('This is a real cloud upload; set MINERU_REAL_CONFIRM=1 only for the authorized acceptance run.');
const pdf='/Users/hou/Downloads/SelfImprovementBooks/金字塔原理.pdf';
const hash=()=>crypto.createHash('sha256').update(fs.readFileSync(pdf)).digest('hex');
const beforeHash=hash();
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1100}});
const evidence={startedAt:new Date().toISOString(),sha256:beforeHash,bytes:fs.statSync(pdf).size,expectedPages:312,events:[]};
const save=()=>fs.writeFileSync('evidence/mineru-split/live-result.json',JSON.stringify(evidence,null,2));
try {
 await page.goto('http://127.0.0.1:48760/');await page.waitForFunction(()=>document.querySelector('#stat-docs').textContent!=='0');
 const before=await page.evaluate(()=>fetch('/api/state').then(r=>r.json()));
 evidence.previousActive=before.experts.map(e=>({id:e.id,activeVersionId:e.activeVersionId}));
 const existing=new Set((before.imports||[]).map(r=>r.id));
 const resumeId=process.env.MINERU_RESUME_IMPORT_ID;
 const verifyId=process.env.MINERU_VERIFY_IMPORT_ID;
 if(resumeId){const response=page.waitForResponse(r=>r.url().endsWith('/api/imports/'+resumeId+'/resume')&&r.request().method()==='POST');await page.locator(`[data-resume-import="${resumeId}"]`).click();assert.equal((await response).status(),202);}
 else if(!verifyId)await page.locator('[data-split-import="36113597-0ce3-4e11-a188-9495475e80b9"]').click();
 let id=verifyId||resumeId;
 while(!id){
  const state=await page.evaluate(()=>fetch('/api/state').then(r=>r.json()));
  id=(state.imports||[]).find(r=>!existing.has(r.id))?.id;

  if(!id)await new Promise(r=>setTimeout(r,1000));
 }
 evidence.importId=id;save();console.log('真实导入任务已创建',id);
 let last='';
 while(true){
  const item=await page.evaluate(id=>fetch('/api/imports/'+id).then(r=>r.json()),id);
  const message=item.status+' '+item.phase+' '+item.message;
  if(message!==last){last=message;const event={at:new Date().toISOString(),status:item.status,phase:item.phase,message:item.message,extractedPages:item.extractedPages};evidence.events.push(event);save();console.log(JSON.stringify(event));}
  if(item.status!=='running'){
   evidence.finishedAt=new Date().toISOString();evidence.import=item;evidence.originalUnchanged=hash()===beforeHash;
   await page.screenshot({path:'evidence/mineru-split/live.png',fullPage:true});
   const after=await page.evaluate(()=>fetch('/api/state').then(r=>r.json()));
   for(const e of evidence.previousActive)assert.equal(after.experts.find(x=>x.id===e.id)?.activeVersionId,e.activeVersionId);
   evidence.activeVersionsUnchanged=true;
   if(item.status==='completed'){
    const version=await page.evaluate(id=>fetch('/api/document-version?versionId='+id).then(r=>r.json()),item.result.versionId);
    assert.equal(version.pageCount,312);assert.equal(version.sha256,beforeHash);
    evidence.version={id:version.id,pageCount:version.pageCount,charCount:version.charCount,chunkCount:version.chunks.length,blankPages:version.blankPages,sha256:version.sha256,parser:version.parser};
    const original=await page.request.get('http://127.0.0.1:48760/api/original?versionId='+version.id);assert.equal(crypto.createHash('sha256').update(await original.body()).digest('hex'),beforeHash);
    evidence.archiveMatchesOriginal=true;
   }
   save();assert.equal(item.status,'completed',item.error||item.message);break;
  }
  await new Promise(r=>setTimeout(r,5000));
 }
 console.log('真实整书拆分/OCR/合并页码核验/资料入库完成；专家生成尚未执行。');
} catch(error){evidence.error=error.message;evidence.finishedAt=new Date().toISOString();save();throw error;} finally{await browser.close();}
