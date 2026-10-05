/* Offline interaction model. No book, network, model or production state access. */
(function (root) {
  'use strict';
  const OBJECT = 'demo-book-summary';
  const BASE = '表达应围绕一个中心结论展开，再用分组后的理由与事实逐层支持。先想清楚要回答什么问题，再检查同一层的观点是否属于相同类别、是否有清楚的逻辑顺序。\n\n构思时，可以从材料归纳出观点；呈现时，可以从核心信息向下展开。结构既帮助读者理解，也帮助表达者发现论证的空缺。\n\n这里仅用概括性文字演示整本书概要的审阅方式，未读取、解析或核对任何真实书籍；不构成完整或已验证的全书总结。';
  const LIMIT = '适用边界（固定演示补充）：结构清晰不等于事实正确；材料不足时应保留不确定性。整理复杂问题时，先核查证据和适用情境，再组织表达。此段是演示编写内容，未经原书核验。';
  const clone = s => JSON.parse(JSON.stringify(s));
  function digest(text) { let h=2166136261; for (const c of text) {h ^= c.codePointAt(0); h=Math.imul(h,16777619);} return 'demo-'+(h>>>0).toString(16).padStart(8,'0'); }
  function revision(n, body, reason='') {return {n,body,reason,digest:digest(body),generation_status:'succeeded',review_status:'pending',sealed:true,adminNotes:reason?[reason]:[]};}
  function seed() { return {schema:1,seq:0,phase:'summary',status:'active',current:1,focus:1,revisions:[revision(1,BASE)],messages:[{role:'master',text:'预学习结果已载入（演示）。请审阅左侧概要 v1。你可以提问、否决并说明原因，或明确输入“确认概要 v1”。本页不会生成真实专家。',version:1}],reviews:[],clarifications:[],operations:{},lastResult:{code:'READY'}}; }
  function snapshot(s) { const r=s.revisions.find(x=>x.n===s.focus); return {object:OBJECT,version:r.n,digest:r.digest,scope:'summary.full'}; }
  function focus(s,n) {const out=clone(s); if(out.revisions.some(r=>r.n===n)){out.focus=n;out.seq++;} return out;}
  function isValid(s) { return !!(s && s.schema===1 && Number.isInteger(s.seq) && Array.isArray(s.revisions) && s.revisions.length && s.revisions.every(r=>Number.isInteger(r.n)&&typeof r.body==='string'&&r.digest===digest(r.body)&&['pending','needs_reason','changes_requested','completed'].includes(r.review_status)) && s.revisions.some(r=>r.n===s.current) && s.revisions.some(r=>r.n===s.focus) && Array.isArray(s.messages) && s.messages.every(m=>typeof m.text==='string') && Array.isArray(s.clarifications) && Array.isArray(s.reviews) && s.operations && ['summary','specialists'].includes(s.phase)); }
  function send(input, request) {
    const s=clone(input), text=request.text, t=text.trim().replace(/[。！!]$/u,''), snap=request.snapshot;
    if (Object.hasOwn(s.operations,request.id)) {
      const prev=s.operations[request.id]; const sameSnapshot=prev.snapshot && ['object','version','digest','scope'].every(k=>prev.snapshot[k]===snap[k]); s.lastResult=prev.text===text && sameSnapshot ? {code:'REPLAY',message:'同一请求已处理，未重复写入。'} : {code:'IDEMPOTENCY_CONFLICT',message:'请求身份已被另一条消息使用。'}; return s;
    }
    s.seq++; s.messages.push({role:'admin',text,version:snap.version,snapshot:clone(snap),requestId:request.id});
    function reply(code,message) {s.lastResult={code,message};s.messages.push({role:'master',text:message,version:s.current});s.operations[request.id]={text,code,snapshot:clone(snap)};return s;}
    const approval=/^确认概要\s*[vV](\d+)$/u.exec(t);
    const denial=/^(?:不通过|否决概要(?:\s*[vV](\d+))?)(?:[：:]\s*([\s\S]+))?$/u.exec(t);
    const reason=/^补充原因[：:]\s*([\s\S]+)$/u.exec(t);
    if (approval || denial || reason) {
      const target=approval?Number(approval[1]):denial&&denial[1]?Number(denial[1]):snap.version;
      if (target!==s.current || snap.version!==s.current) return reply('VERSION_CONFLICT',`版本冲突：你提交的是概要 v${target}，当前待审版本是 v${s.current}。旧稿保持不变，请查看当前稿后重新表达意见。`);
      const r=s.revisions.find(x=>x.n===s.current);
      if(snap.object!==OBJECT || snap.scope!=='summary.full' || snap.digest!==r.digest) return reply('PRESENTATION_CONFLICT','展示对象、确认范围或内容摘要不匹配，未执行审核。请重新查看完整当前概要。');
      if(s.phase!=='summary') return reply('STAGE_CLOSED','概要审核已经完成。现在停在待审子专家入口，后续阶段未实现。');
      if(approval) {
        if(s.clarifications.some(c=>c.status==='open')) return reply('UNRESOLVED','还有待补充的否决原因。请先说明原因并审阅新稿，本次没有确认。');
        r.review_status='completed'; s.phase='specialists';
        s.reviews.push({version:r.n,scope:'summary.full',decision:'approve',digest:r.digest,original:text,requestId:request.id});
        return reply('APPROVED',`已记录：管理员确认概要 v${r.n} 的完整内容（${r.digest}）。概要阶段完成，停在待审子专家入口；没有生成子专家，也没有确认团队。`);
      }
      let why=denial?denial[2]:reason[1];
      if(reason && !s.clarifications.some(c=>c.status==='open'&&c.version===r.n)) return reply('NO_PENDING_REASON','当前没有待补原因的问题。若要修改，请明确输入“不通过：具体原因”。');
      if(!why || !why.trim()) {
        if(r.review_status!=='needs_reason') {
          r.review_status='needs_reason';
          s.reviews.push({version:r.n,scope:'summary.full',decision:'reject',reason:null,digest:r.digest,original:text,requestId:request.id});
          s.clarifications.push({version:r.n,question:'你认为概要哪里不准确或缺少什么？请用“补充原因：……”说明。',status:'open',answer:null});
        }
        return reply('NEEDS_REASON','已记录否决，概要未通过。你认为哪里不准确或缺少什么？请用“补充原因：……”说明；提问仍可继续。');
      }
      why=why.trim(); r.review_status='changes_requested';
      const pending=s.clarifications.find(c=>c.version===r.n&&c.status==='open');
      if(pending) {pending.status='resolved';pending.answer=why;pending.resolutionVersion=r.n+1;}
      s.reviews.push({version:r.n,scope:'summary.full',decision:'reject',reason:why,digest:r.digest,original:text,requestId:request.id});
      const body=BASE+'\n\n'+LIMIT+'\n\n管理员自定义修订意见（不是原书观点）：\n'+why;
      const next=revision(r.n+1,body,why); s.revisions.push(next);s.current=next.n;s.focus=next.n;
      return reply('REVISED',`已保存原因并展示概要 v${next.n}，仍需重新确认。演示改稿采用固定“适用边界”补充模板，并单独保留你的自定义意见；没有调用模型理解或执行任意修改。旧版正文、否决和原因都已保留。`);
    }
    if(s.phase==='specialists') return reply('NOT_IMPLEMENTED','当前停在待审子专家入口。子专家、兜底、主专家、生产配置及整体确认尚未实现，本原型不能继续生成。');
    if (/^(?:可以|好|好的|通过|确认|继续)$/u.test(t)) return reply('CLARIFY_SCOPE',`这句话的对象或范围不够明确，未批准概要。若确认当前完整内容，请先查看概要 v${s.current}，再明确输入“确认概要 v${s.current}”。`);
    const answer = /来源|页码|原文/u.test(t) ? '来源栏是占位，尚未读取原书，因此无法提供真实页码、引文或覆盖证明。概要只能用于审阅交互演示。' : /逻辑|分组|为什么/u.test(t) ? '演示解释：把支持同一观点的理由放在一起，能帮助检查它们是否回答同一个问题。这是固定示例回答，不是依据真实书籍检索或模型生成的结论。' : '已保留你的原话。本页仅识别少量明确的演示句式；这条消息作为讨论保留，不会批准概要或自动执行修改。可以用下方示例体验流程。';
    return reply('DISCUSSION',answer+'\n审核位置保持不变。');
  }
  root.ReviewDemo={seed,snapshot,focus,send,isValid};
})(globalThis);
