import test from "node:test";
import assert from "node:assert/strict";
import {createSseParser} from "../../main/resources/static/run-events.js";

const encoder = new TextEncoder();
const event = id => ({eventId: String(id), taskId: "task", runId: "task", type: "AGENT_STEP",
    occurredAt: "2026-09-15T00:00:00Z", payload: {summary: "你好"}});
const frame = (id, newline = "\n") => `id: ${id}${newline}event: AGENT_STEP${newline}data: ${JSON.stringify(event(id))}${newline}${newline}`;

test("parses split UTF-8, CRLF, comments and multiple frames incrementally", async () => {
    const received = [];
    const parser = createSseParser({taskId: "task", onEvent: async value => received.push(value)});
    const bytes = encoder.encode(`: ping\r\n\r\n${frame(1, "\r\n")}${frame(2)}`);
    const split = bytes.findIndex((value, index) => value >= 0x80 && index > 0) + 1;
    await parser.push(bytes.slice(0, split));
    await parser.push(bytes.slice(split));
    assert.deepEqual(received.map(value => value.eventId), ["1", "2"]);
    assert.equal(parser.lastEventId(), "2");
});

test("advances BigInt cursor only after handler completes and ignores duplicate frames", async () => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const parser = createSseParser({taskId: "task", onEvent: async () => gate});
    const pending = parser.push(encoder.encode(frame("9007199254740993")));
    assert.equal(parser.lastEventId(), "0");
    release();
    await pending;
    assert.equal(parser.lastEventId(), "9007199254740993");
    await parser.push(encoder.encode(frame("9007199254740993")));
    assert.equal(parser.lastEventId(), "9007199254740993");
});

test("drops a half frame at EOF and enforces raw frame byte limit", async () => {
    const parser = createSseParser({taskId: "task", maximumFrameBytes: 64, onEvent: async () => {}});
    await parser.push(encoder.encode("id: 1\ndata: half"));
    parser.finish();
    await assert.rejects(() => parser.push(encoder.encode("x".repeat(65))), /SSE_FRAME_TOO_LARGE/);
});
