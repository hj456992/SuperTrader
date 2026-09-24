import { build } from 'esbuild';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const base = process.env.DSH_JAVA_HOME || '/Users/hou/Documents/Codex/projects/dsh-java';
const packages = process.env.DSH_PACKAGES || '/Users/hou/Documents/Codex/DSH/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai';
const dist = path.join(here, 'dist');
await fs.mkdir(dist, {recursive:true});
await fs.cp(path.join(here,'public'),dist,{recursive:true});
await fs.copyFile(path.join(base,'frontend/src/bootstrap.js'),path.join(dist,'vendor/bootstrap.js'));
await fs.copyFile(path.join(base,'frontend/vendor/plugins/dsh-client-modules/client.js'),path.join(dist,'vendor/client-modules.js'));
await fs.copyFile(path.join(base,'frontend/vendor/plugins/dsh-client-modules/LICENSE'),path.join(dist,'vendor/client-modules.LICENSE'));
await build({entryPoints:[path.join(here,'src/host.js')],bundle:true,format:'esm',outfile:path.join(dist,'host.js'),alias:{'@deepseek-ai/cordis':path.join(packages,'cordis/lib/index.js')}});
for (const id of ['garden-ui','ranch-ui']) {
  const plugin = await build({entryPoints:[path.join(here,`src/${id}.js`)],bundle:true,format:'cjs',write:false});
  await fs.writeFile(path.join(dist,`${id}.js`),`window.__ModuleLoader__.load({id:'${id}',factory(require){var module={exports:{}};var exports=module.exports;\n${plugin.outputFiles[0].text}\nreturn module.exports;}});`);
}
for (const name of ['cordis','cosmokit']) {
  await fs.copyFile(path.join(packages,name,'LICENSE'),path.join(dist,'vendor',name+'.LICENSE'));
}
console.log('garden-ui built: original module loader + Cordis plugin lifecycle');
