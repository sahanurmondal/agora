FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# gen/ is committed, so no protoc needed at image-build time
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/rate-limiter ./cmd/ratelimiter

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/rate-limiter /rate-limiter
EXPOSE 9095
ENTRYPOINT ["/rate-limiter"]
