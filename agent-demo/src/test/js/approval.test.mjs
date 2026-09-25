import test from 'node:test';
import assert from 'node:assert/strict';
import {createApprovalClient, approvalText} from '../../main/resources/static/approval.js';
import {createSseParser} from '../../main/resources/static/run-events.js';

test('approval message distinguishes authorization from execution and masks private values', () => {
    const item = {status:'APPROVED', actionSummary:'Append', expiresAt:'soon', dispatchCount:0, outcome:'NOT_DISPATCHED',
        argumentPreview:[{name:'body',value:'private',masked:true,truncated:false},{name:'title',value:'<img>',masked:false,truncated:true}]};
    const text = approvalText(item);
    assert.match(text, /已批准，等待执行结果/);
    assert.doesNotMatch(text, /private|操作成功/);
    assert.match(text, /已截断/);
});

test('decision sends only decision and coalesces double clicks without retrying conflicts', async () => {
    const calls = []; let release;
    const client = createApprovalClient(async (url, options) => {
        calls.push({url,options}); await new Promise(resolve => { release=resolve; });
        return {ok:true,json:async () => ({status:'APPROVED'})};
    });
    const first = client.decide('run','approval','APPROVE');
    const second = client.decide('run','approval','APPROVE');
    release(); await Promise.all([first,second]);
    assert.equal(calls.length,1);
    assert.deepEqual(JSON.parse(calls[0].options.body),{decision:'APPROVE'});
    assert.doesNotMatch(calls[0].url,/token|password/);
});

test('approval and unknown nonterminal event frames advance the cursor', async () => {
    const received=[];
    const parser=createSseParser({taskId:'run',onEvent:e=>received.push(e.type)});
    for (const [index,type] of ['APPROVAL_REQUESTED','FUTURE_EVENT','APPROVAL_RESOLVED'].entries()) {
        const event={eventId:String(index+1),taskId:'run',runId:'run',type,occurredAt:'2026-09-25T00:00:00Z',payload:{}};
        await parser.push(new TextEncoder().encode('id: '+event.eventId+'\nevent: '+type+'\ndata: '+JSON.stringify(event)+'\n\n'));
    }
    assert.equal(parser.lastEventId(),'3');
    assert.deepEqual(received,['APPROVAL_REQUESTED','APPROVAL_RESOLVED']);
});
