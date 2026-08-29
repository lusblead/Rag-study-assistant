'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const typescript = require('typescript');

const {
  SUPPORTED_VERSIONS,
  assertSupportedVersions,
  createReadOnlyParseConfigHost,
  loadSupportedRuntime,
  patchParsedCommandLine,
} = require('./vue-tsc-readonly.cjs');

const frontendRoot = path.resolve(__dirname, '..');
const wrapperPath = path.join(__dirname, 'vue-tsc-readonly.cjs');
const globalTypesRoot = path.join(
  frontendRoot,
  'node_modules',
  '.vue-global-types',
);
const blockedGlobalTypesExitCode = 86;

function snapshotFiles(root) {
  if (!fs.existsSync(root)) {
    return [];
  }
  const records = [];
  const visit = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const absolute = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        visit(absolute);
      } else if (entry.isFile()) {
        const stat = fs.statSync(absolute, { bigint: true });
        records.push({
          path: path.relative(root, absolute).replaceAll('\\', '/'),
          size: stat.size,
          mtimeNs: stat.mtimeNs,
          sha256: crypto.createHash('sha256')
            .update(fs.readFileSync(absolute))
            .digest('hex'),
        });
      }
    }
  };
  visit(root);
  return records.sort((left, right) => left.path.localeCompare(right.path));
}

function removeVerifiedFixture(fixtureRoot, guardRoot) {
  const relative = path.relative(guardRoot, fixtureRoot);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error(`Refusing to remove unverified fixture path: ${fixtureRoot}`);
  }
  fs.rmSync(fixtureRoot, { recursive: true, force: true });
  if (fs.existsSync(guardRoot) && fs.readdirSync(guardRoot).length === 0) {
    fs.rmdirSync(guardRoot);
  }
}

function writeDenyGlobalTypesPreload(preloadPath) {
  fs.writeFileSync(preloadPath, [
    "'use strict';",
    "const fs = require('node:fs');",
    "const path = require('node:path');",
    "const configuredRoot = process.env.VUE_TSC_FORBIDDEN_WRITE_ROOT;",
    "if (!configuredRoot) {",
    "  throw new Error('Missing VUE_TSC_FORBIDDEN_WRITE_ROOT');",
    "}",
    "const forbiddenRoot = path.resolve(configuredRoot);",
    "let blockedMutation = false;",
    "function isProtected(target) {",
    "  if (typeof target !== 'string' && !Buffer.isBuffer(target)) {",
    "    return false;",
    "  }",
    "  const relative = path.relative(forbiddenRoot, path.resolve(String(target)));",
    "  return relative === ''",
    "    || (!relative.startsWith(`..${path.sep}`)",
    "      && relative !== '..'",
    "      && !path.isAbsolute(relative));",
    "}",
    "function rejectMutation(operation, target) {",
    "  blockedMutation = true;",
    "  throw new Error(`[vue-tsc-write-guard] blocked ${operation}: ${target}`);",
    "}",
    "const writeFlagMask = fs.constants.O_WRONLY",
    "  | fs.constants.O_RDWR",
    "  | fs.constants.O_APPEND",
    "  | fs.constants.O_CREAT",
    "  | fs.constants.O_TRUNC;",
    "function isWriteFlag(flags) {",
    "  return typeof flags === 'number'",
    "    ? (flags & writeFlagMask) !== 0",
    "    : /[wax+]/.test(String(flags));",
    "}",
    "const originalOpenSync = fs.openSync;",
    "fs.openSync = function guardedOpenSync(target, flags, ...rest) {",
    "  if (isProtected(target) && isWriteFlag(flags)) {",
    "    rejectMutation('openSync', target);",
    "  }",
    "  return Reflect.apply(originalOpenSync, this, [target, flags, ...rest]);",
    "};",
    "const originalMkdirSync = fs.mkdirSync;",
    "fs.mkdirSync = function guardedMkdirSync(target, ...rest) {",
    "  if (isProtected(target)) {",
    "    rejectMutation('mkdirSync', target);",
    "  }",
    "  return Reflect.apply(originalMkdirSync, this, [target, ...rest]);",
    "};",
    "const originalExit = process.exit.bind(process);",
    "process.exit = function guardedExit(code) {",
    `  return originalExit(blockedMutation ? ${blockedGlobalTypesExitCode} : code);`,
    "};",
    '',
  ].join('\n'));
}

function readOnlyParseHostHidesWriteFile() {
  const writes = [];
  const host = {
    readFile: () => 'content',
    fileExists: () => true,
    writeFile: (fileName) => writes.push(fileName),
  };
  const readOnlyHost = createReadOnlyParseConfigHost(host);

  assert.equal(readOnlyHost.readFile('fixture.ts'), 'content');
  assert.equal(readOnlyHost.fileExists('fixture.ts'), true);
  assert.equal(readOnlyHost.writeFile, undefined);
  assert.deepEqual(writes, []);
}

function supportedRuntimeContractIsAvailable() {
  const runtime = loadSupportedRuntime();
  assert.deepEqual(runtime.versions, {
    vueTsc: SUPPORTED_VERSIONS.vueTsc,
    languageCore: SUPPORTED_VERSIONS.languageCore,
  });
}

function supportedRuntimeMismatchFailsClosed() {
  assert.throws(
    () => assertSupportedVersions('future', SUPPORTED_VERSIONS.languageCore),
    /Unsupported Vue typecheck runtime/,
  );
  assert.throws(
    () => assertSupportedVersions(SUPPORTED_VERSIONS.vueTsc, 'future'),
    /Unsupported Vue typecheck runtime/,
  );
}

