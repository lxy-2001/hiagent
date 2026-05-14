create table sys_user (
    id varchar(36) primary key,
    username varchar(64) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(100) not null,
    enabled bit not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null
);

create table sys_role (
    id varchar(36) primary key,
    code varchar(64) not null unique,
    name varchar(100) not null,
    created_at datetime(6) not null
);

create table sys_user_role (
    user_id varchar(36) not null,
    role_id varchar(36) not null,
    primary key (user_id, role_id)
);

create table auth_refresh_token (
    id varchar(36) primary key,
    user_id varchar(36) not null,
    token_hash varchar(128) not null unique,
    revoked bit not null,
    expires_at datetime(6) not null,
    created_at datetime(6) not null
);

create table agent_session (
    id varchar(36) primary key,
    user_id varchar(36) not null,
    title varchar(200) not null,
    created_at datetime(6) not null
);

create table agent_task (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    user_id varchar(36) not null,
    user_input text not null,
    status varchar(32) not null,
    final_answer longtext null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null
);

create table agent_step (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    step_no int not null,
    step_type varchar(32) not null,
    tool_name varchar(100) null,
    input longtext null,
    output longtext null,
    status varchar(32) not null,
    latency_ms bigint not null,
    prompt_tokens int null,
    completion_tokens int null,
    error_message text null,
    created_at datetime(6) not null,
    unique key uk_agent_step_task_no (task_id, step_no),
    index idx_agent_step_task (task_id)
);

create table agent_tool (
    id varchar(36) primary key,
    name varchar(100) not null unique,
    description varchar(500) not null,
    schema_json json null,
    enabled bit not null,
    risk_level varchar(32) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null
);

create table agent_memory (
    id varchar(36) primary key,
    session_id varchar(36) not null,
    memory_type varchar(32) not null,
    content text not null,
    embedding_id varchar(100) null,
    created_at datetime(6) not null,
    index idx_agent_memory_session (session_id)
);

create table knowledge_document (
    id varchar(36) primary key,
    title varchar(200) not null,
    source varchar(500) not null,
    content_hash varchar(128) not null unique,
    created_at datetime(6) not null,
    updated_at datetime(6) not null
);

create table knowledge_chunk (
    id varchar(36) primary key,
    document_id varchar(36) not null,
    chunk_no int not null,
    content text not null,
    vector_id varchar(100) not null unique,
    content_hash varchar(128) not null,
    created_at datetime(6) not null,
    index idx_knowledge_chunk_document (document_id),
    index idx_knowledge_chunk_vector (vector_id)
);
