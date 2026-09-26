export function createApprovalClient(authedFetch) {
    const pending = new Map();
    const base = task => '/api/agent/tasks/' + encodeURIComponent(task) + '/approvals';
    async function read(response) {
        const body = await response.json();
        if (!response.ok) throw new Error(body.code || 'APPROVAL_UNAVAILABLE');
        return body;
    }
    return {
        async list(task) { return read(await authedFetch(base(task))); },
        decide(task, id, decision) {
            if (!['APPROVE','REJECT'].includes(decision)) return Promise.reject(new Error('INVALID_DECISION'));
            const key = task + '/' + id;
            if (pending.has(key)) return pending.get(key);
            const request = (async () => read(await authedFetch(base(task) + '/' + encodeURIComponent(id) + '/decision',
                {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({decision})})))();
            pending.set(key,request);
            request.finally(() => pending.delete(key)).catch(() => {});
            return request;
        }
    };
}

export function approvalText(item) {
    const status = item.status === 'APPROVED' ? '已批准，等待执行结果' : item.status === 'PENDING' ? '等待你的审批' : item.status;
    const fields = (item.argumentPreview || []).map(field => field.name + ': ' + (field.masked ? '[masked]' : field.value)
        + (field.truncated ? '（已截断）' : ''));
    return [status, item.actionSummary, '到期时间：' + item.expiresAt, ...fields,
        '调用结果：' + item.outcome + '；发起次数：' + item.dispatchCount,
        ...(item.outcome === 'UNKNOWN' ? ['可能已执行，取消不会撤销已发起的操作；请核对结果，不要重复提交。'] : [])].join('\n');
}

export function renderApprovals(container, items, decide, documentRef = document) {
    container.replaceChildren(...items.map(item => {
        const card = documentRef.createElement('div'); card.className='step';
        const text = documentRef.createElement('pre'); text.textContent=approvalText(item); card.append(text);
        if (item.status === 'PENDING') {
            const buttons = ['APPROVE','REJECT'].map(decision => {
                const button=documentRef.createElement('button'); button.textContent=decision === 'APPROVE' ? '批准原始调用' : '拒绝';
                button.addEventListener('click', async () => {
                    buttons.forEach(b => { b.disabled=true; });
                    try { await decide(item.approvalId,decision); }
                    catch (error) { text.textContent += '\n决定未确认：'+error.message+'。请刷新审批状态。'; }
                    finally { buttons.forEach(b => { b.disabled=false; }); }
                });
                return button;
            });
            card.append(...buttons);
        }
        return card;
    }));
}
