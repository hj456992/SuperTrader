import {chromium} from '/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
const root=new URL('../src/main/resources/web/',import.meta.url);
const initial={csrf:'test',documents:[{id:'d',title:'测试资料',versions:[{id:'dv',number:1,pageCount:1,charCount:100,chunkCount:1,createdAt:new Date().toISOString(),filename:'test.pdf'}]}],experts:[{id:'e',name:'测试专家',activeVersionId:'',versions:[{id:'v1',number:1,name:'测试专家',duty:'测试职责',tested:true,createdAt:new Date().toISOString(),selections:[{documentId:'d',versionId:'dv'}],subagents:[],processedChunks:1,methodCount:1}]}]};
const errors=[];
async function setup({failRefresh=false}={}){
 const page=await browser.newPage(); let state=structuredClone(initial),requests=[],fault=false,polled=0,stateCalls=0;
 await page.route('http://lab.test/**',async route=>{
  const url=new URL(route.request().url()), p=url.pathname;
  const json=body=>route.fulfill({json:body});
  if(p==='/api/state'){if(++stateCalls===2&&failRefresh)return route.abort('failed');return json(state);}
  if(p==='/api/chat'){requests.push(route.request().postDataJSON());return json({jobId:'chat'});}
  if(p==='/api/experts'){state.experts[0].versions.push({...structuredClone(state.experts[0].versions[0]),id:'v2',number:2,tested:false});return json({jobId:'generate'});}
  if(p==='/api/jobs/generate')return json({status:'completed',events:[],result:{expertId:'e',versionId:'v2'}});
  if(p==='/api/jobs/chat'){
   polled++;if(fault&&polled===1)return route.abort('failed');
   return json({status:'completed',events:[],result:{expertId:'e',versionId:'v1',conversationId:'v1-conversation',answer:'测试回答',references:[],reports:[]}});
  }
  const file=p==='/'?'index.html':p.slice(1);if(['index.html','app.js','style.css'].includes(file))return route.fulfill({body:fs.readFileSync(new URL(file,root)),contentType:file.endsWith('js')?'text/javascript':file.endsWith('css')?'text/css':'text/html'});
  return route.fulfill({status:404,body:''});
 });
 await page.goto('http://lab.test');await page.waitForFunction(()=>document.querySelector('#stat-experts').textContent==='1');
 await page.click('.nav[data-view="experts"]');await page.selectOption('#edit-expert','e');await page.click('#trial-open');
 return {page,requests,setFault:()=>fault=true};
}
try{
 const {page,requests}=await setup();await page.fill('#chat-input','第一个问题');await page.click('#send');await page.waitForSelector('.message.assistant');
 await page.click('.nav[data-view="experts"]');await page.click('#expert-form button[type="submit"]');await page.waitForFunction(()=>document.querySelector('#expert-version').value==='v2');
 await page.click('#trial-open');assert.equal(await page.locator('.message').count(),0,'new expert version must clear old messages');
 await page.fill('#chat-input','新版问题');await page.click('#send');await page.waitForTimeout(100);assert.equal(requests.at(-1).conversationId,'','new version must not carry previous conversation');assert.equal(requests.at(-1).versionId,'v2');console.log('PASS new version clears conversation');await page.close();
}catch(e){errors.push(e.message);}
try{
 const {page,setFault}=await setup();setFault();await page.fill('#chat-input','网络故障测试');await page.click('#send');await page.waitForTimeout(400);
 assert.ok(await page.evaluate(()=>sessionStorage.getItem('expertLabJob')),'transient poll error must retain recoverable job');
 assert.equal(await page.locator('#cancel-job').isVisible(),true,'cancel remains available');
 await page.waitForSelector('.message.assistant',{timeout:12000});assert.equal(await page.evaluate(()=>sessionStorage.getItem('expertLabJob')),null);console.log('PASS transient poll error recovers');await page.close();
}catch(e){errors.push(e.message);}
try{
 const {page}=await setup({failRefresh:true});await page.fill('#chat-input','完成后状态断网测试');await page.click('#send');await page.waitForTimeout(400);
 assert.ok(await page.evaluate(()=>sessionStorage.getItem('expertLabJob')),'completed job must survive state refresh failure');
 assert.equal(await page.locator('#send').isDisabled(),true,'cannot start new task while applying old result');
 await page.waitForSelector('.message.assistant',{timeout:12000});assert.equal(await page.evaluate(()=>sessionStorage.getItem('expertLabJob')),null);console.log('PASS completed job survives state refresh failure');await page.close();
}catch(e){errors.push(e.message);}
await browser.close();if(errors.length)throw new Error(errors.join('\n'));
