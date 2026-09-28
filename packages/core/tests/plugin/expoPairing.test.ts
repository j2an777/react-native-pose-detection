import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';

import { checkExpoMatchesReactNative } from '../../plugin/src/checks';

const roots: string[] = [];
after(() => roots.forEach((root) => rmSync(root, { recursive: true, force: true })));

/** An app root holding only what the check reads: the two manifests and Expo's version list. */
function project(installed: {
  reactNative?: string;
  expo?: string;
  bundledReactNative?: string;
  bundledJson?: string;
}): string {
  const root = mkdtempSync(join(tmpdir(), 'pose-pairing-'));
  roots.push(root);
  writeFileSync(join(root, 'package.json'), '{"name":"app"}');
  const write = (name: string, file: string, contents: string): void => {
    mkdirSync(join(root, 'node_modules', name), { recursive: true });
    writeFileSync(join(root, 'node_modules', name, file), contents);
  };
  if (installed.reactNative !== undefined) {
    write('react-native', 'package.json', JSON.stringify({ version: installed.reactNative }));
  }
  if (installed.expo !== undefined) {
    write('expo', 'package.json', JSON.stringify({ version: installed.expo }));
    const bundled =
      installed.bundledJson ??
      (installed.bundledReactNative === undefined
        ? undefined
        : JSON.stringify({ 'react-native': installed.bundledReactNative }));
    if (bundled !== undefined) write('expo', 'bundledNativeModules.json', bundled);
  }
  return root;
}

test('an SDK built for the installed React Native passes, whatever the patch', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ reactNative: '0.86.2', expo: '57.0.25', bundledReactNative: '0.86.3' }),
  );
  assert.equal(check.status, 'pass');
  assert.equal(check.detail, 'expo 57 with react-native 0.86.2');
});

test('an SDK built for another React Native fails and names the one to install', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ reactNative: '0.85.3', expo: '57.0.25', bundledReactNative: '0.86.3' }),
  );
  assert.equal(check.status, 'fail');
  assert.equal(check.detail, 'expo 57 is built for React Native 0.86, found 0.85.3: npm i expo@56');
});

test('a React Native newer than the table fails, naming the newest pair without inventing an SDK', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ reactNative: '0.87.1', expo: '57.0.25', bundledReactNative: '0.86.3' }),
  );
  assert.equal(check.status, 'fail');
  assert.equal(
    check.detail,
    'expo 57 is built for React Native 0.86, found 0.87.1: install the Expo SDK built for React ' +
      'Native 0.87 if there is one, or use React Native 0.86 with npm i expo@57',
  );
});

test('a missing expo on a React Native newer than the table names the newest pair', async () => {
  const check = await checkExpoMatchesReactNative(project({ reactNative: '0.87.1' }));
  assert.equal(check.status, 'fail');
  assert.match(check.detail, /or use React Native 0\.86 with npm i expo@57$/);
});

test('a React Native older than the package supports fails, even with its own SDK', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ reactNative: '0.83.10', expo: '55.0.31', bundledReactNative: '0.83.10' }),
  );
  assert.equal(check.status, 'fail');
  assert.equal(
    check.detail,
    'react-native 0.83.10 is older than this package supports, which needs React Native 0.85 and ' +
      'Expo SDK 56 or newer',
  );
});

test('an SDK older than the package supports fails, naming the one for this React Native', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ reactNative: '0.85.3', expo: '55.0.31', bundledReactNative: '0.83.10' }),
  );
  assert.equal(check.status, 'fail');
  assert.equal(
    check.detail,
    'expo 55 is older than this package supports, which needs SDK 56 or newer: npm i expo@56',
  );
});

test('a missing expo fails, because it is what links the module', async () => {
  const check = await checkExpoMatchesReactNative(project({ reactNative: '0.85.3' }));
  assert.equal(check.status, 'fail');
  assert.equal(check.detail, 'expo is not installed, and it links this module: npm i expo@56');
});

test('without react-native there is nothing to compare, which is not a failure', async () => {
  const check = await checkExpoMatchesReactNative(
    project({ expo: '57.0.25', bundledReactNative: '0.86.3' }),
  );
  assert.equal(check.status, 'skip');
});

test('an unreadable version list is a skip, never a crash', async () => {
  for (const bundledJson of ['{"react-native": ', '{}', 'null', '{"react-native": 86}']) {
    const check = await checkExpoMatchesReactNative(
      project({ reactNative: '0.86.2', expo: '57.0.25', bundledJson }),
    );
    assert.equal(check.status, 'skip', bundledJson);
    assert.equal(check.detail, 'expo 57 names no React Native version');
  }
  const absent = await checkExpoMatchesReactNative(
    project({ reactNative: '0.86.2', expo: '57.0.25' }),
  );
  assert.equal(absent.status, 'skip');
});
