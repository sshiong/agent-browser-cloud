setInterval(() => {
  chrome.runtime.sendMessage({ type: "integration-heartbeat" }).catch(() => {});
}, 1000);
