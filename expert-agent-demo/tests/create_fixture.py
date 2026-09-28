"""Original, fictional teaching material used for end-to-end testing."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = Path(__file__).resolve().parents[1]
pdfmetrics.registerFont(TTFont('DemoChinese', '/Library/Fonts/Arial Unicode.ttf'))
path = ROOT / 'evidence/演示资料-职场沟通方法.pdf'
pages = [
('沟通分析：先核实目标与事实', [
 '本文件是为专家实验室制作的原创演示资料，仅用于验证软件流程，不是专业研究。',
 '一、明确请求。面对含糊的工作指令，先确认交付内容、时间、优先级和责任归属。',
 '例如领导说“这个事情你跟一下”，可能指跟踪进度，也可能是接手交付。',
 '应追问：您希望我负责进度协调，还是承担最终交付？需要何时完成？',
 '二、分离事实和猜测。原话是事实，对动机的解释是待验证假设。',
 '“今天之前发我”表达时间要求，不能单凭这一句断定领导对员工不满意。',
 '三、给出可确认的回应。复述理解，再提出一个决定行动所需的关键问题。',
 '适用：任务边界不清、短消息有歧义。边界：事实已经完整时，不应反复追问。']),
('工作量协商：明确取舍与可行方案', [
 '一、列出现有承诺。说明已经承担的任务、截止时间和新增工作所需时间。',
 '二、提出选择。可以接受新增任务并调整原有截止时间，也可以请求减少工作范围。',
 '例如原任务周五交付，新任务需要两天，可以询问：是否将原任务顺延到下周？',
 '三、确认优先级。让有权限的人决定哪项工作优先，不自行承诺同时完成所有事项。',
 '四、准备替代方案。与同事协作、分阶段交付、明确只负责其中一部分。',
 '适用：资源有限但目标可协商。边界：没有掌握工作量时，不应编造精确工期。',
 '回应示例：我可以接这项。目前还在做甲任务，请帮我确认两项的优先级。']),
('分歧处理：表达影响与建立约定', [
 '一、描述具体事件，避免用“你总是”评价人格。先问清不同理解从何而来。',
 '二、表达影响。例如临时变更安排导致准备时间减少，而非推断对方故意刁难。',
 '三、明确共同目标。讨论怎样保证交付，以及各方需要哪些支持。',
 '四、形成可检查约定。约定谁做什么、什么时候同步、发生变化如何告知。',
 '适用：合作分歧、职责交叉。边界：存在明显权力差异时，应考虑表达渠道与时机。',
 '不宜把所有问题归为沟通问题；资源不足、制度约束可能需要调整任务安排。',
 '验证建议：换一种人物关系或时间条件，检查建议是否随背景合理变化。'])]
c = canvas.Canvas(str(path), pagesize=(595,842))
c.setTitle('原创演示资料：职场沟通方法')
for number, (title, lines) in enumerate(pages, 1):
 c.setFillColorRGB(.14,.32,.22); c.setFont('DemoChinese',21); c.drawString(45,770,title)
 c.setFillColorRGB(.22,.26,.23); c.setFont('DemoChinese',12)
 y=725
 for line in lines:
  for start in range(0,len(line),37):
   c.drawString(45,y,line[start:start+37]); y-=24
  y-=10
 c.setFont('DemoChinese',10);c.setFillColorRGB(.45,.5,.43);c.drawString(45,45,'原创虚构演示资料 · 仅用于软件验证');c.drawRightString(550,45,str(number));c.showPage()
c.save()
print(path)
