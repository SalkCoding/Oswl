-- Retention policy for old scans: scans beyond the per-project retain count keep
-- only an aggregate summary here, and their scan_components/dependency_paths rows are deleted.
alter table scan_results add column archived boolean not null default false;
alter table scan_results add column archived_at timestamp;
alter table scan_results add column archived_component_count integer;
alter table scan_results add column archived_security_critical integer;
alter table scan_results add column archived_security_high integer;
alter table scan_results add column archived_security_medium integer;
alter table scan_results add column archived_security_low integer;
alter table scan_results add column archived_security_unscored integer;
alter table scan_results add column archived_license_critical integer;
alter table scan_results add column archived_license_high integer;
alter table scan_results add column archived_license_medium integer;
alter table scan_results add column archived_license_low integer;

create index idx_scan_results_archived on scan_results(project_id, archived);
