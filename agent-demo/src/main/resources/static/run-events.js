const EVENT_TYPES = new Set(["RUN_CREATED", "RUN_STARTED", "AGENT_STEP", "RUN_TERMINATED"]);
const TERMINAL = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED"]);
const MAX_EVENT_ID = 9223372036854775807n;

export function createSseParser({taskId, onEvent, maximumFrameBytes = 32768, initialEventId = "0"}) {
    if (!taskId || typeof onEvent !== "function") throw new TypeError("taskId and onEvent are required");
    let pending = new Uint8Array(0);
    let lastHandled = parseEventId(initialEventId, true);
    const decoder = new TextDecoder("utf-8", {fatal: true});

    async function push(chunk) {
        const incoming = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
        const joined = new Uint8Array(pending.length + incoming.length);
        joined.set(pending);
        joined.set(incoming, pending.length);
        pending = joined;
        while (true) {
            const boundary = findBoundary(pending);
            if (!boundary) break;
            const raw = pending.slice(0, boundary.index);
            pending = pending.slice(boundary.index + boundary.length);
            if (raw.length > maximumFrameBytes) throw new Error("SSE_FRAME_TOO_LARGE");
            if (raw.length === 0) continue;
            const parsed = parseFrame(decoder.decode(raw), taskId);
            if (!parsed) continue;
            const id = parseEventId(parsed.id, false);
            if (id <= lastHandled) continue;
            await onEvent(parsed.event);
            lastHandled = id;
        }
        if (pending.length > maximumFrameBytes) throw new Error("SSE_FRAME_TOO_LARGE");
    }

    return {
        push,
        finish() { pending = new Uint8Array(0); },
        lastEventId() { return lastHandled.toString(); }
    };
}

export function observeRun(options) {
    const controller = new AbortController();
    const generation = options.generation;
    const state = {lastEventId: "0", stopped: false};
    const sleep = options.sleep || (milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)));
    const fetchImpl = options.fetchImpl || fetch;

    async function authed(url, init = {}) {
        const headers = Object.assign({}, init.headers || {}, {Authorization: `Bearer ${options.token}`});
        return fetchImpl(url, Object.assign({}, init, {headers, signal: controller.signal}));
    }

    async function loadFinal() {
        for (let attempt = 0; attempt < 30 && !state.stopped; attempt++) {
            let response;
            try { response = await authed(`/api/agent/tasks/${encodeURIComponent(options.taskId)}`); }
            catch (error) { if (controller.signal.aborted) return; response = null; }
            if (response?.ok) {
                const snapshot = await response.json();
                if (options.isCurrent?.(generation) === false) return;
                options.onSnapshot?.(snapshot);
                if (TERMINAL.has(snapshot.status)) {
                    const steps = await authed(`/api/agent/tasks/${encodeURIComponent(options.taskId)}/steps`);
                    if (steps.ok && options.isCurrent?.(generation) !== false) options.onSteps?.(await steps.json());
                    return;
                }
            }
            if (attempt < 29) await sleep(2000);
        }
        options.onState?.("manual-refresh");
    }

    async function run() {
        const delays = [1000, 2000, 4000];
        for (let attempt = 0; attempt <= delays.length && !state.stopped; attempt++) {
            const headers = {Accept: "text/event-stream"};
            if (state.lastEventId !== "0") headers["Last-Event-ID"] = state.lastEventId;
            let response;
            try { response = await authed(`/api/agent/tasks/${encodeURIComponent(options.taskId)}/events`, {headers}); }
            catch (error) { if (controller.signal.aborted) return; response = null; }
            if (response?.status === 204) return loadFinal();
            if (response?.status === 410) return loadFinal();
            if (response && [400, 401, 404].includes(response.status)) {
                options.onState?.(`stopped-${response.status}`);
                return;
            }
            if (response?.status === 200 && response.body) {
                const parser = createSseParser({taskId: options.taskId, initialEventId: state.lastEventId,
                    onEvent: async event => {
                        if (options.isCurrent?.(generation) === false) return;
                        await options.onEvent?.(event);
                        state.lastEventId = event.eventId;
                    }});
                const reader = response.body.getReader();
                try {
                    while (!state.stopped) {
                        const {done, value} = await reader.read();
                        if (done) break;
                        await parser.push(value);
                    }
                    parser.finish();
                } finally { reader.releaseLock(); }
            }
            if (attempt < delays.length) await sleep(delays[attempt]);
        }
        if (!state.stopped) await loadFinal();
    }

    return {
        start: run,
        stop() { state.stopped = true; controller.abort(); },
        lastEventId() { return state.lastEventId; }
    };
}

function parseFrame(text, expectedTaskId) {
    let id = null;
    let type = "message";
    const data = [];
    for (const line of text.replaceAll("\r\n", "\n").split("\n")) {
        if (line.startsWith(":")) continue;
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        let value = separator < 0 ? "" : line.slice(separator + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "id") id = value;
        else if (field === "event") type = value;
        else if (field === "data") data.push(value);
    }
    if (id === null && data.length === 0) return null;
    parseEventId(id, false);
    const event = JSON.parse(data.join("\n"));
    if (!EVENT_TYPES.has(type) || event.type !== type || event.eventId !== id
            || event.taskId !== expectedTaskId || event.runId !== expectedTaskId
            || typeof event.occurredAt !== "string" || !("payload" in event)) {
        throw new Error("INVALID_EVENT_ENVELOPE");
    }
    return {id, event};
}

function parseEventId(value, allowZero) {
    if (typeof value !== "string" || !(allowZero ? /^(0|[1-9][0-9]{0,18})$/ : /^[1-9][0-9]{0,18}$/.test(value))) {
        throw new Error("INVALID_EVENT_ID");
    }
    const parsed = BigInt(value);
    if (parsed > MAX_EVENT_ID) throw new Error("INVALID_EVENT_ID");
    return parsed;
}

function findBoundary(bytes) {
    for (let i = 0; i < bytes.length - 1; i++) {
        if (bytes[i] === 10 && bytes[i + 1] === 10) return {index: i, length: 2};
        if (i + 3 < bytes.length && bytes[i] === 13 && bytes[i + 1] === 10
                && bytes[i + 2] === 13 && bytes[i + 3] === 10) return {index: i, length: 4};
    }
    return null;
}
