import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

test("page uses authenticated observer, cancellation and lifecycle cleanup", async () => {
    const html = await readFile(new URL("../../main/resources/static/index.html", import.meta.url), "utf8");
    assert.match(html, /import \{observeRun\} from "\/run-events\.js"/);
    assert.match(html, /id="cancelBtn"/);
    assert.match(html, /Authorization/);
    assert.match(html, /pagehide/);
    assert.match(html, /textContent/);
    assert.doesNotMatch(html, /new EventSource/);
    assert.doesNotMatch(html, /[?&](token|accessToken)=/);
});
