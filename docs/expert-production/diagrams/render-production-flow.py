from pathlib import Path
from html import escape
root=Path(__file__).parent
W,H=1680,2840
lines=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" data-generator="fireworks-tech-graph" data-quality-profile="standard">', '<style>text{font-family:"PingFang SC","Microsoft YaHei",sans-serif;fill:#172033} .title{font-weight:600}</style>', '<defs>']
colors={'main':'#365f91','loop':'#8261a8','data':'#45866a'}
for name,c in colors.items():lines.append(f'<marker id="arrow-{name}" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0 0L10 4L0 8Z" fill="{c}"/></marker>')
lines+=['</defs>','<rect width="1680" height="2840" fill="#ffffff" data-graph-role="background"/>']
def text(x,y,s,size=24,color='#172033',anchor='middle',weight=400):
 lines.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" text-anchor="{anchor}" font-weight="{weight}">{escape(s)}</text>')
text(840,65,'爱聊 · 专家生产全流程',38,weight=600)
text(840,109,'管理员搭建流程｜依据当前实现绘制｜2026-10-12',23,'#667085')
for y,h,label,fill,stroke in [(290,330,'接入与保存','#edf5ff','#7d9cbc'),(680,650,'学习与生成','#f0f7ed','#8baa78'),(1380,650,'人工审阅','#f5f0fb','#a08db8'),(2070,560,'确认与完成','#fff8e5','#cdb573')]:
 lines.append(f'<rect x="30" y="{y}" width="55" height="{h}" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1.5" data-graph-role="background"/>')
 for i,ch in enumerate(label):text(57,y+h/2-(len(label)-1)*18+i*36,ch,24)
nodes={}
def node(id,x,y,w,h,title,subs=(),fill='#f2f7fc',stroke='#789abd',titleSize=27):
 nodes[id]=(x,y,w,h)
 lines.append(f'<g data-graph-role="node" data-node-id="{id}" data-graph-bounds="{x} {y} {w} {h}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{fill}" stroke="{stroke}" stroke-width="1.6"/>')
 count=len(subs);first=y+h/2-(count*32)/2+9;text(x+w/2,first,title,titleSize,weight=600)
 for i,s in enumerate(subs):text(x+w/2,first+35+i*32,s,21,'#425166')
 lines.append('</g>')
def diamond(id,x,y,w,h,title):
 nodes[id]=(x,y,w,h);lines.append(f'<g data-graph-role="node" data-node-id="{id}" data-graph-bounds="{x} {y} {w} {h}"><path d="M{x+w/2} {y}L{x+w} {y+h/2}L{x+w/2} {y+h}L{x} {y+h/2}Z" fill="#fff8df" stroke="#bca965" stroke-width="1.6"/>');text(x+w/2,y+h/2+9,title,25,weight=600);lines.append('</g>')
