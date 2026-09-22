-- Explicit user-confirmed slots only; legacy agent_memory is not imported.
create table agent_confirmed_memory (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    memory_key varchar(32) not null,
    memory_value varchar(512) null,
    state varchar(16) not null,
    version bigint not null,
    source varchar(32) null,
    updated_at datetime(6) not null,
    constraint fk_confirmed_memory_session foreign key (session_id) references agent_session(id),
    constraint uk_confirmed_memory_slot unique (session_id, memory_key),
    constraint ck_confirmed_memory_key check (case memory_key when 'preferred_language' then 1 when 'project_stack' then 1 else 0 end = 1),
    constraint ck_confirmed_memory_version check (version > 0),
    constraint ck_confirmed_memory_state check (
        (state = 'ACTIVE' and memory_value is not null and char_length(trim(memory_value)) > 0 and source = 'USER_CONFIRMED' and source is not null)
        or (state = 'DELETED' and memory_value is null and source is null)
    )
);
