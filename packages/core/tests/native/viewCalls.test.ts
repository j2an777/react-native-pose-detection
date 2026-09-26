import assert from 'node:assert/strict';
import { test } from 'node:test';

import { callView, isViewNotMounted, MOUNT_WAIT_MS } from '../../src/native/viewCalls';

const notMounted = new Error(
  "Call to function 'PoseCameraView.pause' has been rejected.\n" +
    '→ Caused by: Unable to find the class com.posedetection.view.PoseCameraView view with tag 660',
);

/** A clock that only moves when the retry sleeps, so the tests never wait for real. */
function clock() {
  let now = 0;
  return {
    now: () => now,
    sleep: async (ms: number) => {
      now += ms;
    },
  };
}

test('both platforms’ wording of a view that is not mounted yet is recognized', () => {
  assert.equal(isViewNotMounted(notMounted), true);
  assert.equal(
    isViewNotMounted(new Error("Unable to find the 'PoseCameraView' view with tag '12'")),
    true,
  );
  assert.equal(isViewNotMounted(new Error('The camera could not be switched.')), false);
});

test('a call made before the view is mounted waits for it', async () => {
  const time = clock();
  let attempts = 0;
  const result = await callView(
    () => 'view',
    async () => {
      attempts += 1;
      if (attempts < 3) throw notMounted;
      return 'paused';
    },
    time.sleep,
    time.now,
  );
  assert.equal(result, 'paused');
  assert.equal(attempts, 3);
});

test('any other failure rejects at once', async () => {
  const time = clock();
  let attempts = 0;
  await assert.rejects(
    callView(
      () => 'view',
      async () => {
        attempts += 1;
        throw new Error('CAMERA_SWITCH_FAILED');
      },
      time.sleep,
      time.now,
    ),
    /CAMERA_SWITCH_FAILED/,
  );
  assert.equal(attempts, 1);
});

test('a view that never mounts rejects once the wait is over', async () => {
  const time = clock();
  await assert.rejects(
    callView(
      () => 'view',
      async () => {
        throw notMounted;
      },
      time.sleep,
      time.now,
    ),
    /Unable to find/,
  );
  assert.ok(time.now() >= MOUNT_WAIT_MS);
});

test('an unmounted component resolves to nothing rather than failing', async () => {
  const time = clock();
  const result = await callView(
    () => null,
    async () => 'never',
    time.sleep,
    time.now,
  );
  assert.equal(result, undefined);
});