function installedParserKeepsGlobalTypesInlineWithoutCallingWriteFile() {
  const runtime = loadSupportedRuntime();
  const writeCalls = [];
  const host = Object.create(typescript.sys);
  Object.defineProperty(host, 'writeFile', {
    value: (fileName) => writeCalls.push(fileName),
    enumerable: true,
    writable: false,
    configurable: false,
  });
  const restore = patchParsedCommandLine(
    runtime.languageCoreTs,
    runtime.vueLanguageCore,
  );
  try {
    const configPath = path.join(frontendRoot, 'tsconfig.json')
      .replaceAll('\\', '/');
    const parsed = runtime.languageCoreTs.createParsedCommandLine(
      typescript,
      host,
      configPath,
    );
    assert.ok(parsed.fileNames.length > 0, '项目配置必须实际解析源码');
    assert.equal(parsed.vueOptions.__setupedGlobalTypes, undefined);
    assert.deepEqual(writeCalls, []);
  } finally {
    restore();
  }
}

function templateTypeErrorStillFailsReadonlyVueTsc() {
  const distRoot = path.join(frontendRoot, 'dist');
  const guardRoot = path.join(distRoot, 'vue-tsc-readonly-guard');
  assert.equal(path.dirname(guardRoot), distRoot);
  fs.mkdirSync(guardRoot, { recursive: true });
  const fixtureRoot = fs.mkdtempSync(path.join(guardRoot, 'case-'));
  const globalTypesBefore = snapshotFiles(globalTypesRoot);
  let globalTypesAfter;
  let result;
  try {
    const preloadPath = path.join(
      fixtureRoot,
      'deny-global-types-write.cjs',
    );
    writeDenyGlobalTypesPreload(preloadPath);
    const guardProbeRoot = path.join(fixtureRoot, 'guard-probe');
    const guardProbe = spawnSync(
      process.execPath,
      [
        '--require',
        preloadPath,
        '-e',
        [
          "const fs = require('node:fs');",
          "const path = require('node:path');",
          "const target = path.join(",
          "  process.env.VUE_TSC_FORBIDDEN_WRITE_ROOT,",
          "  'blocked.d.ts',",
          ");",
          "try {",
          "  fs.openSync(target, 'w');",
          "} catch {",
          "  process.exit(0);",
          "}",
          "process.exit(1);",
        ].join('\n'),
      ],
      {
        cwd: frontendRoot,
        encoding: 'utf8',
        env: {
          ...process.env,
          VUE_TSC_FORBIDDEN_WRITE_ROOT: guardProbeRoot,
        },
        timeout: 30_000,
      },
    );
    assert.equal(guardProbe.error, undefined);
    assert.equal(
      guardProbe.status,
      blockedGlobalTypesExitCode,
      'preload 必须在写入前阻止受保护路径并保留专用退出码',
    );
    assert.equal(fs.existsSync(guardProbeRoot), false);

    const tsconfigPath = path.join(fixtureRoot, 'tsconfig.json');
    fs.writeFileSync(tsconfigPath, JSON.stringify({
      extends: path.join(frontendRoot, 'tsconfig.json'),
      include: ['Bad.vue'],
    }, null, 2));
    fs.writeFileSync(
      path.join(fixtureRoot, 'Bad.vue'),
      [
        '<template><div>{{ message.notARealMethod() }}</div></template>',
        '<script setup lang="ts">',
        "const message = 'not-a-number';",
        '</script>',
        '',
      ].join('\n'),
    );

    result = spawnSync(
      process.execPath,
      [
        '--require',
        preloadPath,
        wrapperPath,
        '--noEmit',
        '-p',
        tsconfigPath,
      ],
      {
        cwd: frontendRoot,
        encoding: 'utf8',
        env: {
          ...process.env,
          VUE_TSC_FORBIDDEN_WRITE_ROOT: globalTypesRoot,
        },
        timeout: 120_000,
      },
    );
  } finally {
    try {
      globalTypesAfter = snapshotFiles(globalTypesRoot);
    } finally {
      removeVerifiedFixture(fixtureRoot, guardRoot);
    }
  }
  assert.ok(result, 'vue-tsc 子进程必须返回结果');
  assert.equal(result.error, undefined);
  assert.notEqual(
    result.status,
    blockedGlobalTypesExitCode,
    'vue-tsc 不得尝试写入 .vue-global-types',
  );
  assert.deepEqual(globalTypesAfter, globalTypesBefore);
  assert.notEqual(result.status, 0, '模板类型错误必须使 vue-tsc 非零退出');
  const diagnostics = `${result.stdout}\n${result.stderr}`;
  assert.match(diagnostics, /TS2339/);
  assert.match(diagnostics, /notARealMethod/);
}

test('read-only parse Host hides writeFile', readOnlyParseHostHidesWriteFile);
test('supported runtime contract is available', supportedRuntimeContractIsAvailable);
test('supported runtime mismatch fails closed', supportedRuntimeMismatchFailsClosed);
test(
  'installed parser keeps global types inline without calling writeFile',
  installedParserKeepsGlobalTypesInlineWithoutCallingWriteFile,
);
test(
  'Vue template type error still fails read-only vue-tsc',
  templateTypeErrorStillFailsReadonlyVueTsc,
);
