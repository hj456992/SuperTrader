import {chromium} from '/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const root=new URL('../',import.meta.url);
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
try{
 const response=await page.request.get('http://127.0.0.1:48760/app.js');assert.equal(await response.text(),fs.readFileSync(new URL('src/main/resources/web/app.js',root),'utf8'));
 await page.goto('http://127.0.0.1:48760/');await page.waitForFunction(()=>document.querySelector('#stat-experts').textContent==='1');
 await page.click('.nav[data-view="experts"]');const value=await page.locator('#edit-expert option').nth(1).getAttribute('value');await page.selectOption('#edit-expert',value);
 assert.equal(await page.locator('.agent-card').count(),3);assert.equal(await page.locator('#activate').textContent(),'此版本已启用');
 await page.screenshot({path:new URL('evidence/05-final-team.png',root).pathname,fullPage:true});
 await page.goto('http://127.0.0.1:61928/index.html#chapter-13');await page.waitForSelector('#chapter-13.active');
 await page.getByRole('heading',{name:'专家插件已实现的两层版本，与爱聊待集成部分'}).scrollIntoViewIfNeeded();
 await page.screenshot({path:new URL('evidence/06-docs.png',root).pathname,fullPage:false});
 assert.ok((await page.locator('#chapter-13').textContent()).includes('activeVersionId'));
 assert.equal(errors.length,0);
 fs.writeFileSync(new URL('evidence/final-smoke.json',root),JSON.stringify({status:'passed',servedAssetsMatch:true,restoredExperts:1,subagents:3,activeVersionPreserved:true,docsChapter:13,pageErrors:errors},null,2));
 console.log('PASS latest packaged UI, persisted expert, active version and documentation browser smoke');
}finally{await browser.close();}
