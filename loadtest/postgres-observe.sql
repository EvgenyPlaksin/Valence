SELECT now() AS sampled_at,
       count(*) FILTER (WHERE state = 'active') AS active_connections,
       count(*) FILTER (
           WHERE state = 'active'
             AND wait_event_type IS NOT NULL
             AND wait_event_type <> 'Lock'
       ) AS non_lock_waiters,
       count(*) FILTER (WHERE state = 'active' AND wait_event_type = 'Lock') AS lock_waiters,
       (SELECT deadlocks
        FROM pg_stat_database
        WHERE datname = current_database()) AS deadlocks
FROM pg_stat_activity
WHERE datname = current_database();

SELECT datname,
       numbackends,
       xact_commit,
       xact_rollback,
       deadlocks,
       blks_read,
       blks_hit,
       tup_inserted,
       tup_updated,
       tup_deleted
FROM pg_stat_database
WHERE datname = current_database();

SELECT schemaname,
       relname,
       seq_scan,
       idx_scan,
       n_tup_ins,
       n_tup_upd,
       n_tup_del,
       n_live_tup,
       n_dead_tup
FROM pg_stat_user_tables
ORDER BY relname;

SELECT indexrelname,
       idx_scan,
       idx_tup_read,
       idx_tup_fetch
FROM pg_stat_user_indexes
ORDER BY indexrelname;

SELECT calls,
       mean_exec_time,
       max_exec_time,
       rows,
       query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
