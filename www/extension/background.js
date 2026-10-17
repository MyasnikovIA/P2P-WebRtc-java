let captureWindowId = null;
let publisherTabId = null;
let meetingWs = null;

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  // --- СТАРЫЙ ПЛАГИН (Broadcasting) ---
  if (msg.type === 'start') {
    startCapture(msg.tabId, msg.room)
        .then(r => sendResponse(r))
        .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }

  // НОВЫЙ ОБРАБОТЧИК: запуск трансляции со страницы
  if (msg.type === 'page-start-broadcast') {
    if (!_sender.tab) { sendResponse({ ok: false, error: 'no tab info' }); return; }
    startCapture(_sender.tab.id, msg.room)
        .then(r => sendResponse(r))
        .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }

  if (msg.type === 'stop') {
    stopAll();
    sendResponse({ ok: true });
    return;
  }
  if (msg.type === 'connect-bridge') {
    if (publisherTabId == null) { sendResponse({ ok: false, error: 'no publisher tab' }); return; }
    chrome.tabs.sendMessage(publisherTabId, { type: 'bridge-connect', room: msg.room })
        .then(r => sendResponse(r)).catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
  if (msg.type === 'signal') {
    if (publisherTabId == null) { sendResponse({ ok: false, error: 'no publisher tab' }); return; }
    chrome.tabs.sendMessage(publisherTabId, { type: 'signal', target: msg.target, data: msg.data })
        .then(r => sendResponse(r)).catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }

  // --- НОВЫЙ ПЛАГИН (Meeting Mesh) ---
  if (msg.type === 'meeting-connect') {
    if (meetingWs) { try { meetingWs.close(); } catch {} meetingWs = null; }

    let responded = false;
    const respond = (data) => { if (responded) return; responded = true; sendResponse(data); };

    try {
      meetingWs = new WebSocket(msg.url);

      const timeoutId = setTimeout(() => {
        respond({ ok: false, error: 'Connection timeout (5s)' });
        if (meetingWs) { try { meetingWs.close(); } catch {} meetingWs = null; }
      }, 5000);

      meetingWs.onopen = () => {
        clearTimeout(timeoutId);
        broadcastToMeeting({ type: 'ws-status', status: 'connected' });
        respond({ ok: true });
      };
      meetingWs.onmessage = (e) => {
        try { broadcastToMeeting(JSON.parse(e.data)); } catch (err) { }
      };
      meetingWs.onerror = () => {
        clearTimeout(timeoutId);
        broadcastToMeeting({ type: 'ws-status', status: 'error' });
        respond({ ok: false, error: 'Connection error' });
      };
      meetingWs.onclose = (e) => {
        clearTimeout(timeoutId);
        broadcastToMeeting({ type: 'ws-status', status: 'disconnected' });
        respond({ ok: false, error: 'Connection closed, code: ' + e.code });
        meetingWs = null;
      };
      return true;
    } catch (e) {
      respond({ ok: false, error: e.message });
      return;
    }
  }

  if (msg.type === 'meeting-send') {
    if (meetingWs && meetingWs.readyState === WebSocket.OPEN) {
      meetingWs.send(JSON.stringify(msg.payload));
      sendResponse({ ok: true });
    } else {
      sendResponse({ ok: false, error: 'WS not open' });
    }
    return;
  }
  if (msg.type === 'meeting-disconnect') {
    if (meetingWs) { try { meetingWs.close(); } catch {} meetingWs = null; }
    sendResponse({ ok: true });
    return;
  }
});

function broadcastToMeeting(message) {
  chrome.tabs.query({ url: chrome.runtime.getURL('meeting.html') + '*' }, (tabs) => {
    tabs.forEach(tab => {
      chrome.tabs.sendMessage(tab.id, message).catch(() => {});
    });
  });
}

async function ensureBridge(tabId) {
  try {
    await chrome.tabs.sendMessage(tabId, { type: 'bridge-ping' });
    return;
  } catch (_) {}
  await chrome.scripting.executeScript({ target: { tabId }, files: ['bridge.js'] });
}

async function startCapture(tabId, room) {
  if (captureWindowId !== null) {
    try { await chrome.windows.remove(captureWindowId); } catch {}
    captureWindowId = null;
  }
  publisherTabId = tabId;
  await ensureBridge(tabId);
  const url = chrome.runtime.getURL(`capture.html?room=${encodeURIComponent(room)}`);
  const win = await chrome.windows.create({ url, type: 'popup', width: 420, height: 380 });
  captureWindowId = win.id;
  return { ok: true };
}

function stopAll() {
  if (publisherTabId != null) {
    chrome.tabs.sendMessage(publisherTabId, { type: 'bridge-disconnect' }).catch(() => {});
  }
  if (captureWindowId !== null) {
    chrome.windows.remove(captureWindowId).catch(() => {});
    captureWindowId = null;
  }
  publisherTabId = null;
}

chrome.windows.onRemoved.addListener((id) => {
  if (id === captureWindowId) {
    captureWindowId = null;
    if (publisherTabId != null) {
      chrome.tabs.sendMessage(publisherTabId, { type: 'bridge-disconnect' }).catch(() => {});
    }
  }
});