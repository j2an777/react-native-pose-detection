import assert from 'node:assert/strict';
import { test } from 'node:test';

import { resolveSmoothing } from '../src/smoothing';

test("'auto' is off for one pose, which MediaPipe already smooths", () => {
  assert.equal(resolveSmoothing('auto', 1), false);
  assert.equal(resolveSmoothing(undefined, undefined), false);
});

test("'auto' is on for several poses, which MediaPipe does not smooth", () => {
  assert.equal(resolveSmoothing('auto', 2), true);
  assert.equal(resolveSmoothing(undefined, 5), true);
});

test('an explicit answer is passed through whatever maxPoses says', () => {
  assert.equal(resolveSmoothing(true, 1), true);
  assert.equal(resolveSmoothing(false, 3), false);
  assert.deepEqual(resolveSmoothing({ minCutoff: 0.1, beta: 40 }, 1), { minCutoff: 0.1, beta: 40 });
});
