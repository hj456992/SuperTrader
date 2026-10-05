// Node-only binding/storage checks. Minimal DOM adapter; NOT browser rendering QA.
const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm'), path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const KEY='ailiao.design.expert-summary.prototype.v1';
let ids=0,count=0;
class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.listeners={};this.attrs={};this.dataset={};this.hidden=false;this.disabled=false;this.value='';this._text='';this.scrollHeight=0;}
 set textContent(t){this._text=String(t);this.children=[];}get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
 append(...els){this.children.push(...els);}replaceChildren(...els){this._text='';this.children=[...els];}
 addEventListener(name,f){(this.listeners[name]??=[]).push(f);}setAttribute(k,v){this.attrs[k]=v;}removeAttribute(k){delete this.attrs[k];}
 focus(){}showModal(){this.open=true;}close(){this.open=false;}
 fire(name){if(name==='click'&&this.disabled)return;for(const f of this.listeners[name]||[])f({target:this,preventDefault(){}});}
}
function storage(){const data=new Map();return {data,fail:false,getItem:k=>data.has(k)?data.get(k):null,setItem(k,v){if(this.fail)throw new Error('QuotaExceededError');data.set(k,String(v));}};}
function boot(store=storage()){
 const elements={};for(const m of html.matchAll(/<([a-z]+)\b[^>]*\bid="([^"]+)"[^>]*>/g))elements[m[2]]=new Element(m[1]);
 const examples=[...html.matchAll(/<button data-example="([^"]+)"/g)].map(m=>{const e=new Element('button');e.dataset.example=m[1];return e;});
 const window={listeners:{},addEventListener(name,f){this.listeners[name]=f;}};
 const ctx={document:{getElementById:id=>{assert.ok(elements[id],`HTML 必须存在绑定元素 ${id}`);return elements[id];},createElement:t=>new Element(t),querySelectorAll:q=>{assert.equal(q,'[data-example]');return examples;}},localStorage:store,window,crypto:{randomUUID:()=>`test-${++ids}`},console:{warn(){}},location:{reload(){} }};
 vm.createContext(ctx);for(const file of ['core.js','app.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),ctx,{filename:file});
 return {elements,examples,store,window,state:()=>JSON.parse(store.getItem(KEY)),say(text){elements.input.value=text;elements['chat-form'].fire('submit');},click(id){elements[id].fire('click');}};
}
function test(name,f){f();count++;console.log('PASS',name);}
test('页面初始概要和来源占位已绑定，无确认状态',()=>{const b=boot();assert.match(b.elements['summary-body'].textContent,/未读取/);assert.equal(b.elements['review-badge'].textContent,'待确认');assert.equal(b.state().phase,'summary');});
test('示例只填入，提交后问题回复不推进',()=>{const b=boot();b.examples[0].fire('click');assert.equal(b.state().messages.length,1);b.elements['chat-form'].fire('submit');assert.equal(b.state().messages.length,3);assert.equal(b.state().reviews.length,0);b.say('可以');assert.equal(b.state().phase,'summary');});
test('否决按钮追问，刷新恢复未决原因，补充后新稿可对比',()=>{let b=boot();b.click('reject');assert.equal(b.elements.pending.hidden,false);assert.equal(b.elements.approve.disabled,true);const saved=b.store;b=boot(saved);assert.match(b.elements['save-status'].textContent,/已恢复/);assert.equal(b.elements.pending.hidden,false);b.say('补充原因：需要说明适用边界');assert.equal(b.state().current,2);assert.match(b.elements.diff.textContent,/适用边界/);assert.equal(b.elements.pending.hidden,true);assert.equal(b.state().reviews[0].reason,null);assert.equal(b.state().reviews[1].reason,'需要说明适用边界');});
test('旧版本提交显示冲突，重试不重复写聊天',()=>{const b=boot();b.say('不通过：补充边界');b.click('old-confirm');assert.equal(b.state().lastResult.code,'VERSION_CONFLICT');const n=b.state().messages.length;b.click('retry');assert.equal(b.state().messages.length,n);assert.equal(b.state().revisions[1].review_status,'pending');});
test('焦点切换刷新后仍看历史，回当前稿才可确认',()=>{let b=boot();b.say('不通过：补充边界');b.elements.version.value='1';b.elements.version.fire('change');assert.equal(b.state().focus,1);assert.equal(b.elements.approve.disabled,true);b=boot(b.store);assert.equal(b.state().focus,1);assert.match(b.elements['focus-position'].textContent,/历史/);b.click('current');assert.equal(b.state().focus,2);b.click('approve');assert.equal(b.state().phase,'specialists');assert.equal(b.state().status,'active');assert.equal(b.elements['next-stage'].hidden,false);assert.equal(b.state().reviews.at(-1).scope,'summary.full');});
test('确认和聊天刷新恢复，后续未实现不能推进',()=>{let b=boot();b.click('approve');b=boot(b.store);assert.equal(b.elements['next-stage'].hidden,false);b.say('继续生成子专家');assert.equal(b.state().revisions.length,1);assert.equal(b.state().phase,'specialists');});
test('保存失败不能显示确认成功，也不能写入已确认',()=>{const b=boot();b.store.fail=true;b.click('approve');assert.equal(b.state().revisions[0].review_status,'pending');assert.equal(b.elements.approve.disabled,true);assert.match(b.elements['save-status'].textContent,/保存不可用/);assert.equal(b.elements['next-stage'].hidden,true);});
test('检测到其他页面写入时不发送旧快照，原话留在输入框',()=>{const store=storage(),a=boot(store),b=boot(store);a.say('不通过：补充边界');b.say('确认概要 v1');assert.equal(b.state().current,2);assert.equal(b.state().revisions[1].review_status,'pending');assert.equal(b.elements.input.value,'确认概要 v1');assert.match(b.elements.notice.textContent,/另一标签页/);});
test('损坏记录不静默覆盖，重置只写原型命名空间',()=>{const store=storage();store.setItem(KEY,'{broken');store.setItem('unrelated','keep');const b=boot(store);assert.equal(b.elements.approve.disabled,true);assert.equal(store.getItem(KEY),'{broken');b.click('reset');b.click('confirm-reset');assert.equal(b.state().current,1);assert.equal(store.getItem('unrelated'),'keep');});
test('管理员文本以textContent显示，不创建HTML节点',()=>{const b=boot();b.say('<img src=x onerror=alert(1)>');assert.match(b.elements.messages.textContent,/<img src=x/);assert.equal(b.state().messages[1].text,'<img src=x onerror=alert(1)>');});
console.log(`${count} 项页面绑定与保存交互检查通过（DOM 适配器，非浏览器实测）`);
