// In-memory DOM boundary for component tests; no browser or production network access.
class Node {
 constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.listeners={};this.attributes={};this.style={};this.dataset={};this._text='';this.value='';this.hidden=false;this.disabled=false;this.scrollHeight=0;this.scrollTop=0;this.clientHeight=0;this.className='';this.classList={toggle:(c,on)=>{const set=new Set(this.className.split(' ').filter(Boolean));const yes=on===undefined?!set.has(c):on;if(yes)set.add(c);else set.delete(c);this.className=[...set].join(' ');},add:c=>this.classList.toggle(c,true),remove:c=>this.classList.toggle(c,false)};}
 set textContent(v){this._text=String(v??'');this.children=[];}get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
 append(...els){for(const el of els){el.parentNode=this;this.children.push(el);}}replaceChildren(...els){this._text='';this.children=[];this.append(...els);}
 setAttribute(k,v){this.attributes[k]=v;}removeAttribute(k){delete this.attributes[k];}addEventListener(n,f){(this.listeners[n]??=[]).push(f);}
 async fire(n){if(n==='click'&&this.disabled)return;for(const f of this.listeners[n]||[])await f({target:this,preventDefault(){}});if(this['on'+n])await this['on'+n]({target:this,preventDefault(){}});}
 focus(){}scrollIntoView(){}showModal(){this.open=true;}close(){this.open=false;}
 querySelector(sel){return this.querySelectorAll(sel)[0]||null;}querySelectorAll(sel){const result=[];function visit(n){for(const c of n.children){if(sel[0]==='#'?c.id===sel.slice(1):c.tagName?.toLowerCase()===sel)result.push(c);visit(c);}}visit(this);return result;}
}
function document(){const body=new Node('body');return {body,createElement:t=>new Node(t),getElementById:id=>body.querySelector('#'+id)};}
module.exports={Node,document};
