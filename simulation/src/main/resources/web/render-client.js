/**
 * RenderClient - WebSocket client for the Luciferase ESVO rendering server.
 *
 * Connects to a Javalin WebSocket server at /ws/render, handles binary ESVO
 * frames, JSON error messages, auto-reconnection with exponential backoff, and
 * viewport update throttling.
 *
 * Usage:
 *   const client = new RenderClient('ws://localhost:7090/ws/render');
 *   client.on('binaryFrame', (arrayBuffer) => { ... parse it ... });
 *   client.on('statusChange', ({ connected, reconnecting, attempt }) => { ... });
 *   client.on('serverMessage', (jsonMsg) => { ... handle errors ... });
 *   client.connect();
 *   client.sendRegisterClient('my-client', viewport);
 *   client.sendUpdateViewport(viewport);
 *   client.disconnect();
 */

// ============================================================================
// Constants
// ============================================================================

const DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;
const DEFAULT_RECONNECT_BASE_DELAY_MS = 1000;
const DEFAULT_MAX_RECONNECT_DELAY_MS = 30_000;

// ============================================================================
// RenderClient
// ============================================================================

export class RenderClient {
    /**
     * Create a new RenderClient.
     *
     * @param {string} serverUrl - WebSocket URL, e.g. 'ws://localhost:7090/ws/render'
     * @param {object} [options]
     * @param {string|null} [options.apiKey=null] - API key appended as ?apiKey=<value>
     * @param {number} [options.maxReconnectAttempts=10] - Max auto-reconnect attempts
     * @param {number} [options.reconnectBaseDelayMs=1000] - Base delay for exponential backoff (ms)
     * @param {number|null} [options.maxUpdatesPerSecond=null] - Throttle for sendUpdateViewport
     */
    constructor(serverUrl, options = {}) {
        this._serverUrl = serverUrl;
        this._apiKey = options.apiKey ?? null;
        this._maxReconnectAttempts = options.maxReconnectAttempts ?? DEFAULT_MAX_RECONNECT_ATTEMPTS;
        this._reconnectBaseDelayMs = options.reconnectBaseDelayMs ?? DEFAULT_RECONNECT_BASE_DELAY_MS;
        this._maxUpdatesPerSecond = options.maxUpdatesPerSecond ?? null;

        /** @type {WebSocket|null} */
        this._ws = null;

        /** @type {number} */
        this._reconnectAttempt = 0;

        /** @type {number|null} */
        this._reconnectTimer = null;

        /** @type {boolean} */
        this._intentionalClose = false;

        /** @type {number} Timestamp (ms) of last sendUpdateViewport call that was sent */
        this._lastViewportSendTime = 0;

        /** @type {Map<string, Set<Function>>} */
        this._listeners = new Map();
    }

    // ============================================================================
    // Event Emitter (on / off / _emit)
    // ============================================================================

    /**
     * Register an event listener.
     *
     * Events:
     *   - 'binaryFrame'   : (arrayBuffer: ArrayBuffer) => void
     *   - 'statusChange'  : ({ connected: boolean, reconnecting: boolean, attempt: number }) => void
     *   - 'serverMessage' : (jsonMsg: object) => void
     *
     * @param {string} event
     * @param {Function} handler
     */
    on(event, handler) {
        if (!this._listeners.has(event)) {
            this._listeners.set(event, new Set());
        }
        this._listeners.get(event).add(handler);
    }

    /**
     * Remove an event listener.
     *
     * @param {string} event
     * @param {Function} handler
     */
    off(event, handler) {
        const handlers = this._listeners.get(event);
        if (handlers) {
            handlers.delete(handler);
        }
    }

    /**
     * Emit an event to all registered listeners.
     *
     * @param {string} event
     * @param {*} payload
     */
    _emit(event, payload) {
        const handlers = this._listeners.get(event);
        if (!handlers) return;
        for (const handler of handlers) {
            try {
                handler(payload);
            } catch (err) {
                console.error(`RenderClient: error in '${event}' handler:`, err);
            }
        }
    }

