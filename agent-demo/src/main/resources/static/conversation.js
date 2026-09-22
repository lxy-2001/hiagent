export function createConversationClient(fetcher) {
    let sessionId = null;
    const sessionPath = () => {
        if (!sessionId) throw Error('请先创建或选择会话');
        return `/api/agent/sessions/${encodeURIComponent(sessionId)}`;
    };
    async function json(url, options) {
        const response = await fetcher(url, options);
        const body = await response.json();
        if (!response.ok) throw Error(`${body.code || response.status}：请刷新后检查，再手动重试`);
        return body;
    }
    function version(value) {
        if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(value)) throw Error('invalid version');
        return value;
    }
    return {
        select(id) { sessionId = id; },
        reset() { sessionId = null; },
        request(input, requireEvidence = false) {
            const request = sessionId ? {input, sessionId} : {input};
            if (requireEvidence) request.requireEvidence = true;
            return request;
        },
        list(before = null) { return json(`/api/agent/sessions${before ? `?before=${encodeURIComponent(before)}` : ''}`); },
        turns(after = '0', until = null) {
            const query = new URLSearchParams({afterSequence: after});
            if (until !== null) query.set('untilSequence', until);
            return json(`${sessionPath()}/turns?${query}`);
        },
        memories() { return json(`${sessionPath()}/memories`); },
        writeMemory(key, value, expectedVersion) {
            return json(`${sessionPath()}/memories/${encodeURIComponent(key)}`, {method: 'PUT',
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify({value, expectedVersion: version(expectedVersion)})});
        },
        deleteMemory(key, expectedVersion) {
            return json(`${sessionPath()}/memories/${encodeURIComponent(key)}?expectedVersion=${version(expectedVersion)}`, {method: 'DELETE'});
        }
    };
}
