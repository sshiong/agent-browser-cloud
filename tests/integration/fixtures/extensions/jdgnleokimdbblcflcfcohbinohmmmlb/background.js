chrome.runtime.onMessage.addListener((_message, _sender, sendResponse) => {
  let checksum = 0;
  for (let index = 0; index < 20000; index += 1) {
    checksum = (checksum + index * 17) % 1000003;
  }
  sendResponse({ checksum });
});
