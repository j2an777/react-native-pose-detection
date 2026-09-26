import assert from 'node:assert/strict';
import { test } from 'node:test';

import { PoseConfigError } from '../../src/errors';
import {
  assertValidCameraNumbers,
  assertValidFileOptions,
  validateCameraNumbers,
  validateFileOptions,
} from '../../src/validation/numbers';

function paths(issues: readonly { path: string }[]): string[] {
  return issues.map((issue) => issue.path);
}

test('ordinary camera numbers produce no issues', () => {
  assert.deepStrictEqual(
    validateCameraNumbers({
      targetFps: 24,
      maxPoses: 2,
      minConfidence: 0.4,
      smoothing: { minCutoff: 1, beta: 4 },
      data: { mode: 'throttled', throttleMs: 100 },
      overlay: { lineWidth: 3, angles: [{ joint: 'leftKnee', radius: 30, decimals: 1 }] },
    }),
    [],
  );
});

test("targetFps 'auto' is a value, not a number to check", () => {
  assert.deepStrictEqual(validateCameraNumbers({ targetFps: 'auto' }), []);
});

test('NaN and both infinities are refused wherever a camera number goes', () => {
  const issues = validateCameraNumbers({
    targetFps: Number.POSITIVE_INFINITY,
    maxPoses: Number.NaN,
    minConfidence: Number.NEGATIVE_INFINITY,
    smoothing: { minCutoff: Number.NaN, beta: 4 },
    data: { mode: 'throttled', throttleMs: 1000 / 0, flushMs: Number.NaN },
    overlay: {
      pointRadius: Number.NaN,
      angles: [{ joint: 'leftKnee', decimals: Number.POSITIVE_INFINITY }],
    },
  });
  assert.deepStrictEqual(paths(issues), [
    'targetFps',
    'maxPoses',
    'minConfidence',
    'smoothing.minCutoff',
    'data.throttleMs',
    'data.flushMs',
    'overlay.pointRadius',
    'overlay.angles[0].decimals',
  ]);
});

test('a value of the wrong type says what it received', () => {
  const issues = validateCameraNumbers({ maxPoses: '2' as unknown as number });
  assert.equal(issues.length, 1);
  assert.equal(issues[0]?.message, 'must be a number, received string');
});

test('booleans and absent configs are not number fields', () => {
  assert.deepStrictEqual(validateCameraNumbers({ smoothing: true, overlay: false }), []);
  assert.deepStrictEqual(validateCameraNumbers({}), []);
});

test('the camera assertion throws a PoseConfigError naming the path', () => {
  assert.throws(
    () => assertValidCameraNumbers({ data: { mode: 'live', throttleMs: Number.NaN } }),
    (error: unknown) =>
      error instanceof PoseConfigError &&
      error.message === 'data.throttleMs: must be a finite number, received NaN',
  );
});

test('file options are checked the same way, overlay included', () => {
  assert.deepStrictEqual(validateFileOptions(undefined), []);
  assert.deepStrictEqual(validateFileOptions({ fps: 10, startMs: 0, maxPoses: 1 }), []);
  assert.deepStrictEqual(
    paths(
      validateFileOptions({
        fps: Number.POSITIVE_INFINITY,
        endMs: Number.NaN,
        maxSize: Number.NaN,
        overlay: { lineWidth: Number.NaN },
      }),
    ),
    ['options.fps', 'options.endMs', 'options.maxSize', 'options.overlay.lineWidth'],
  );
  assert.throws(() => assertValidFileOptions({ quality: Number.NaN }), PoseConfigError);
});
