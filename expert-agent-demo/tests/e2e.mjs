import {chromium} from '/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs';
import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
const errors=[];
page.on('response',r=>{if(r.status()>=400)console.log('HTTP',r.status(),r.url());});
page.on('pageerror',e=>errors.push(e.message));
page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
const check=(value,message)=>{if(!value)throw Error(message);};
try {
 await page.goto(process.env.EXPERT_URL || 'http://127.0.0.1:48760/');
 if(!process.env.RESUME_TRIAL){
 await page.waitForFunction(()=>document.querySelector('#stat-docs').textContent==='0');
 await page.screenshot({path:path.join(root,'evidence/01-empty.png'),fullPage:true});
 await page.click('#upload-open');
 await page.fill('#document-title','原创演示资料：职场沟通方法');
 await page.setInputFiles('#pdf-file',path.join(root,'evidence/演示资料-职场沟通方法.pdf'));
 await page.click('#upload-submit');
 await page.waitForFunction(()=>document.querySelector('#stat-docs').textContent==='1');
 console.log('PASS PDF upload and extraction');
 await page.click('.nav[data-view="experts"]');
 await page.fill('#expert-name','职场沟通顾问 · 演示');
 await page.fill('#expert-duty','帮助用户理解工作指令，协商工作量与优先级，并处理合作中的分歧。结合具体背景提出可执行回应，不推断没有依据的动机。');
 await page.check('.selection input');
 await page.click('#expert-form button[type="submit"]');
 let chatLast='';
 for(let i=0;i<400;i++){
  await page.waitForTimeout(1000);
  const message=await page.locator('#job-message').textContent();
  if(message!==last){console.log('GEN',message);last=message;}
  if(await page.locator('#expert-result').isVisible())break;
  const title=await page.locator('#job-title').textContent();
  if(title==='任务未完成'||title==='无法读取任务')throw Error(await page.locator('#notice').textContent());
 }
 check(await page.locator('#expert-result').isVisible(),'Generation did not complete');
 check(await page.locator('.agent-card').count()>=1,'No generated subagents');
 check(await page.locator('#activate').isDisabled(),'Untested version may activate');
 await page.screenshot({path:path.join(root,'evidence/02-expert-team.png'),fullPage:true});
 console.log('PASS real model generated expert team',await page.locator('.agent-card').count());
 await page.locator('.agent-card details').first().locator('summary').click();
 await page.locator('.source-link').first().click();
 await page.waitForSelector('#source-dialog[open]');
 check((await page.locator('#source-text').textContent()).length>20,'Missing source text');
 await page.click('#source-close');
 }else{
  await page.waitForFunction(()=>document.querySelector('#stat-experts').textContent!=='0');
  await page.click('.nav[data-view="experts"]');
  const option=await page.locator('#edit-expert option').nth(1).getAttribute('value');
  await page.selectOption('#edit-expert',option);
 }
 let chatLast='';
 await page.click('#trial-open');
 await page.fill('#chat-input','领导说“明天前把同事的报告接过来做完”。我正在做周五要交付的项目，接手报告预计要两天。我希望先确认交付范围，再协商优先级。请分别从沟通理解和工作量协商两方面分析，给出一条可以发给领导的回复。');
 await page.click('#send');
 chatLast='';
 for(let i=0;i<400;i++){
  await page.waitForTimeout(1000);
  const message=await page.locator('#job-message').textContent();
  if(message!==chatLast){console.log('CHAT',message);chatLast=message;}
  if(await page.locator('.message.assistant').count())break;
  const title=await page.locator('#job-title').textContent();
  if(title==='任务未完成'||title==='无法读取任务')throw Error(await page.locator('#notice').textContent());
 }
 check(await page.locator('.message.assistant').count()===1,'No answer');
 check(await page.locator('.report').count()>=1,'No real child agent result');
 check(await page.locator('.message.assistant .source-link').count()>=1,'No verified references');
 await page.screenshot({path:path.join(root,'evidence/03-chat.png'),fullPage:true});
 console.log('PASS real DSH expert and specialist conversation');
 await page.fill('#chat-input','先不用再分析，请用一句话复述我最初遇到的问题。');await page.click('#send');
 await page.waitForFunction(()=>document.querySelectorAll('.message.assistant').length===2,{},{timeout:300000});
 check((await page.locator('.message.assistant').last().textContent()).includes('报告'),'Follow-up did not retain original context');
 console.log('PASS same-version multi-turn context');
 await page.click('.nav[data-view="experts"]');
 if(await page.locator('#activate').textContent()!=='此版本已启用'){
 check(await page.locator('#activate').isEnabled(),'Trial did not permit activation');
 await page.click('#activate');
 }
 await page.waitForFunction(()=>document.querySelector('#activate').textContent==='此版本已启用');
 console.log('PASS activate tested expert');
 await page.setViewportSize({width:390,height:844});
 await page.screenshot({path:path.join(root,'evidence/04-mobile.png'),fullPage:true});
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'Mobile horizontal overflow');
 check(errors.length===0,'Browser errors: '+errors.join(';'));
 fs.writeFileSync(path.join(root,'evidence/e2e-result.json'),JSON.stringify({status:'passed',browserErrors:errors,at:new Date().toISOString()},null,2));
} catch(error){await page.screenshot({path:path.join(root,'evidence/e2e-error.png'),fullPage:true});fs.writeFileSync(path.join(root,'evidence/e2e-result.json'),JSON.stringify({status:'failed',error:String(error),browserErrors:errors},null,2));throw error;}
finally{await browser.close();}
