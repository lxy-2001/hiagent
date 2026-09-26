-- Feature 004: stable per-session ordering, independent of Run terminal status.
alter table agent_session add column last_turn_sequence bigint not null default 0;
alter table agent_task add column turn_sequence bigint null;

create temporary table conversation_turn_backfill as
select id, session_id, row_number() over (partition by session_id order by created_at, id) as sequence_no
from agent_task;

update agent_task
set turn_sequence = (select sequence_no from conversation_turn_backfill where conversation_turn_backfill.id = agent_task.id);
update agent_session
set last_turn_sequence = (select coalesce(max(sequence_no), 0) from conversation_turn_backfill where conversation_turn_backfill.session_id = agent_session.id);
drop table conversation_turn_backfill;

alter table agent_task modify column turn_sequence bigint not null;
alter table agent_task add constraint ck_agent_task_turn_positive check (turn_sequence > 0);
alter table agent_session add constraint ck_agent_session_turn_nonnegative check (last_turn_sequence >= 0);
create unique index uk_agent_task_session_turn on agent_task (session_id, turn_sequence);
create index idx_agent_task_session_status_turn on agent_task (session_id, status, turn_sequence);
create index idx_agent_session_owner_created on agent_session (user_id, created_at, id);
