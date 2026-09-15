-- Feature 003: durable run lifecycle facts. Event history remains process-local.
alter table agent_task add column started_at datetime(6) null;
alter table agent_task add column finished_at datetime(6) null;
alter table agent_task add column cancel_requested bit not null default 0;
alter table agent_task add column termination_reason varchar(64) null;
alter table agent_task add column runtime_reason varchar(64) null;
alter table agent_task add column error_code varchar(64) null;
alter table agent_task add column recording_complete bit not null default 0;
alter table agent_task add column prompt_tokens int null;
alter table agent_task add column completion_tokens int null;
alter table agent_task add column total_tokens int null;

alter table agent_step add column decision_id varchar(128) null;
alter table agent_step add column call_id varchar(128) null;
alter table agent_step add column error_code varchar(128) null;
alter table agent_step add column terminal bit not null default 0;

update agent_task
set termination_reason = 'COMPLETED',
    finished_at = updated_at,
    recording_complete = 0
where status = 'SUCCEEDED'
  and final_answer is not null
  and char_length(trim(final_answer)) > 0;

update agent_task
set status = 'FAILED',
    final_answer = null,
    termination_reason = 'LEGACY_FAILURE',
    error_code = 'LEGACY_FAILURE',
    finished_at = updated_at,
    recording_complete = 0
where status = 'FAILED'
   or (status = 'SUCCEEDED' and (final_answer is null or char_length(trim(final_answer)) = 0));

create index idx_agent_task_status_id on agent_task (status, id);
