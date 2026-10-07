// Runs only in a Node-created isolated world in the exact main Frame. No page data or values
// enter the observer ledger. The bounded path list is supplied by Node's current full snapshot.
(() => {
  const paths = __REGION_PATHS__;
  const key = '__agentBrowserRegionObserverV1';
  const limit = Number.MAX_SAFE_INTEGER;
  let state = globalThis[key];
  const advance = () => {
    if (state.sequence >= limit) { state.fault = true; return state.sequence; }
    return ++state.sequence;
  };
  const related = (element, target) => element === target
    || element.contains(target) || target.contains(element);
  const touch = target => {
    const sequence = advance();
    if (!(target instanceof Node)) { state.fault = true; return; }
    for (const entry of state.watched.values()) {
      const styled = target.nodeType === Node.ELEMENT_NODE ? target : target.parentElement;
      if (related(entry.scope, target) || styled?.closest('style,link[rel="stylesheet"]')) {
        entry.lastEventSequence = sequence;
      }
    }
  };
  if (!state) {
    const nonce = Array.from(crypto.getRandomValues(new Uint32Array(4)),
      value => value.toString(16).padStart(8, '0')).join('');
    state = {nonce, sequence: 1, nextIdentity: 1, fault: false,
      identities: new WeakMap(), watched: new Map()};
    const mutations = records => {
      if (records.length > 1000) { state.fault = true; return; }
      for (const record of records) touch(record.target);
    };
    state.observer = new MutationObserver(mutations);
    state.observer.observe(document, {subtree: true, childList: true,
      attributes: true, characterData: true});
    state.resizeObserver = new ResizeObserver(entries => {
      if (entries.length > 40) { state.fault = true; return; }
      for (const entry of entries) touch(entry.target);
    });
    for (const event of ['input', 'change', 'focusin', 'focusout', 'scroll', 'visibilitychange', 'animationstart',
      'animationiteration', 'animationend', 'animationcancel', 'transitionrun',
      'transitionstart', 'transitionend', 'transitioncancel']) {
      document.addEventListener(event, value => touch(value.target), true);
    }
    state.flush = () => mutations(state.observer.takeRecords());
    globalThis[key] = state;
  }
  // A native form reset does not have to call an input's JavaScript setter or emit a
  // Mutation. Add the listener to existing ledgers too, invalidating the unobserved gap.
  if (!state.resetListener) {
    state.resetListener = event => touch(event.target);
    document.addEventListener('reset', state.resetListener, true);
    const sequence = advance();
    for (const entry of state.watched.values()) entry.lastEventSequence = sequence;
  }
  state.flush();
  const next = new Map();
  const regions = [];
  for (const path of paths) {
    const element = document.querySelector(path);
    if (!element) continue;
    let identity = state.identities.get(element);
    if (!identity) {
      if (state.nextIdentity >= limit) { state.fault = true; break; }
      identity = state.nextIdentity++;
      state.identities.set(element, identity);
    }
    const previous = state.watched.get(path);
    let scope = element;
    let candidate = element;
    for (let depth = 0; candidate && depth < 12; depth++, candidate = candidate.parentElement) {
      if (['row', 'listitem', 'treeitem'].includes(candidate.getAttribute('role'))
          || ['TR', 'LI'].includes(candidate.tagName)
          || ['data-agent-entity-id', 'data-entity-id', 'data-row-key', 'data-item-key',
            'data-key', 'data-agent-entity-hash'].some(attribute => candidate.hasAttribute(attribute))) {
        scope = candidate;
        break;
      }
    }
    const entry = previous?.element === element ? previous
      : {element, identity, lastEventSequence: state.sequence};
    entry.scope = scope;
    if (previous?.element !== element) {
      if (previous) state.resizeObserver.unobserve(previous.element);
      state.resizeObserver.observe(element);
    }
    next.set(path, entry);
    const rect = element.getBoundingClientRect();
    let animationActive = false;
    let current = element;
    let depth = 0;
    while (current && depth++ < 64) {
      const animations = current.getAnimations(current === element ? {subtree: true} : {});
      if (animations.length > 100 || animations.some(animation => animation.pending
          || animation.playState === 'running')) { animationActive = true; break; }
      current = current.parentElement;
    }
    if (current && depth >= 64) animationActive = true;
    regions.push({path, identity, lastEventSequence: entry.lastEventSequence,
      animationActive, bounds: {x: rect.x, y: rect.y, width: rect.width, height: rect.height}});
  }
  for (const [path, entry] of state.watched) {
    if (!next.has(path)) state.resizeObserver.unobserve(entry.element);
  }
  state.watched = next;
  return {nonce: state.nonce, currentSequence: state.sequence, fresh: !state.fault, regions};
})()
