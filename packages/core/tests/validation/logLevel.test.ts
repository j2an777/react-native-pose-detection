import assert from 'node:assert/strict';
import { test } from 'node:test';

import { PoseConfigError } from '../../src/errors';
import { assertValidLogLevel, validateLogLevel } from '../../src/validation/logLevel';

function paths(issues: readonly { path: string }[]): string[] {
  return issues.map((issue) => issue.path);
}

test('a level, or a map of known categories to levels, is fine', () => {
  assert.deepEqual(validateLogLevel('debug'), []);
  assert.deepEqual(validateLogLevel({ triggers: 'trace', camera: 'off' }), []);
  assert.deepEqual(validateLogLevel({}), []);
});

test('an unknown category or level is refused with its path', () => {
  const issues = validateLogLevel({ trigger: 'trace', camera: 'loud' });
  assert.deepEqual(paths(issues), ['logLevel.trigger', 'logLevel.camera']);
  assert.match(issues[0]?.message ?? '', /unknown category/);
});

test('a string that is not a level, or a shape that is not a map, is refused', () => {
  assert.deepEqual(paths(validateLogLevel('verbose')), ['logLevel', 'logLevel']);
  assert.deepEqual(paths(validateLogLevel(['debug'])), ['logLevel', 'logLevel']);
  assert.deepEqual(paths(validateLogLevel(null)), ['logLevel', 'logLevel']);
});

test('the assertion throws PoseConfigError, which is what the prop and setLogLevel do', () => {
  assert.throws(() => assertValidLogLevel('verbose'), PoseConfigError);
  assert.doesNotThrow(() => assertValidLogLevel('off'));
});
