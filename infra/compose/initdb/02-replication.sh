#!/bin/sh
# Fresh-volume setup for streaming replication + Debezium logical decoding.
# (For an already-initialized volume, run the equivalent by hand — see replica/README.md)
set -e

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres <<-SQL
  CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'repl_local';
SQL

echo "host replication replicator all scram-sha-256" >> "$PGDATA/pg_hba.conf"
