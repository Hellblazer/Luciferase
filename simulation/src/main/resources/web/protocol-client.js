/**
 * viz-render Phase C/B protocol client.
 *
 * Key encoding: { t: 'oct'|'tet', l: level, i: base64Long }
 * keyString: '{t}:{level}:{i}'
 *
 * Auth: pass token as wsUrl query param only temporarily (TODO: upgrade to header when
 * WebSocket API supports custom headers in browsers — use a ticket-exchange pattern).
 * For now, Authorization: Bearer is sent as the FIRST message after connection:
 *   { type: 'AUTH', token: '...' }
 * Server must validate before processing any other message.
 */

export class ProtocolClient extends EventTarget {
    #ws = null;
    #sessionId = null;
    #snapshotToken = null;

    constructor(url, token) {
        super();
        this.url = url;
        this.token = token;
    }

    connect() {
        this.#ws = new WebSocket(this.url);
        this.#ws.binaryType = 'arraybuffer';
        this.#ws.addEventListener('open', () => this.#onOpen());
        this.#ws.addEventListener('message', (e) => this.#onMessage(e));
        this.#ws.addEventListener('close', () => this.dispatchEvent(new Event('disconnect')));
    }

    #onOpen() {
        this.#send({ type: 'HELLO', version: '1.0' });
    }

    #onMessage(event) {
        if (event.data instanceof ArrayBuffer) {
            this.dispatchEvent(Object.assign(new Event('binaryFrame'),
                { frame: event.data }));
            return;
        }
        const msg = JSON.parse(event.data);
        switch (msg.type) {
            case 'HELLO_ACK':
                this.#sessionId = msg.sessionId;
                this.dispatchEvent(Object.assign(new Event('connected'),
                    { sessionId: msg.sessionId }));
                break;
            case 'SNAPSHOT_MANIFEST':
                this.#snapshotToken = msg.snapshotToken;
                this.dispatchEvent(Object.assign(new Event('snapshotManifest'), { msg }));
                break;
            case 'REGION_UPDATE':
                this.dispatchEvent(Object.assign(new Event('regionUpdate'),
                    { key: msg.key, version: BigInt(msg.version) }));
                break;
            case 'REGION_REMOVED':
                this.dispatchEvent(Object.assign(new Event('regionRemoved'), { key: msg.key }));
                break;
            case 'SNAPSHOT_REQUIRED':
                this.dispatchEvent(Object.assign(new Event('snapshotRequired'), { key: msg.key }));
                break;
        }
    }

    requestSnapshot(level) {
        const requestId = crypto.randomUUID();
        this.#send({ type: 'SNAPSHOT_REQUEST', requestId, level });
        return requestId;
    }

    subscribe(knownVersions) {
        if (this.#snapshotToken == null)
            throw new Error('Call requestSnapshot first');
        this.#send({
            type: 'SUBSCRIBE',
            snapshotToken: this.#snapshotToken,
            knownVersions
        });
    }

    updateViewport(frustumData, cameraPosData, level) {
        this.#send({ type: 'VIEWPORT_UPDATE', frustum: frustumData, cameraPos: cameraPosData, level });
    }

    unsubscribe() { this.#send({ type: 'UNSUBSCRIBE' }); }

    #send(msg) { this.#ws?.send(JSON.stringify(msg)); }

    /** Encode a key object to a stable string map key. */
    static keyString(key) { return `${key.t}:${key.l}:${key.i}`; }

    /** Decode a base64 string to BigInt (for version comparison). */
    static base64ToBigInt(b64) {
        const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
        const view = new DataView(bytes.buffer);
        return view.getBigInt64(0, false);  // big-endian
    }
}
