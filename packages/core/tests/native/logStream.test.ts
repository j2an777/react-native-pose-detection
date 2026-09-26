import assert from 'node:assert/strict';
import { test } from 'node:test';

import { logStreamGate } from '../../src/native/logStream';

function gate() {
  const calls: string[] = [];
  const stream = logStreamGate(
    () => calls.push('start'),
    () => calls.push('stop'),
  );
  return { calls, stream };
}

test('the first holder starts the stream and the last one stops it', () => {
  const { calls, stream } = gate();
  const listener = stream.hold();
  const camera = stream.hold();
  assert.deepEqual(calls, ['start']);

  listener();
  assert.deepEqual(calls, ['start']);
  camera();
  assert.deepEqual(calls, ['start', 'stop']);
});

test('a camera’s onLog keeps the stream running after the last listener goes', () => {
  const { calls, stream } = gate();
  const camera = stream.hold();
  const listener = stream.hold();
  listener();
  assert.deepEqual(calls, ['start']);
  camera();
  assert.deepEqual(calls, ['start', 'stop']);
});

test('releasing twice counts once', () => {
  const { calls, stream } = gate();
  const first = stream.hold();
  const second = stream.hold();
  first();
  first();
  assert.deepEqual(calls, ['start']);
  second();
  assert.deepEqual(calls, ['start', 'stop']);
});

test('the stream starts again for a holder that comes after it stopped', () => {
  const { calls, stream } = gate();
  stream.hold()();
  stream.hold();
  assert.deepEqual(calls, ['start', 'stop', 'start']);
});
