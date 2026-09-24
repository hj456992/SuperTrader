import { Context } from '@deepseek-ai/cordis';
// 外壳只装配模块；界面、事件和轮询都属于 garden-ui 插件作用域。
try {
  const id = location.pathname === '/conversations.html' ? 'garden-ui' : 'ranch-ui';
  const boot = {rev:'ranch-1',entries:[{id,url:`/${id}.js`,rev:'1',immediately:true}],batches:[{phase:'application',url:`/${id}.js`,rev:'1',entries:[id]}]};
  const modules = window.__ModuleLoader__.create({boot,staticModules:{}});
  const plugin = await modules.import(id);
  const ctx = new Context();
  const fiber = await ctx.plugin(plugin);
  window.__GardenRuntime__ = {modules,ctx,fiber};
  document.documentElement.dataset.plugin = fiber.status || 'active';
} catch (error) {
  document.querySelector('#app').textContent = '前端插件启动失败：' + error.message;
  console.error(error);
}
