-- db-per-service: each service gets its own database inside the single local
-- Postgres container. Runs only on a fresh pg-data volume; for a live volume
-- add via: docker exec agora-postgres psql -U agora -d postgres -c "CREATE DATABASE <db>;"

CREATE DATABASE linkdb;
CREATE DATABASE identitydb;
CREATE DATABASE catalogdb;
CREATE DATABASE inventorydb;
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
CREATE DATABASE chatdb;
CREATE DATABASE notifdb;
CREATE DATABASE feeddb;
CREATE DATABASE analyticsdb;
