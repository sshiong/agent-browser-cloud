import assert from 'node:assert/strict';
import test from 'node:test';

// noVNC reads browser capabilities when its modules load. The fence handler below uses only
// the receive queue, display queue and socket, so these minimal globals are enough to test it.
globalThis.document = {
  documentElement: {},
  createElement: () => ({ style: { cursor: '' }, getContext: () => ({}) }),
};
globalThis.window = {
  navigator,
  document,
  addEventListener() {},
  removeEventListener() {},
  requestAnimationFrame(callback) {
    pendingAnimationFrame = callback;
  },
};
globalThis.MutationObserver = class {
  observe() {}
  disconnect() {}
};

let pendingAnimationFrame;
const { default: RFB } = await import('@novnc/novnc');

function receiver(payload, displayPromise = Promise.resolve()) {
  const sent = [];
  let displayFlushes = 0;
  const socket = {
    rQwait: () => false,
    rQskipBytes() {},
    rQshift32: () => 0x80000000,
    rQshift8: () => payload.length,
    rQshiftStr: () => payload,
    sQpush8(value) { sent.push(value); },
    sQpush32(value) { sent.push(value); },
    sQpushString(value) { sent.push(value); },
    flush() { sent.push('flush'); },
  };
  return {
    fake: {
      _sock: socket,
      _display: { flush() { displayFlushes += 1; return displayPromise; } },
      _rfbConnectionState: 'connected',
    },
    sent,
    displayFlushes: () => displayFlushes,
  };
}

test('gateway frame fence waits for the draw queue and an animation frame', async () => {
  let completeDisplay;
  const displayPromise = new Promise((resolve) => { completeDisplay = resolve; });
  const { fake, sent, displayFlushes } = receiver('ABCF\x00\x00\x00\x01', displayPromise);
  pendingAnimationFrame = undefined;

  assert.equal(RFB.prototype._handleServerFenceMsg.call(fake), true);
  assert.equal(displayFlushes(), 1);
  assert.deepEqual(sent, []);
  completeDisplay();
  await displayPromise;
  await Promise.resolve();
  assert.deepEqual(sent, []);
  assert.equal(typeof pendingAnimationFrame, 'function');

  pendingAnimationFrame();
  assert.deepEqual(sent, [248, 0, 0, 0, 0, 8, 'ABCF\x00\x00\x00\x01', 'flush']);
});

test('an abandoned connection never acknowledges a displayed frame', async () => {
  const { fake, sent } = receiver('ABCF\x00\x00\x00\x02');
  pendingAnimationFrame = undefined;
  RFB.prototype._handleServerFenceMsg.call(fake);
  await Promise.resolve();
  fake._rfbConnectionState = 'disconnected';
  pendingAnimationFrame();
  assert.deepEqual(sent, []);
});

test('ordinary server fences keep standard immediate replies', () => {
  const { fake, sent, displayFlushes } = receiver('OTHER123');
  RFB.prototype._handleServerFenceMsg.call(fake);
  assert.equal(displayFlushes(), 0);
  assert.deepEqual(sent, [248, 0, 0, 0, 0, 8, 'OTHER123', 'flush']);
});
