import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { JSDOM } from 'jsdom';
import { build } from 'esbuild';
import { parse, compileScript } from '@vue/compiler-sfc';

const dom = new JSDOM('<div id="app"></div>', { url: 'http://localhost/' });
for (const key of ['window','document','Element','HTMLElement','SVGElement','Node','Event']) globalThis[key] = dom.window[key];
dom.window.HTMLElement.prototype.scrollTo = () => {};
const require = createRequire(import.meta.url);
const vue = await import('vue');
const output = await build({
  entryPoints: ['src/pages/ChatPage.vue'], bundle: true, write: false, format: 'esm', platform: 'node',
  plugins: [{ name: 'vue-lifecycle-test', setup(builder) {
    builder.onResolve({ filter: /^vue$/ }, () => ({ path: pathToFileURL(require.resolve('vue')).href, external: true }));
    builder.onResolve({ filter: /\/api$/ }, () => ({ path: 'api', namespace: 'test' }));
    builder.onResolve({ filter: /\/markdown$/ }, () => ({ path: 'markdown', namespace: 'test' }));
    builder.onLoad({ filter: /.*/, namespace: 'test' }, args => ({ contents: args.path === 'api'
      ? 'export const api = globalThis.__materialApi; export const streamChat = globalThis.__materialStream;'
      : 'export const renderMarkdown = value => value;' }));
    builder.onLoad({ filter: /\.vue$/ }, async args => {
      const { descriptor } = parse(await readFile(args.path,'utf8'), { filename: args.path });
      const compiled = compileScript(descriptor, { id: args.path, inlineTemplate: true });
      return { contents: compiled.content, loader: 'ts', resolveDir: path.dirname(args.path) };
    });
  }}]
});
let status;
let referenceFailure;
let referenceReads = [];
const saved = { references: [{ chunkId: 1, documentId: 10, documentVersionId: 100, title: '旧讲义', content: 'cached text' }] };
globalThis.__materialApi = {
  listSessions: async () => [{ id: 1, courseId: 1, title: '原对话' }],
  listMessages: async () => [{ id: 1, role: 'assistant', content: '原回答', evidenceJson: JSON.stringify(saved) }],
  sessionMaterials: async () => status,
  sessionReference: async (...args) => { referenceReads.push(args); if(referenceFailure) throw Error(referenceFailure); return { content: 'checked old evidence' }; }
};
globalThis.__materialStream = async () => {};
const component = (await import('data:text/javascript;base64,' + Buffer.from(output.outputFiles[0].text).toString('base64'))).default;
let app;
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 15)); await vue.nextTick(); };
async function mount(nextStatus) {
  app?.unmount();
  status = { sessionId:1, state:'READY', updateAvailable:false, versions:[{
    documentId:10, documentVersionId:100, versionNo:1, documentName:'课程讲义'
  }], ...nextStatus };
  referenceFailure = null; referenceReads = [];
  app = vue.createApp(component, { course: { id:1, name:'测试课程' } });
  app.mount('#app'); await flush();
  [...document.querySelectorAll('button')].find(b => b.textContent.includes('原对话')).click();
  await flush();
}
after(() => { app?.unmount(); dom.window.close(); });

test('restore session displays original version and one persistent update notice', async () => {
  await mount({ updateAvailable:true });
  assert.match(document.body.textContent,/本对话仍使用原版/);
  assert.match(document.body.textContent,/第 1 版（记录 100）/);
  assert.equal(document.querySelectorAll('.material-status').length,1);
  assert.match(document.body.textContent,/原回答/);
});

test('unavailable material blocks submission and explicit new session drops old history', async () => {
  await mount({ state:'UNAVAILABLE', reason:'DOCUMENT_WITHDRAWN' });
  assert.equal(document.querySelector('textarea').disabled,true);
  [...document.querySelectorAll('button')].find(b => b.textContent.includes('使用当前可用资料')).click();
  await flush();
  assert.equal(document.querySelector('textarea').disabled,false);
  assert.doesNotMatch(document.body.textContent,/原回答/);
});

test('restored reference reads the exact bound version and handles deletion visibly', async () => {
  await mount({});
  const detail = document.querySelector('.references details');
  detail.open = true; detail.dispatchEvent(new Event('toggle')); await flush();
  assert.deepEqual(referenceReads[0],[1,1,1,100]);
  assert.match(detail.textContent,/checked old evidence/);
  referenceFailure = '本对话使用的资料版本已不可用';
  detail.dispatchEvent(new Event('toggle')); await flush();
  assert.match(detail.textContent,/资料版本已不可用/);
  assert.doesNotMatch(detail.textContent,/checked old evidence|cached text/);
});

test('missing evidence is distinguished from version withdrawal', async () => {
  await mount({ state:'UNAVAILABLE', reason:'EVIDENCE_MISSING' });
  assert.match(document.body.textContent,/资料证据不完整/);
  assert.equal(document.querySelector('textarea').disabled,true);
});
