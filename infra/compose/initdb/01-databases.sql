-- db-per-service: each service gets its own database inside the single local
-- Postgres container. Added here as services land.

CREATE DATABASE linkdb;
GRANT ALL PRIVILEGES ON DATABASE linkdb TO agora;
