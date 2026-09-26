-- Feature 006: invocation audit and owner-controlled approvals; no replayable arguments.
create table agent_tool_invocation (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    call_id varchar(128) not null,
    owner_id varchar(36) not null,
    tool_name varchar(100) not null,
    server_id varchar(32) null,
    remote_tool_name varchar(64) null,
    definition_version varchar(64) not null,
    policy_version varchar(64) not null,
    arguments_digest varchar(64) not null,
    risk varchar(16) not null,
    policy_action varchar(32) not null,
    effect varchar(16) not null,
    action_summary varchar(256) null,
    argument_preview_json text null,
    approval_status varchar(16) null,
    created_at datetime(6) not null,
    expires_at datetime(6) null,
    decided_at datetime(6) null,
    decision_source varchar(16) null,
    dispatch_count int not null default 0,
    dispatch_at datetime(6) null,
    outcome varchar(16) not null,
    error_code varchar(64) null,
    approval_wait_ms bigint null,
    execution_ms bigint null,
    constraint uk_tool_invocation_call unique (task_id, call_id),
    constraint fk_tool_invocation_task foreign key (task_id) references agent_task(id) on delete cascade,
    constraint ck_invocation_policy check (regexp_like(policy_action, '^(ALLOW|DENY|REQUIRE_APPROVAL)$')
        and regexp_like(risk, '^(LOW|MEDIUM|HIGH)$') and regexp_like(effect, '^(READ_ONLY|WRITE)$')
        and (policy_action <> 'ALLOW' or (risk <> 'HIGH' and effect <> 'WRITE'))),
    constraint ck_invocation_dispatch check (
        (dispatch_count = 0 and dispatch_at is null and outcome = 'NOT_DISPATCHED') or
        (dispatch_count = 1 and dispatch_at is not null and regexp_like(outcome, '^(SUCCEEDED|FAILED|UNKNOWN)$')
            and policy_action <> 'DENY'
            and (policy_action <> 'REQUIRE_APPROVAL' or (approval_status is not null and approval_status = 'APPROVED')))),
    constraint ck_invocation_approval check (
        (approval_status is null and action_summary is null and argument_preview_json is null
            and expires_at is null and decided_at is null and decision_source is null and approval_wait_ms is null)
        or (approval_status is not null and policy_action = 'REQUIRE_APPROVAL'
            and action_summary is not null and argument_preview_json is not null and expires_at is not null
            and octet_length(argument_preview_json) <= 4096
            and ((approval_status = 'PENDING' and decided_at is null and decision_source is null
                    and approval_wait_ms is null and dispatch_count = 0)
                or (regexp_like(approval_status, '^(APPROVED|REJECTED|EXPIRED|CANCELLED|INVALIDATED|INTERRUPTED)$')
                    and decided_at is not null and decision_source is not null
                    and (decision_source <> 'USER' or approval_wait_ms is not null)
                    and regexp_like(decision_source, '^(USER|TTL|RUN_TIMEOUT|CANCEL|POLICY|PROCESS)$'))))),
    constraint ck_invocation_durations check ((approval_wait_ms is null or approval_wait_ms >= 0)
        and (execution_ms is null or execution_ms >= 0)),
    constraint ck_invocation_remote check ((server_id is null and remote_tool_name is null)
        or (server_id is not null and remote_tool_name is not null))
);
create index idx_tool_invocation_task_created on agent_tool_invocation(task_id, created_at, id);
