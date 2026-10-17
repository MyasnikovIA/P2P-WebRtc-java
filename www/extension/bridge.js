(() => {
    let ws = null;

    // Сообщаем странице, что скрипт расширения загружен и готов к работе
    window.postMessage({ __extReady: true }, '*');

    // Слушаем сообщения от веб-страницы
    window.addEventListener('message', (e) => {
        if (e.source !== window) return;
        if (!e.data || !e.data.__toExt) return;

        const { type, payload, __id } = e.data;

        if (type === 'ext-ping') {
            // Расширение установлено и скрипт работает
            window.postMessage({ __replyTo: __id, ok: true }, '*');
            return;
        }

        if (type === 'start-broadcast') {
            // Просим background.js открыть окно с камерой
            chrome.runtime.sendMessage({
                type: 'page-start-broadcast',
                room: payload.room || 'main'
            }).then((r) => {
                window.postMessage({ __replyTo: __id, ...r }, '*');
            }).catch((err) => {
                window.postMessage({ __replyTo: __id, ok: false, error: err.message }, '*');
            });
            return;
        }
    });

    // Обработка запросов от background.js (сигналинг WebRTC)
    chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
        if (msg.type === 'bridge-ping') { sendResponse({ ok: true }); return; }

        if (msg.type === 'bridge-connect') {
            if (ws) { try { ws.close(); } catch {} ws = null; }
            const url = `ws://${location.host}/ws/publish?room=${encodeURIComponent(msg.room)}`;
            try { ws = new WebSocket(url); } catch (e) { sendResponse({ ok: false, error: e.message }); return; }

            ws.binaryType = 'arraybuffer';

            ws.onmessage = (e) => {
                if (typeof e.data === 'string') {
                    try {
                        const json = JSON.parse(e.data);
                        if (json.viewerJoined) {
                            chrome.runtime.sendMessage({ type: 'viewerJoined', viewerId: json.viewerJoined }).catch(()=>{});
                        } else if (json.viewerLeft) {
                            chrome.runtime.sendMessage({ type: 'viewerLeft', viewerId: json.viewerLeft }).catch(()=>{});
                        } else if (json.sdp || json.candidate) {
                            chrome.runtime.sendMessage({
                                type: 'signal',
                                viewerId: json.viewerId,
                                data: json
                            }).catch(()=>{});
                        }
                    } catch (err) {}
                }
            };

            let done = false;
            const finish = (r) => { if (!done) { done = true; sendResponse(r); } };

            ws.onopen  = () => finish({ ok: true });
            ws.onerror = () => finish({ ok: false, error: 'ws error' });
            ws.onclose = (e) => { if (!done) finish({ ok: false, error: 'closed: ' + e.code }); ws = null; };
            setTimeout(() => finish({ ok: false, error: 'timeout' }), 5000);
            return true;
        }

        if (msg.type === 'signal') {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ target: msg.target, ...msg.data }));
                sendResponse({ ok: true });
            } else {
                sendResponse({ ok: false, error: 'not open' });
            }
            return;
        }

        if (msg.type === 'bridge-disconnect') {
            if (ws) { try { ws.close(); } catch {} ws = null; }
            sendResponse({ ok: true });
        }
    });
})();