#!/bin/sh
# Register (or update) a Debezium connector. Usage: ./register.sh catalog-outbox
set -e
NAME="${1:?connector name, e.g. catalog-outbox}"
DIR="$(dirname "$0")"
CONNECT_URL="${CONNECT_URL:-http://localhost:8383}"

curl -sf -X PUT "$CONNECT_URL/connectors/$NAME/config" \
  -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json;print(json.dumps(json.load(open('$DIR/$NAME.json'))['config']))")" \
  && echo "\nconnector $NAME registered"
