# TLS / HTTPS

By default floci-gcp serves plain HTTP on port `4588`. Most GCP SDKs skip TLS when you point
them at an emulator through a `*_EMULATOR_HOST` variable, so this is all you need for local
development.

Enable TLS when a client insists on `https://` — a tool that only accepts HTTPS endpoints, a
`gcloud` custom `api_endpoint_overrides`, or code under test that builds its channel with real
transport credentials.

```bash
docker run -e FLOCI_GCP_TLS_ENABLED=true -p 4588:4588 floci/floci-gcp
```

## HTTP and HTTPS share one port

floci-gcp does not open a separate HTTPS port. When TLS is enabled, Quarkus moves to two
loopback-only ports and a small TCP proxy takes over the public port, inspecting the first byte
of every connection:

```
                          :4588  (public)            :443  (optional)
                             |                          |
                       TlsProxyServer — first-byte protocol sniff
                             |                          |
   0x16 (TLS ClientHello) ──► 127.0.0.1:4581   Quarkus HTTPS
   anything else          ──► 127.0.0.1:4580   Quarkus HTTP / h2c
```

Both schemes therefore work against the same URL, and nothing you already have configured
breaks when you switch TLS on:

```bash
curl     http://localhost:4588/health   # still works
curl -k https://localhost:4588/health   # now works too
```

### gRPC keeps working

Pub/Sub, Firestore, Datastore and Secret Manager speak gRPC on this same port. The proxy is a
raw TCP pipe, so both transports are handled:

- **Plaintext gRPC** opens with the HTTP/2 preface (`PRI * HTTP/2.0`), which is not `0x16`, so
  it reaches the plaintext backend exactly as before.
- **gRPC over TLS** opens with a ClientHello, reaches the HTTPS backend, and Quarkus negotiates
  `h2` over ALPN.

### Port 443

The proxy also binds `443` by default, because GCP SDKs and `gcloud` assume HTTPS on the
conventional port when no explicit port is given. Binding a privileged port needs elevated
privileges; when it fails, floci-gcp logs a warning and carries on serving the main port. Set
`FLOCI_GCP_TLS_HTTPS_PORT=0` to skip the attempt entirely.

## Certificates

With no certificate configured, floci-gcp generates a self-signed one on first start and stores
it under `{FLOCI_GCP_STORAGE_PERSISTENT_PATH}/tls/`:

```
data/tls/floci-gcp-selfsigned.crt
data/tls/floci-gcp-selfsigned.key
data/tls/floci-gcp-selfsigned.metadata.json
```

The certificate is reused across restarts. It is regenerated automatically when the hostname
configuration changes — the metadata file records which names went into it, and a changed
`FLOCI_GCP_HOSTNAME` or `FLOCI_GCP_BASE_URL` triggers a new one.

Default Subject Alternative Names:

| SAN | Covers |
|---|---|
| `localhost`, `127.0.0.1`, `0.0.0.0`, `*.localhost` | Local access |
| `localhost.floci.io`, `*.localhost.floci.io` | GCS virtual-hosted-style bucket URLs served through the embedded DNS |
| `*.googleapis.com` | Clients addressing floci-gcp under real service hostnames (`storage.googleapis.com`, `pubsub.googleapis.com`, …) |
| `host.docker.internal` | Spawned containers reaching floci-gcp when it runs on the host |

Any custom `FLOCI_GCP_HOSTNAME` and the host from `FLOCI_GCP_BASE_URL` are appended.

### Trusting the certificate

The generated certificate is a CA (a trust anchor), so a client that adds it to its CA bundle
can verify the connection rather than skipping verification. Fetch it over plain HTTP:

```bash
curl http://localhost:4588/_floci-gcp/tls-cert -o floci-gcp.crt
curl --cacert floci-gcp.crt https://localhost:4588/health
```

For a Java SDK client:

```bash
keytool -importcert -alias floci-gcp -file floci-gcp.crt \
        -keystore truststore.jks -storepass changeit -noprompt
```

### Bringing your own certificate

Point floci-gcp at PEM files and it skips generation entirely:

```bash
docker run \
  -e FLOCI_GCP_TLS_ENABLED=true \
  -e FLOCI_GCP_TLS_CERT_PATH=/certs/server.crt \
  -e FLOCI_GCP_TLS_KEY_PATH=/certs/server.key \
  -v "$PWD/certs:/certs:ro" \
  -p 4588:4588 floci/floci-gcp
```

Both paths must be set together, and both must be readable — floci-gcp fails fast at startup
otherwise. Setting `FLOCI_GCP_TLS_SELF_SIGNED=false` without supplying a certificate is also a
startup error, which is the point: it stops floci-gcp from silently generating a certificate
you did not intend to use.

## Testing

The compatibility suite runs with TLS enabled by default (`docker-compose.yml`,
`make compat-docker`, `make run` and CI). Since HTTP and HTTPS share the port, every
existing suite keeps using `http://` unchanged and doubles as a regression guard for the
proxy; `sdk-test-java`'s `TlsTest` additionally drives GCS over HTTPS and Pub/Sub and
Secret Manager over gRPC-with-TLS, trusting the certificate fetched from
`/_floci-gcp/tls-cert`.

Note that the `*_EMULATOR_HOST` variables cannot be used to reach floci-gcp over TLS: the
GCP SDKs call `usePlaintext()` whenever one is set, whatever scheme you give it. To use
TLS, configure the client's endpoint and transport credentials explicitly.

## Reference

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_TLS_ENABLED` | `false` | Serve HTTPS alongside HTTP on the public port |
| `FLOCI_GCP_TLS_SELF_SIGNED` | `true` | Auto-generate a self-signed certificate when no cert/key is supplied |
| `FLOCI_GCP_TLS_CERT_PATH` | _(none)_ | PEM certificate file |
| `FLOCI_GCP_TLS_KEY_PATH` | _(none)_ | PEM private key file |
| `FLOCI_GCP_TLS_HTTPS_PORT` | `443` | Extra port to bind for HTTPS. `0` disables |

| Endpoint | Description |
|---|---|
| `GET /_floci-gcp/tls-cert` | The active certificate in PEM form; `404` with a JSON explanation when TLS is off |
