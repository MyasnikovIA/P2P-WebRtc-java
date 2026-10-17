const $ = id => document.getElementById(id);

let currentTab = null;
let tabHost = '';

(async () => {
  const cfg = await chrome.storage.local.get(['room', 'server']);
  $('room').value = cfg.room || 'main';

  [currentTab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const isHttp = currentTab && /^https?:\/\//.test(currentTab.url || '');
  if (!isHttp) {
    $('start').disabled = true;
    $('status').textContent = 'Откройте любую http(s) страницу — в неё будет внедрён мост.';
    return;
  }

  try { tabHost = new URL(currentTab.url).host; } catch { tabHost = ''; }

  // Приоритет: сохранённый сервер → host текущей вкладки
  $('server').value = cfg.server || tabHost;
})();

function normalizeServerInput(raw) {
  let s = (raw || '').trim();
  if (!s) return '';
  if (s.startsWith('ws://') || s.startsWith('wss://')) return s;
  if (s.startsWith('http://'))  return 'ws://'  + s.slice(7);
  if (s.startsWith('https://')) return 'wss://' + s.slice(8);
  return 'ws://' + s; // host:port
}

$('start').onclick = async () => {
  if (!currentTab) return;

  const room = $('room').value.trim() || 'main';
  const serverRaw = $('server').value.trim();
  const server = serverRaw ? normalizeServerInput(serverRaw) : '';

  await chrome.storage.local.set({ room, server: serverRaw });

  $('status').textContent = 'starting…';
  try {
    const resp = await chrome.runtime.sendMessage({
      type: 'start',
      tabId: currentTab.id,
      room,
      host: server || tabHost
    });
    if (resp && resp.ok) window.close();
    else $('status').textContent = 'error: ' + (resp && resp.error);
  } catch (e) {
    $('status').textContent = 'sendMessage error: ' + e.message;
  }
};

$('stop').onclick = async () => {
  await chrome.runtime.sendMessage({ type: 'stop' });
  $('status').textContent = 'stopped.';
};