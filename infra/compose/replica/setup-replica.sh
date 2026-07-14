#!/bin/sh
# Replica entrypoint: seed from pg_basebackup on first boot (-R writes
# standby.signal + primary_conninfo), then run as hot standby.
set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "replica: seeding from primary via pg_basebackup..."
  export PGPASSWORD="$REPLICATION_PASSWORD"
  until pg_basebackup -h postgres -p 5432 -U replicator -D "$PGDATA" -R -X stream; do
    echo "replica: primary not ready, retrying in 2s"
    sleep 2
  done
  chmod 700 "$PGDATA"
fi

exec postgres
