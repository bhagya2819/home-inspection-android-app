# Build: static Go binary. Run: scratch image (~10 MB), non-root.
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# The scratch image has no root certificates; HTTPS out (DeepSeek) needs them.
RUN apk add --no-cache ca-certificates
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/home-inspection-api ./cmd/home-inspection-api

FROM scratch
COPY --from=build /out/home-inspection-api /home-inspection-api
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["/home-inspection-api", "healthcheck"]
ENTRYPOINT ["/home-inspection-api"]
CMD ["serve"]
