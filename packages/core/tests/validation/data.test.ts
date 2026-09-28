import assert from 'node:assert/strict';
import { test } from 'node:test';

import { PoseConfigError } from '../../src/errors';
import {
  assertValidDataConfig,
  validateDataConfig,
  validateFileJoints,
} from '../../src/validation/data';

function paths(issues: readonly { path: string }[]): string[] {
  return issues.map((issue) => issue.path);
}

test('a well formed data config produces no issues', () => {
  assert.deepEqual(
    validateDataConfig({
      mode: 'throttled',
      throttleMs: 100,
      select: ['nose', 'leftWrist'],
      angles: ['leftKnee', 'rightElbow'],
      worldLandmarks: true,
    }),
    [],
  );
  assert.deepEqual(validateDataConfig({ mode: 'batched', angles: true }), []);
});

test('an absent config is nothing to check', () => {
  assert.deepEqual(validateDataConfig(undefined), []);
  assert.deepEqual(validateDataConfig(null), []);
  assert.deepEqual(validateDataConfig({}), []);
});

test('an unknown mode is refused rather than read as off', () => {
  assert.deepEqual(paths(validateDataConfig({ mode: 'slow' })), ['data.mode']);
});

test('an unknown joint in select is refused with its index', () => {
  const issues = validateDataConfig({ select: ['nose', 'wrist'] });
  assert.deepEqual(paths(issues), ['data.select[1]']);
  assert.match(issues[0]?.message ?? '', /unknown joint "wrist"/);
});

test('an angle on a joint that has none says so', () => {
  const issues = validateDataConfig({ angles: ['nose', 'leftKnee', 'kneeLeft'] });
  assert.deepEqual(paths(issues), ['data.angles[0]', 'data.angles[2]']);
  assert.match(issues[0]?.message ?? '', /has no angle/);
  assert.match(issues[1]?.message ?? '', /unknown joint/);
});

test('select and angles must be arrays', () => {
  assert.deepEqual(paths(validateDataConfig({ select: 'nose', angles: 'leftKnee' })), [
    'data.select',
    'data.angles',
  ]);
});

test('the file functions hold select and angles to the same rules, under options', () => {
  const issues = validateFileJoints({ select: ['nose', 'wrist'], angles: ['leftKnee', 'nose'] });
  assert.deepEqual(paths(issues), ['options.select[1]', 'options.angles[1]']);
  assert.match(issues[1]?.message ?? '', /has no angle/);

  assert.deepEqual(validateFileJoints({ select: ['leftKnee'], angles: true, maxPoses: 2 }), []);
  assert.deepEqual(validateFileJoints({ angles: false }), []);
  assert.deepEqual(validateFileJoints(undefined), []);
});

test('the assertion throws one PoseConfigError carrying every issue', () => {
  assert.throws(
    () => assertValidDataConfig({ mode: 'fast', select: ['elbow'] }),
    (error: unknown) =>
      error instanceof PoseConfigError &&
      error.issues.length === 2 &&
      error.issues[0]?.path === 'data.mode',
  );
});
