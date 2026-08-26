#!/bin/sh
# Starts as root, normalizes the bind-mounted Docker socket's group
# ownership so the unprivileged `floci` user can reach it on any host,
# then re-executes this script as `floci` via gosu. The second invocation
# falls through to exec the user's command.

set -eu

if [ "$(id -u)" = '0' ]; then
    if [ -S /var/run/docker.sock ]; then
        sock_gid="$(stat -c '%g' /var/run/docker.sock)"
        if [ "$sock_gid" != '0' ]; then
            group_name="$(getent group "$sock_gid" | cut -d: -f1)" || group_name=''
            if [ -z "$group_name" ]; then
                groupadd -g "$sock_gid" docker-host
                group_name='docker-host'
            fi
            usermod -aG "$group_name" floci
        fi
    fi

    # Re-own state dir for the case where a host bind-mount arrives with
    # ownership the floci user cannot write to.
    if [ -d /app/data ]; then
        chown -R floci:root /app/data 2>/dev/null || true
    fi

    exec gosu floci "$0" "$@"
fi

# TLS: generate a self-signed certificate on first start unless one was provided,
# so the TLS listener (default 4589) comes up alongside the plaintext port.
# FLOCI_GCP_TLS_ENABLED=false skips generation, leaving the TLS listener closed.
# Extra subjectAltNames: FLOCI_GCP_TLS_EXTRA_SANS=DNS:myhost,IP:10.0.0.5
if [ "${FLOCI_GCP_TLS_ENABLED:-true}" != 'false' ] && [ -z "${FLOCI_GCP_TLS_CERTIFICATE:-}" ]; then
    if command -v openssl >/dev/null 2>&1; then
        tls_dir="${FLOCI_GCP_TLS_DIR:-/app/tls}"
        if [ ! -f "$tls_dir/cert.pem" ] || [ ! -f "$tls_dir/key.pem" ]; then
            mkdir -p "$tls_dir"
            sans='DNS:localhost,DNS:floci-gcp,DNS:host.docker.internal,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1'
            if [ -n "${FLOCI_GCP_TLS_EXTRA_SANS:-}" ]; then
                sans="$sans,${FLOCI_GCP_TLS_EXTRA_SANS}"
            fi
            openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 \
                -days 3650 -nodes -subj '/CN=floci-gcp' -addext "subjectAltName=$sans" \
                -keyout "$tls_dir/key.pem" -out "$tls_dir/cert.pem" 2>/dev/null \
                || echo 'floci-gcp: TLS certificate generation failed' >&2
        fi
        if [ -f "$tls_dir/cert.pem" ] && [ -f "$tls_dir/key.pem" ]; then
            export FLOCI_GCP_TLS_CERTIFICATE="$tls_dir/cert.pem"
            export FLOCI_GCP_TLS_CERTIFICATE_KEY="$tls_dir/key.pem"
            # Log the fingerprint so a certificate fetched from /_floci-gcp/tls/cert can
            # be verified out-of-band against the container logs.
            fingerprint="$(openssl x509 -in "$tls_dir/cert.pem" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)"
            echo "floci-gcp: TLS certificate SHA-256 fingerprint: ${fingerprint:-unavailable}"
        else
            echo 'floci-gcp: TLS listener disabled' >&2
        fi
    else
        echo 'floci-gcp: openssl not found; TLS listener disabled' >&2
    fi
fi

exec "$@"