# 先写节点布局，连接线插在节点层之前。
edge_index=len(lines)
node('start',600,150,480,88,'管理员选择资料与版本',('上传资料需先完成解析',),'#f4f0fa','#9682b0')
node('api',600,310,480,106,'生产接口接收请求',('LabHttp → ProductionHttp','Host / CSRF 校验；请求身份校验'))
node('persist',600,490,480,110,'先保存，再执行',('构建、资料版本、成果占位和任务入库','初始状态：待生成 / 待确认'))
node('learn',600,700,480,142,'异步预学习',('ProductionJobQueue → prelearn','逐批提炼方法、摘要和原文来源','每批校验并保存；恢复时复用已完成批次'),'#eef7eb','#8aac79')
node('generate',600,950,480,142,'生成当前待审稿',('ProductionContext + ModelCalls','使用已保存学习成果和已确认上游','按阶段生成完整内容与专家指令'),'#eef7eb','#8aac79')
node('validate',600,1170,480,150,'校验与封存',('ProductionRules / ProductionCitations','原文句段 → 精确引用；字段与依赖校验','失败不放行；保留记录供管理员重试'),'#eef7eb','#8aac79')
node('display',600,1400,480,120,'展示完整稿与原文',('当前版本、职责、边界、完整提示词','可查看来源、修订差异和历史记录'),'#f5effa','#a18bb7')
node('intent',600,1610,480,142,'解析管理员审阅意见',('按钮：使用明确动作','聊天：搭建 Master 识别意图、补全指代','保留原话；条件、否定和引用不能变批准'),'#f5effa','#a18bb7')
diamond('decision',630,1840,420,160,'这条意见如何处理？')
node('discuss',160,1830,340,180,'讨论或需要澄清',('解释原文、一般提问','条件认可 / 对象不明 → 追问','当前阶段与确认状态不变'),'#f5effa','#a18bb7',25)
node('revise',1200,1830,350,180,'不通过或要求修改',('无原因：追问具体原因','原因明确：创建未确认的新修订','保留旧稿；必要时标记下游失效'),'#fff5e4','#cab16b',25)
node('approve',600,2090,480,120,'程序校验后记录确认',('版本、内容指纹、完整范围与展示身份','必要澄清已解决；确认当前完整稿'),'#fff7e6','#c4aa69')
diamond('more',650,2300,380,140,'还有下一项待审？')
node('done',600,2530,480,120,'团队生产完成',('最终成员快照与流程全部确认','build = completed；关联团队完成版本'),'#eef7eb','#8aac79')
node('order',160,310,340,500,'逐项审阅顺序',('① 整本书概要','② 1–6 位专业子专家逐位审阅','③ 兜底专家','④ 只负责路由的主专家','⑤ 关键词规则','⑥ 问答示例与路由配置','⑦ 团队完整流程和成员清单','每位专家均有独立完整提示词'),'#fbfcfe','#c5ced8',26)
node('database',1200,490,350,180,'PostgreSQL',('构建 / 修订 / 引用 / 审核','聊天原话 / 澄清 / 任务 / 事件','作为持久事实与恢复依据'),'#edf7f1','#85a991',27)
node('cluster',1200,720,350,180,'任务与集群约束',('PG 行锁、SKIP LOCKED、任务租约','Redis：模型调用共享额度','失租、取消或过期结果不可提交'),'#edf7f1','#85a991',25)
node('browser',1200,1400,350,180,'浏览器同步状态',('每 2.5 秒查询增量事件','有变化再读取构建快照','当前为轮询，不是 SSE'),'#f2f7fc','#789abd',25)
edge_lines=[]
def edge(source,target,points,kind='main',label=None,lpos=None):
 d='M'+' L'.join(f'{x} {y}' for x,y in points);edge_lines.append(f'<path data-graph-role="edge" data-source="{source}" data-target="{target}" d="{d}" fill="none" stroke="{colors[kind]}" stroke-width="2.2" marker-end="url(#arrow-{kind})"/>')
 if label and lpos:
  x,y=lpos;edge_lines.append(f'<text x="{x}" y="{y}" font-size="21" fill="{colors[kind]}" text-anchor="middle">{escape(label)}</text>')
for a,b in [('start','api'),('api','persist'),('persist','learn'),('learn','generate'),('generate','validate'),('validate','display'),('display','intent'),('intent','decision'),('decision','approve'),('approve','more'),('more','done')]:
 x,y,w,h=nodes[a];xx,yy,ww,hh=nodes[b];edge(a,b,[(x+w/2,y+h),(xx+ww/2,yy)])
edge('persist','database',[(1080,545),(1200,545)],'data')
edge('cluster','learn',[(1200,785),(1080,785)],'data')
edge('validate','browser',[(1080,1250),(1140,1250),(1140,1460),(1200,1460)],'data')
edge('browser','display',[(1200,1500),(1080,1500)],'data')
edge('decision','discuss',[(630,1920),(500,1920)],'loop')
edge('decision','revise',[(1050,1920),(1200,1920)],'loop')
edge('discuss','intent',[(160,1920),(130,1920),(130,1680),(600,1680)],'loop')
edge('revise','generate',[(1550,1920),(1590,1920),(1590,1020),(1080,1020)],'loop','原因完整后重做当前稿',(1380,1003))
edge('more','generate',[(650,2370),(110,2370),(110,1020),(600,1020)],'loop','是：生成下一份',(352,2350))
# 主流程上的分支标签保持在线侧，不覆盖连接线。
lines[edge_index:edge_index]=edge_lines
text(914,2050,'明确完整确认',21,'#365f91',anchor='start')
text(914,2490,'无：最终审核通过',21,'#365f91',anchor='start')
text(840,2710,'L1 关键词、L2 向量问答、L3 主专家路由：本流程生成并审核配置，尚未执行用户侧路由。',23,'#596579')
text(840,2753,'共享原始文件按资料版本保存；所有修改与确认都有对应修订，讨论不等于批准。',22,'#596579')
text(840,2803,'蓝线：主流程　　紫线：审阅反馈 / 重生成　　绿线：存储、额度与状态同步',21,'#596579')
lines.append('</svg>');(root/'expert-production-flow.svg').write_text('\n'.join(lines),encoding='utf-8')
print(root/'expert-production-flow.svg')
