#!/usr/bin/env bash
# Regenerates gen/ratelimitpb from the wire contract in contracts/proto/.
# The generated code IS committed: docker build and CI must work without protoc.
#
# Prereqs:
#   brew install protobuf
#   go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
#   go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
#
# Run from anywhere; it re-anchors itself at the repo root.
set -euo pipefail
cd "$(dirname "$0")/../.."

PATH="$PATH:$(go env GOPATH)/bin" protoc \
  --proto_path=contracts/proto \
  --go_out=services/rate-limiter/gen/ratelimitpb \
  --go_opt=paths=source_relative \
  --go-grpc_out=services/rate-limiter/gen/ratelimitpb \
  --go-grpc_opt=paths=source_relative \
  ratelimit.proto

echo "generated: services/rate-limiter/gen/ratelimitpb/"
