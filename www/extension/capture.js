const params = new URLSearchParams(location.search);
const room  = params.get('room') || 'main';
const host  = params.get('host') || '';

const statusEl = document.getElementById('status');
const preview  = document.getElementById('preview');
const stopBtn  = document.getElementById('stop');

const setStatus = s => { console.log('[capture]', s); statusEl.textContent = s; };

let stream = null;
let closed = false;
const pcs = new Map();

(async () => {
    setStatus('starting…');
    try {
        stream = await navigator.mediaDevices.getUserMedia({
            video: { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 30 } },
            audio: { echoCancellation: true, noiseSuppression: true }
        });
        preview.srcObject = stream;

        const tracksInfo = stream.getTracks().map(t => `${t.kind} (${t.label || 'no label'})`);
        console.log('[capture] Captured tracks:', tracksInfo);

        setStatus(`camera OK, connecting bridge (${host || location.host})…`);
    } catch (e) {
        setStatus('getUserMedia error: ' + e.message);
        return;
    }

    let conn;
    try {
        conn = await chrome.runtime.sendMessage({ type: 'connect-bridge', room, host });
    } catch (e) {
        setStatus('bridge message error: ' + e.message);
        return;
    }
    if (!conn || !conn.ok) {
        setStatus('bridge connect failed: ' + (conn ? conn.error : 'no response'));
        return;
    }
    setStatus('bridge connected, waiting for viewers…');
})();

chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === 'viewerJoined') {
        setStatus('new viewer: ' + msg.viewerId);
        createPeerConnection(msg.viewerId);
    }
    else if (msg.type === 'viewerLeft') {
        const pc = pcs.get(msg.viewerId);
        if (pc) { pc.close(); pcs.delete(msg.viewerId); }
    }
    else if (msg.type === 'signal') {
        handleSignal(msg.viewerId, msg.data);
    }
});

async function createPeerConnection(viewerId) {
    const pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
    });
    pcs.set(viewerId, pc);

    stream.getTracks().forEach(t => pc.addTrack(t, stream));

    pc.onicecandidate = (e) => {
        if (e.candidate) {
            chrome.runtime.sendMessage({
                type: 'signal',
                target: viewerId,
                data: { candidate: e.candidate }
            }).catch(()=>{});
        }
    };

    pc.onconnectionstatechange = () => {
        if (['disconnected', 'failed', 'closed'].includes(pc.connectionState)) {
            pc.close();
            pcs.delete(viewerId);
            setStatus('viewer disconnected: ' + viewerId);
        }
    };

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    chrome.runtime.sendMessage({
        type: 'signal',
        target: viewerId,
        data: { sdp: pc.localDescription }
    }).catch(()=>{});
}

async function handleSignal(viewerId, data) {
    const pc = pcs.get(viewerId);
    if (!pc) return;
    try {
        if (data.sdp) {
            await pc.setRemoteDescription(new RTCSessionDescription(data.sdp));
        } else if (data.candidate) {
            await pc.addIceCandidate(new RTCIceCandidate(data.candidate));
        }
    } catch (e) {
        console.error('Signal error', e);
    }
}

function stop() {
    if (closed) return;
    closed = true;
    pcs.forEach(pc => pc.close());
    pcs.clear();
    if (stream) stream.getTracks().forEach(t => t.stop());
    setTimeout(() => window.close(), 150);
}

stopBtn.onclick = stop;
window.addEventListener('beforeunload', stop);
chrome.runtime.onMessage.addListener((msg) => { if (msg.type === 'stop') stop(); });