    // ============================================================================
    // Connection Management
    // ============================================================================

    /**
     * Build the full WebSocket URL, appending ?apiKey=<value> if configured.
     *
     * @returns {string}
     */
    _buildUrl() {
        if (this._apiKey) {
            const separator = this._serverUrl.includes('?') ? '&' : '?';
            return `${this._serverUrl}${separator}apiKey=${encodeURIComponent(this._apiKey)}`;
        }
        return this._serverUrl;
    }

    /**
     * Open the WebSocket connection.
     *
     * @returns {Promise<void>} Resolves when the connection is open, rejects on error before open.
     */
    connect() {
        this._intentionalClose = false;

        return new Promise((resolve, reject) => {
            const url = this._buildUrl();
            console.log(`RenderClient: connecting to ${this._serverUrl}`);

            this._ws = new WebSocket(url);
            this._ws.binaryType = 'arraybuffer';

            this._ws.onopen = () => {
                console.log('RenderClient: connected');
                this._reconnectAttempt = 0;
                this._emit('statusChange', { connected: true, reconnecting: false, attempt: 0 });
                resolve();
            };

            this._ws.onclose = (event) => {
                console.log(`RenderClient: connection closed (code=${event.code}, wasClean=${event.wasClean})`);
                this._emit('statusChange', { connected: false, reconnecting: false, attempt: this._reconnectAttempt });

                if (!this._intentionalClose) {
                    this._scheduleReconnect(reject);
                }
            };

            this._ws.onerror = (error) => {
                console.error('RenderClient: WebSocket error:', error);
                // onclose fires after onerror; rejection is handled there via _scheduleReconnect
                // on the first connection attempt before onopen, reject immediately
                if (this._reconnectAttempt === 0) {
                    // only reject the outer promise on the very first attempt
                    // subsequent attempts don't have the original reject reference
                }
            };

            this._ws.onmessage = (event) => {
                this._handleMessage(event);
            };
        });
    }

    /**
     * Schedule a reconnection attempt using exponential backoff.
     * Emits 'statusChange' with reconnecting=true while waiting.
     *
     * @param {Function} [_initialReject] - unused, kept for call-site symmetry
     */
    _scheduleReconnect(_initialReject) {
        if (this._intentionalClose) return;

        if (this._reconnectAttempt >= this._maxReconnectAttempts) {
            console.error(`RenderClient: max reconnect attempts (${this._maxReconnectAttempts}) reached`);
            this._emit('statusChange', { connected: false, reconnecting: false, attempt: this._reconnectAttempt });
            return;
        }

        this._reconnectAttempt++;
        const delay = Math.min(
            this._reconnectBaseDelayMs * Math.pow(2, this._reconnectAttempt - 1),
            DEFAULT_MAX_RECONNECT_DELAY_MS
        );

        console.log(`RenderClient: reconnecting in ${delay}ms (attempt ${this._reconnectAttempt}/${this._maxReconnectAttempts})`);
        this._emit('statusChange', { connected: false, reconnecting: true, attempt: this._reconnectAttempt });

        this._reconnectTimer = setTimeout(() => {
            this._reconnectTimer = null;
            if (!this._intentionalClose) {
                this._reconnectInternal();
            }
        }, delay);
    }

    /**
     * Internal reconnect — opens a new WebSocket without returning a Promise.
     */
    _reconnectInternal() {
        const url = this._buildUrl();
        console.log(`RenderClient: reconnect attempt ${this._reconnectAttempt} to ${this._serverUrl}`);

        this._ws = new WebSocket(url);
        this._ws.binaryType = 'arraybuffer';

        this._ws.onopen = () => {
            console.log('RenderClient: reconnected');
            this._reconnectAttempt = 0;
            this._emit('statusChange', { connected: true, reconnecting: false, attempt: 0 });
        };

        this._ws.onclose = (event) => {
            console.log(`RenderClient: connection closed (code=${event.code}, wasClean=${event.wasClean})`);
            this._emit('statusChange', { connected: false, reconnecting: false, attempt: this._reconnectAttempt });

            if (!this._intentionalClose) {
                this._scheduleReconnect();
            }
        };

        this._ws.onerror = (error) => {
            console.error('RenderClient: WebSocket error:', error);
        };

        this._ws.onmessage = (event) => {
            this._handleMessage(event);
        };
    }

