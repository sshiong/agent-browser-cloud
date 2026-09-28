import assert from 'node:assert/strict';
import test from 'node:test';

// noVNC reads browser capabilities when its modules load. The fence handler below uses only
// the receive queue, display queue and socket, so these minimal globals are enough to test it.
globalThis.document = {
  documentElement: {},
  createElement: () => ({ style: { cursor: '' }, getContext: () => ({}) }),
};
if (!globalThis.navigator) {
  globalThis.navigator = { maxTouchPoints: 0, msMaxTouchPoints: 0 };
}
if (!globalThis.WebSocket) {
  globalThis.WebSocket = { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 };
}
globalThis.window = {
  navigator: globalThis.navigator,
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
globalThis.CustomEvent = class {
  constructor(type, init) { this.type = type; this.detail = init.detail; }
};

let pendingAnimationFrame;
const { default: RFB } = await import('@novnc/novnc');

function receiver(payload, displayPromise = Promise.resolve()) {
  const sent = [];
  const events = [];
  let displayFlushes = 0;
  const socket = {
    rQwait: () => false,
    rQskipBytes() {},
    rQshift32: () => 0x80000000,
    rQshift8: () => payload.length,
    rQshiftStr: () => payload,
    sQpush8(value) { sent.push(value); },
    sQpush16(value) { sent.push(value); },
    sQpush32(value) { sent.push(value); },
    sQpushString(value) { sent.push(value); },
    flush() { sent.push('flush'); },
  };
  return {
    fake: {
      _sock: socket,
      _display: { flush() { displayFlushes += 1; return displayPromise; } },
      _rfbConnectionState: 'connected',
      dispatchEvent(event) { events.push(event); },
    },
    sent,
    events,
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

test('frame ID fence keeps its 16-byte payload through the draw barrier', async () => {
  const payload = 'ABCF\x00\x00\x00\x03\x00\x00\x00\x00\x00\x00\x00\x2a';
  const { fake, sent, displayFlushes } = receiver(payload);
  pendingAnimationFrame = undefined;
  assert.equal(RFB.prototype._handleServerFenceMsg.call(fake), true);
  assert.equal(displayFlushes(), 1);
  await Promise.resolve();
  assert.deepEqual(sent, []);
  pendingAnimationFrame();
  assert.deepEqual(sent, [248, 0, 0, 0, 0, 16, payload, 'flush']);
  assert.equal(fake._agentBrowserDisplayedFrameId, payload.slice(8));
  fake._sendAgentBrowserInputFrame = RFB.prototype._sendAgentBrowserInputFrame;
  fake._viewOnly = false;
  RFB.prototype.sendKey.call(fake, 65, '', true);
  assert.deepEqual(sent.slice(8, 16), [248, 0, 0, 0, 0, 12, 'ABCI' + payload.slice(8), 'flush']);
});

test('20-byte frame fence reports source-to-draw age and retains the input frame ID', async () => {
  const id = '\x00\x00\x00\x00\x00\x00\x00\x2a';
  const payload = 'ABCF\x00\x00\x00\x03' + id + '\x00\x00\x00\xfa';
  const { fake, sent, events } = receiver(payload);
  pendingAnimationFrame = undefined;
  assert.equal(RFB.prototype._handleServerFenceMsg.call(fake), true);
  await Promise.resolve();
  assert.deepEqual(events, []);
  pendingAnimationFrame();
  assert.deepEqual(sent, [248, 0, 0, 0, 0, 20, payload, 'flush']);
  assert.equal(fake._agentBrowserDisplayedFrameId, id);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'agentbrowserframe');
  assert.equal(events[0].detail.frameId, id);
  assert.ok(events[0].detail.drawAgeMs >= 250);
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
