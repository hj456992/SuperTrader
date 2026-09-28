import {chromium} from '/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
const root=new URL('../src/main/resources/web/',import.meta.url);
const page=await browser.newPage();
const state={csrf:'test',documents:[],imports:[{id:'i1',title:'长书',status:'paused',message:'网络中断',canResume:true}],experts:[{id:'e1',name:'原专家',activeVersionId:'ev1',versions:[{id:'ev1',number:1,name:'原专家',duty:'原职责',tested:true,createdAt:new Date().toISOString(),selections:[],subagents:[]}]}]};
let resumed=0;
await page.route('http://lab.test/**',async route=>{
 const p=new URL(route.request().url()).pathname;
 if(p==='/api/state')return route.fulfill({json:state});
 if(p==='/api/imports/i1/resume'){resumed++;return route.fulfill({json:{importId:'i1',jobId:'j1'}});}
 if(p==='/api/imports/i1'){
  state.documents=[{id:'d1',title:'长书',versions:[{id:'dv1',number:1,pageCount:312,charCount:280000,chunkCount:120,filename:'book.pdf',createdAt:new Date().toISOString(),parser:{provider:'mineru'}}]}];state.imports=[];
  return route.fulfill({json:{id:'i1',status:'completed',result:{documentId:'d1',versionId:'dv1'},message:'完成'}});
 }
 const file=p==='/'?'index.html':p.slice(1);
 if(['index.html','app.js','style.css'].includes(file))return route.fulfill({body:fs.readFileSync(new URL(file,root)),contentType:file.endsWith('js')?'text/javascript':file.endsWith('css')?'text/css':'text/html'});
 return route.fulfill({status:404,body:''});
});
try {
 await page.goto('http://lab.test');await page.waitForFunction(()=>document.querySelector('#stat-experts').textContent==='1');
 await page.click('.nav[data-view="experts"]');await page.selectOption('#edit-expert','e1');await page.click('.nav[data-view="library"]');
 await page.locator('[data-resume-import="i1"]').click({timeout:4000});
 await page.waitForFunction(()=>document.querySelector('#stat-docs').textContent==='1');
 assert.equal(resumed,1);assert.equal(await page.locator('#edit-expert').inputValue(),'e1','import must not switch the chosen expert');
 assert.equal(await page.evaluate(()=>sessionStorage.getItem('expertLabImport')),null);
 assert.ok(await page.locator('a[href="/api/original?versionId=dv1#page=1"]').count());
 console.log('PASS resumable import refreshes library, preserves expert, links archived PDF');
} finally {await browser.close();}