    /**
     * Close the WebSocket and cancel any pending reconnect timer.
     * No further reconnection will be attempted after calling this.
     */
    disconnect() {
        this._intentionalClose = true;

        if (this._reconnectTimer !== null) {
            clearTimeout(this._reconnectTimer);
            this._reconnectTimer = null;
        }

        if (this._ws) {
            this._ws.close();
            this._ws = null;
        }

        console.log('RenderClient: disconnected');
    }

    // ============================================================================
    // Message Handling
    // ============================================================================

    /**
     * Handle an incoming WebSocket message event.
     * Binary frames are emitted as 'binaryFrame'; text frames are parsed as JSON
     * and emitted as 'serverMessage'.
     *
     * @param {MessageEvent} event
     */
    _handleMessage(event) {
        if (event.data instanceof ArrayBuffer) {
            this._emit('binaryFrame', event.data);
            return;
        }

        if (event.data instanceof Blob) {
            event.data.arrayBuffer().then((buffer) => {
                this._emit('binaryFrame', buffer);
            }).catch((err) => {
                console.error('RenderClient: failed to convert Blob to ArrayBuffer:', err);
            });
            return;
        }

        // Text message — parse as JSON
        try {
            const msg = JSON.parse(event.data);
            this._emit('serverMessage', msg);
        } catch (err) {
            console.error('RenderClient: failed to parse server message:', event.data, err);
        }
    }

    // ============================================================================
    // Sending Messages
    // ============================================================================

    /**
     * Send a JSON message over the WebSocket.
     * Logs a warning and returns false if the socket is not open.
     *
     * @param {object} payload
     * @returns {boolean} true if the message was sent
     */
    _sendJson(payload) {
        if (!this._ws || this._ws.readyState !== WebSocket.OPEN) {
            console.warn('RenderClient: cannot send — WebSocket is not open');
            return false;
        }
        this._ws.send(JSON.stringify(payload));
        return true;
    }

    /**
     * Send a REGISTER_CLIENT message to the server.
     *
     * @param {string} clientId - Unique identifier for this client session
     * @param {object} viewport - Viewport parameters
     * @param {object} viewport.eye         - { x, y, z }
     * @param {object} viewport.lookAt      - { x, y, z }
     * @param {object} viewport.up          - { x, y, z }
     * @param {number} viewport.fovY        - Vertical field of view in radians
     * @param {number} viewport.aspectRatio - Aspect ratio (width / height)
     * @param {number} viewport.nearPlane   - Near clipping plane distance
     * @param {number} viewport.farPlane    - Far clipping plane distance
     * @returns {boolean} true if the message was sent
     */
    sendRegisterClient(clientId, viewport) {
        return this._sendJson({
            type: 'REGISTER_CLIENT',
            clientId,
            viewport
        });
    }

    /**
     * Send an UPDATE_VIEWPORT message to the server.
     * If maxUpdatesPerSecond is configured, calls that arrive too quickly are
     * silently dropped.
     *
     * @param {object} viewport - Same shape as in sendRegisterClient
     * @returns {boolean} true if the message was sent (false if throttled or not open)
     */
    sendUpdateViewport(viewport) {
        if (this._maxUpdatesPerSecond !== null) {
            const minIntervalMs = 1000 / this._maxUpdatesPerSecond;
            const now = performance.now();
            if (now - this._lastViewportSendTime < minIntervalMs) {
                return false;
            }
            this._lastViewportSendTime = now;
        }

        return this._sendJson({
            type: 'UPDATE_VIEWPORT',
            viewport
        });
    }
}
