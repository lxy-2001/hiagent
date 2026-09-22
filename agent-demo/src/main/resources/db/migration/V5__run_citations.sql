alter table agent_task add column require_evidence boolean not null default false;
alter table agent_task add column citations_json longtext null;
