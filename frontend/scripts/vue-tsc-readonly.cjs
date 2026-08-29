'use strict';

const SUPPORTED_VERSIONS = Object.freeze({
  vueTsc: '2.2.12',
  languageCore: '2.2.12',
});

function assertSupportedVersions(vueTscVersion, languageCoreVersion) {
  if (vueTscVersion !== SUPPORTED_VERSIONS.vueTsc
      || languageCoreVersion !== SUPPORTED_VERSIONS.languageCore) {
    throw new Error(
      'Unsupported Vue typecheck runtime: '
      + `vue-tsc=${vueTscVersion}, `
      + `@vue/language-core=${languageCoreVersion}; `
      + `expected ${SUPPORTED_VERSIONS.vueTsc}/${SUPPORTED_VERSIONS.languageCore}`,
    );
  }
}

function createReadOnlyParseConfigHost(parseConfigHost) {
  if (!parseConfigHost
      || typeof parseConfigHost !== 'object'
      || typeof parseConfigHost.readFile !== 'function'
      || typeof parseConfigHost.fileExists !== 'function'
      || typeof parseConfigHost.writeFile !== 'function') {
    throw new Error('Unsupported vue-tsc parse Host shape');
  }

  const readOnlyHost = Object.create(parseConfigHost);
  Object.defineProperty(readOnlyHost, 'writeFile', {
    value: undefined,
    enumerable: true,
    writable: false,
    configurable: false,
  });
  return readOnlyHost;
}

function loadSupportedRuntime() {
  const vueTscPackage = require('vue-tsc/package.json');
  const languageCorePackage = require('@vue/language-core/package.json');
  assertSupportedVersions(vueTscPackage.version, languageCorePackage.version);

  const languageCoreTs = require('@vue/language-core/lib/utils/ts.js');
  const vueLanguageCore = require('@vue/language-core');
  const vueTsc = require('vue-tsc');
  if (typeof languageCoreTs.createParsedCommandLine !== 'function'
      || typeof vueLanguageCore.createParsedCommandLine !== 'function'
      || typeof vueTsc.run !== 'function') {
    throw new Error('Unsupported Vue typecheck API shape');
  }
  if (vueLanguageCore.createParsedCommandLine
      !== languageCoreTs.createParsedCommandLine) {
    throw new Error('Vue Language Core export no longer exposes the parsed-config implementation');
  }

  return {
    languageCoreTs,
    vueLanguageCore,
    vueTsc,
    versions: {
      vueTsc: vueTscPackage.version,
      languageCore: languageCorePackage.version,
    },
  };
}

function patchParsedCommandLine(languageCoreTs, vueLanguageCore) {
  const original = languageCoreTs.createParsedCommandLine;
  if (typeof original !== 'function') {
    throw new Error('Missing Vue parsed-config implementation');
  }

  function createParsedCommandLineWithReadOnlyHost(
    typescript,
    parseConfigHost,
    tsConfigPath,
    skipGlobalTypesSetup,
  ) {
    return original.call(
      this,
      typescript,
      createReadOnlyParseConfigHost(parseConfigHost),
      tsConfigPath,
      skipGlobalTypesSetup,
    );
  }

  languageCoreTs.createParsedCommandLine
    = createParsedCommandLineWithReadOnlyHost;
  if (vueLanguageCore.createParsedCommandLine
      !== createParsedCommandLineWithReadOnlyHost) {
    languageCoreTs.createParsedCommandLine = original;
    throw new Error('Read-only parsed-config patch is not visible to vue-tsc');
  }

  return function restoreParsedCommandLine() {
    languageCoreTs.createParsedCommandLine = original;
    if (vueLanguageCore.createParsedCommandLine !== original) {
      throw new Error('Failed to restore Vue parsed-config implementation');
    }
  };
}

function runReadonlyVueTscCli() {
  const runtime = loadSupportedRuntime();
  patchParsedCommandLine(
    runtime.languageCoreTs,
    runtime.vueLanguageCore,
  );
  // vue-tsc/TypeScript owns process termination. This launcher is
  // intentionally one-shot instead of a reusable in-process API.
  runtime.vueTsc.run();
}

if (require.main === module) {
  try {
    runReadonlyVueTscCli();
  } catch (error) {
    const detail = error instanceof Error
      ? (error.stack || error.message)
      : String(error);
    console.error(`[vue-tsc-readonly] ${detail}`);
    process.exitCode = 1;
  }
}

module.exports = {
  SUPPORTED_VERSIONS,
  assertSupportedVersions,
  createReadOnlyParseConfigHost,
  loadSupportedRuntime,
  patchParsedCommandLine,
};
