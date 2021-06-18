alter table events.auth_events alter column sync type smallint using CASE WHEN sync THEN 1 ELSE 0 END;
alter table events.auth_events alter column sync set default 0;
