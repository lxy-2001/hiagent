-- H01: append-only conversation facts, committed compaction checkpoints and local storage.
-- Existing V1-V6 facts are left unchanged. All deletes are explicit; no cascade over history.
create table agent_session_context (
    session_id varchar(36) primary key,
    last_record_sequence bigint not null default 0,
    active_checkpoint_id varchar(36) null,
    context_revision bigint not null default 0,
    last_used_at datetime(6) not null,
    deleted_at datetime(6) null,
    constraint fk_context_session foreign key (session_id) references agent_session (id),
    constraint ck_context_sequence check (last_record_sequence >= 0),
    constraint ck_context_revision check (context_revision >= 0)
);
create index idx_context_recent on agent_session_context (deleted_at, last_used_at, session_id);

create table agent_history_batch (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    mutation_id varchar(128) not null,
    payload_hash varchar(64) not null,
    first_sequence bigint not null,
    last_sequence bigint not null,
    context_revision bigint not null,
    committed_at datetime(6) not null,
    constraint fk_history_batch_session foreign key (session_id) references agent_session (id),
    constraint uq_history_batch_mutation unique (session_id, mutation_id),
    constraint ck_history_batch_range check (first_sequence > 0 and last_sequence >= first_sequence)
);

create table agent_history_record (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    run_id varchar(36) not null,
    record_sequence bigint not null,
    kind varchar(32) not null,
    role varchar(20) null,
    occurred_at datetime(6) not null,
    decision_id varchar(128) null,
    call_id varchar(128) null,
    tool_name varchar(100) null,
    status varchar(64) null,
    inline_payload longtext null,
    payload_bytes bigint not null,
    artifact_id varchar(36) null,
    adapter_state_ref varchar(36) null,
    constraint fk_history_record_session foreign key (session_id) references agent_session (id),
    constraint fk_history_record_run foreign key (run_id) references agent_task (id),
    constraint uq_history_record_sequence unique (session_id, record_sequence),
    constraint ck_history_record_sequence check (record_sequence > 0),
    constraint ck_history_record_bytes check (payload_bytes >= 0)
);
create index idx_history_run on agent_history_record (run_id, record_sequence);

create table agent_context_checkpoint (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    revision bigint not null,
    base_checkpoint_id varchar(36) null,
    covered_through_sequence bigint not null,
    summary longtext not null,
    summary_tokens bigint not null,
    source_history_tokens bigint not null,
    retained_history_tokens bigint not null,
    tail_start_sequence bigint not null,
    model_request_key varchar(128) not null,
    prompt_version varchar(64) not null,
    model_profile_hash varchar(64) not null,
    created_at datetime(6) not null,
    constraint fk_checkpoint_session foreign key (session_id) references agent_session (id),
    constraint fk_checkpoint_base foreign key (base_checkpoint_id) references agent_context_checkpoint (id),
    constraint uq_checkpoint_revision unique (session_id, revision),
    constraint ck_checkpoint_coverage check (covered_through_sequence >= 0 and tail_start_sequence > covered_through_sequence)
);
alter table agent_session_context add constraint fk_context_active_checkpoint
    foreign key (active_checkpoint_id) references agent_context_checkpoint (id);

create table agent_context_model_call (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    run_id varchar(36) not null,
    request_key varchar(128) not null,
    purpose varchar(32) not null,
    status varchar(32) not null,
    provider varchar(100) not null,
    model varchar(100) not null,
    input_tokens bigint null,
    output_tokens bigint null,
    cached_input_tokens bigint null,
    reasoning_tokens bigint null,
    usage_source varchar(32) not null,
    estimated_input_tokens bigint not null,
    output_reserve_tokens bigint not null,
    safety_tokens bigint not null,
    estimate_source varchar(100) not null,
    source_history_tokens bigint null,
    retained_history_tokens bigint null,
    failure_code varchar(128) null,
    started_at datetime(6) not null,
    finished_at datetime(6) null,
    constraint fk_model_call_session foreign key (session_id) references agent_session (id),
    constraint fk_model_call_run foreign key (run_id) references agent_task (id),
    constraint uq_model_call_request unique (run_id, request_key)
);
create index idx_model_call_session on agent_context_model_call (session_id, started_at, id);

create table agent_artifact (
    id varchar(36) primary key,
    owner_id varchar(36) not null,
    session_id varchar(36) not null,
    run_id varchar(36) not null,
    kind varchar(32) not null,
    state varchar(32) not null,
    relative_path varchar(500) not null,
    saved_bytes bigint not null,
    original_bytes bigint null,
    sha256 varchar(64) null,
    preview longtext null,
    truncated bit not null,
    redacted bit not null,
    created_at datetime(6) not null,
    last_accessed_at datetime(6) not null,
    deleted_at datetime(6) null,
    constraint fk_artifact_session foreign key (session_id) references agent_session (id),
    constraint fk_artifact_run foreign key (run_id) references agent_task (id),
    constraint ck_artifact_bytes check (saved_bytes >= 0)
);
create index idx_artifact_recent on agent_artifact (session_id, last_accessed_at, id);
create index idx_artifact_state on agent_artifact (state, created_at, id);

create table agent_responses_continuation (
    id varchar(36) primary key,
    owner_id varchar(36) not null,
    session_id varchar(36) not null,
    run_id varchar(36) not null,
    provider varchar(100) not null,
    model varchar(100) not null,
    request_key varchar(128) not null,
    payload_hash varchar(64) not null,
    payload longtext not null,
    payload_bytes bigint not null,
    reasoning_token_hint bigint null,
    created_at datetime(6) not null,
    constraint uq_continuation_request unique (run_id, request_key),
    constraint fk_continuation_session foreign key (session_id) references agent_session (id),
    constraint fk_continuation_run foreign key (run_id) references agent_task (id),
    constraint ck_continuation_bytes check (payload_bytes >= 0 and payload_bytes <= 8388608)
);
create index idx_continuation_session on agent_responses_continuation (session_id, created_at, id);

create table auth_access_revocation (
    jti varchar(128) primary key,
    expires_at datetime(6) not null
);
create index idx_access_revocation_expiry on auth_access_revocation (expires_at, jti);
