import assert from "node:assert/strict";
import { before, test } from "node:test";
import { JSDOM } from "jsdom";

let renderMarkdown;

before(async () => {
  const dom = new JSDOM("<!doctype html><html><body></body></html>");
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  ({ renderMarkdown } = await import("./markdown.ts"));
});

test("renders common GFM structures as readable HTML", () => {
  const html = renderMarkdown(`
# 标题

> 重点

| 名称 | 值 |
| --- | --- |
| A | **粗体** |

- [x] 已完成

\`行内代码\`
`);

  assert.match(html, /<h1>标题<\/h1>/);
  assert.match(html, /<blockquote>/);
  assert.match(html, /<table>/);
  assert.match(html, /<strong>粗体<\/strong>/);
  assert.match(html, /type="checkbox"/);
  assert.match(html, /<code>行内代码<\/code>/);
});

test("removes executable HTML before v-html renders it", () => {
  const html = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(2)</script>');

  assert.doesNotMatch(html, /onerror/);
  assert.doesNotMatch(html, /<script/);
});
