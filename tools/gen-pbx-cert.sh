#!/usr/bin/env bash
#
# gen-pbx-cert.sh — private CA + server certificate for a self-hosted PBX.
#
# The phone (gsm2sip) validates the PBX certificate against the Android system
# trust store.  A self-hosted FreePBX normally has no certificate at all, or a
# self-signed one that no public CA ever signed, so the handshake fails before
# a single SIP packet moves.  This script produces the two halves of the fix:
#
#   * a private CA whose certificate is imported into the app on the phone
#     (the app then trusts that CA and nothing else), and
#   * a server certificate for the PBX, signed by that CA, carrying the
#     correct SAN for the name or address the phone connects to.
#
# The generated keys are for a private gateway on a private network.  They are
# not suitable for anything that faces the public internet: the CA is a plain
# offline CA, and anyone holding the CA key can mint certificates for anything.
#
# Usage:  tools/gen-pbx-cert.sh --host <name-or-ip> [options]
#
set -u

PROG="$(basename "$0")"

OUTDIR="pbx-tls"
DAYS=3650
BITS=2048
CA_NAME="gsm2sip private CA"
CA_ORG="gsm2sip"
EC_KEY=0
HOSTS=""
EXTRA_SANS=""
FORCE=0

usage() {
    cat <<EOF
$PROG — generate a private CA and a PBX server certificate for SIP over TLS.

USAGE
    $PROG --host <name-or-ip> [--host <name-or-ip> ...] [options]

REQUIRED
    --host <name>       Name or IP the phone connects to.  Repeat for several
                        (a name and an address may be given together).  A
                        literal address becomes an IP SAN, anything else a
                        DNS SAN.  The name MUST match what the phone is
                        configured with — the certificate is checked against
                        it, and a mismatch fails the handshake.

OPTIONS
    --outdir <dir>      Where to write the files (default: ./pbx-tls)
    --days <n>          Validity in days, CA and server (default: 3650 ≈ 10 years)
    --bits <n>          RSA key size for the server key (default: 2048)
    --ec                Use an EC (prime256v1) server key instead of RSA
    --ca-name <name>    Subject CN of the CA certificate (default: "$CA_NAME")
    --ca-org <name>     Subject O of the CA certificate (default: "$CA_ORG")
    --san <entry>       Extra SAN, "DNS:host" or "IP:addr".  Repeatable.
    --force             Overwrite an existing output directory
    -h, --help          This text

FILES (in --outdir)
    ca.pem              CA certificate — IMPORT THIS INTO THE APP ON THE PHONE
    ca-key.pem          CA private key — keep offline, never copy to the PBX
    server.pem          Server certificate — copy to the PBX (tlscertfile)
    server-key.pem      Server private key — copy to the PBX (tlskeyfile)
    server-fullchain.pem Server certificate + CA certificate, for servers that
                        want the chain in one file

EXAMPLE
    $PROG --host pbx.lan --ip 192.168.1.10 --outdir ~/pbx-tls
EOF
}

need_value() {
    # $1 = option name, $2 = number of remaining args
    if [ "$2" -lt 2 ]; then
        echo "$PROG: $1 needs a value" >&2
        exit 2
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --host)   need_value "$1" $#; HOSTS="$HOSTS $2"; shift 2 ;;
        --ip)     need_value "$1" $#; HOSTS="$HOSTS $2"; shift 2 ;;
        --outdir) need_value "$1" $#; OUTDIR="$2"; shift 2 ;;
        --days)   need_value "$1" $#; DAYS="$2"; shift 2 ;;
        --bits)   need_value "$1" $#; BITS="$2"; shift 2 ;;
        --ca-name) need_value "$1" $#; CA_NAME="$2"; shift 2 ;;
        --ca-org)  need_value "$1" $#; CA_ORG="$2"; shift 2 ;;
        --san)   need_value "$1" $#; EXTRA_SANS="$EXTRA_SANS $2"; shift 2 ;;
        --ec)    EC_KEY=1; shift ;;
        --force) FORCE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "$PROG: unknown option '$1' (try --help)" >&2; exit 2 ;;
    esac
done

if [ -z "$(echo "$HOSTS" | tr -d ' ')" ]; then
    echo "$PROG: at least one --host is required (try --help)" >&2
    exit 2
fi

command -v openssl >/dev/null 2>&1 || {
    echo "$PROG: openssl not found in PATH" >&2
    exit 3
}

case "$DAYS" in
    ''|*[!0-9]*) echo "$PROG: --days must be a number" >&2; exit 2 ;;
esac
case "$BITS" in
    ''|*[!0-9]*) echo "$PROG: --bits must be a number" >&2; exit 2 ;;
esac

# ── Is this an IP literal? ────────────────────────────────────────────────
is_ip() {
    case "$1" in
        *:*) return 0 ;;                       # IPv6
    esac
    # IPv4: four dot-separated decimal octets
    if echo "$1" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
        return 0
    fi
    return 1
}

# ── Build the SAN list ───────────────────────────────────────────────────
SAN_LIST=""
add_san() {
    # $1 = raw entry, "DNS:name" / "IP:addr" / bare name-or-address
    case "$1" in
        DNS:*) entry="$1" ;;
        IP:*)  entry="$1" ;;
        *)
            if is_ip "$1"; then entry="IP:$1"; else entry="DNS:$1"; fi
            ;;
    esac
    if [ -z "$SAN_LIST" ]; then
        SAN_LIST="$entry"
    else
        SAN_LIST="$SAN_LIST,$entry"
    fi
}

for h in $HOSTS; do add_san "$h"; done
for s in $EXTRA_SANS; do add_san "$s"; done

if [ -z "$SAN_LIST" ]; then
    echo "$PROG: no SAN entries could be derived from the hosts" >&2
    exit 2
fi

# The CN mirrors the first DNS SAN, so old clients that only look at the CN
# still see something sensible.  With only addresses given, the CN is the
# address — legal, and better than inventing a name.
PRIMARY_HOST=""
for h in $HOSTS; do
    if ! is_ip "$h"; then PRIMARY_HOST="$h"; break; fi
done
if [ -z "$PRIMARY_HOST" ]; then
    for h in $HOSTS; do PRIMARY_HOST="$h"; break; done
fi

# ── Output directory ─────────────────────────────────────────────────────
if [ -e "$OUTDIR" ] && [ "$FORCE" -eq 0 ]; then
    if [ -n "$(ls -A "$OUTDIR" 2>/dev/null)" ]; then
        echo "$PROG: $OUTDIR exists and is not empty — use --force to overwrite" >&2
        exit 2
    fi
fi
mkdir -p "$OUTDIR" || exit 3
OUTDIR="$(cd "$OUTDIR" && pwd)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/gen-pbx-cert.XXXXXX")" || exit 3
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

CA_KEY="$OUTDIR/ca-key.pem"
CA_CRT="$OUTDIR/ca.pem"
SRV_KEY="$OUTDIR/server-key.pem"
SRV_CRT="$OUTDIR/server.pem"
SRV_FULL="$OUTDIR/server-fullchain.pem"

# ── Extension configuration ──────────────────────────────────────────────
cat > "$WORK/ca.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca

[dn]
CN = $CA_NAME
O = $CA_ORG

[v3_ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

cat > "$WORK/server.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no

[dn]
CN = $PRIMARY_HOST
O = $CA_ORG

[v3_server]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = $SAN_LIST
EOF

# ── CA ───────────────────────────────────────────────────────────────────
echo "Generating private CA …"
openssl genrsa -out "$CA_KEY" "$BITS" 2>/dev/null || exit 4
chmod 600 "$CA_KEY"
openssl req -x509 -new -sha256 -key "$CA_KEY" -out "$CA_CRT" \
    -days "$DAYS" -config "$WORK/ca.cnf" -extensions v3_ca || exit 4

# ── Server key + CSR + certificate ───────────────────────────────────────
echo "Generating server certificate for $SAN_LIST …"
if [ "$EC_KEY" -eq 1 ]; then
    openssl ecparam -name prime256v1 -genkey -noout -out "$SRV_KEY" 2>/dev/null || exit 4
else
    openssl genrsa -out "$SRV_KEY" "$BITS" 2>/dev/null || exit 4
fi
chmod 600 "$SRV_KEY"

openssl req -new -sha256 -key "$SRV_KEY" -out "$WORK/server.csr" \
    -config "$WORK/server.cnf" || exit 4

openssl x509 -req -sha256 -in "$WORK/server.csr" -CA "$CA_CRT" -CAkey "$CA_KEY" \
    -CAcreateserial -out "$SRV_CRT" -days "$DAYS" \
    -extfile "$WORK/server.cnf" -extensions v3_server 2>/dev/null || exit 4

cat "$SRV_CRT" "$CA_CRT" > "$SRV_FULL"

# ── Verify the chain, loudly ─────────────────────────────────────────────
echo "Verifying the generated chain …"
if ! openssl verify -CAfile "$CA_CRT" "$SRV_CRT" > "$WORK/verify.out" 2>&1; then
    echo "$PROG: the generated certificate does NOT verify against its CA:" >&2
    cat "$WORK/verify.out" >&2
    exit 5
fi
if ! openssl verify -CAfile "$CA_CRT" "$CA_CRT" > "$WORK/verify-ca.out" 2>&1; then
    echo "$PROG: the CA certificate does not verify against itself:" >&2
    cat "$WORK/verify-ca.out" >&2
    exit 5
fi
if ! openssl x509 -in "$SRV_CRT" -noout -checkend 0 > /dev/null 2>&1; then
    echo "$PROG: the server certificate is not valid (expired or not yet valid)" >&2
    exit 5
fi

# The SAN is the whole point — a chain that verifies but names the wrong host
# still fails the handshake on the phone.
if ! openssl x509 -in "$SRV_CRT" -noout -text | grep -q "Subject Alternative Name"; then
    echo "$PROG: the server certificate carries no Subject Alternative Name" >&2
    exit 5
fi

# ── Instructions ─────────────────────────────────────────────────────────
CA_SUBJECT="$(openssl x509 -in "$CA_CRT" -noout -subject | sed 's/^subject= *//')"
SRV_SUBJECT="$(openssl x509 -in "$SRV_CRT" -noout -subject | sed 's/^subject= *//')"
SRV_END="$(openssl x509 -in "$SRV_CRT" -noout -enddate | sed 's/^notAfter=//')"
CA_END="$(openssl x509 -in "$CA_CRT" -noout -enddate | sed 's/^notAfter=//')"

cat <<EOF

────────────────────────────────────────────────────────────────────────
Done.  Chain verified: $(cat "$WORK/verify.out")

  CA      $CA_SUBJECT
          valid until $CA_END
  Server  $SRV_SUBJECT
          SAN: $SAN_LIST
          valid until $SRV_END

FILES IN $OUTDIR
  ca.pem              CA certificate  →  IMPORT INTO THE APP ON THE PHONE
  ca-key.pem          CA private key  →  KEEP OFFLINE, never copy anywhere
  server.pem          Server cert     →  copy to the PBX (tlscertfile)
  server-key.pem      Server key      →  copy to the PBX (tlskeyfile)
  server-fullchain.pem Server + CA    →  only if the PBX wants one file

ON THE PBX (FreePBX / Asterisk)
  sudo install -m 644 $SRV_CRT /etc/asterisk/keys/asterisk.crt
  sudo install -m 600 $SRV_KEY /etc/asterisk/keys/asterisk.key
  # then in asterisk.conf, under [options]:
  #   tlscertfile = /etc/asterisk/keys/asterisk.crt
  #   tlskeyfile  = /etc/asterisk/keys/asterisk.key
  # and:  asterisk -rx "pjsip set tls on"
  # check: asterisk -rx "pjsip show tls"

ON THE PHONE
  Copy ca.pem to the phone (cable, SD card, or a messenger), then in the
  app:  Settings → Certificate authority → IMPORT CA, and pick ca.pem.
  The app then trusts this CA in addition to the system store.  The name
  the phone connects to must match the SAN above exactly.

NOTES
  * These keys are for a private gateway on a private network.  The CA key
    can mint a certificate for anything — keep it offline and never reuse
    it for a public website.
  * Keep the phone's server name and the SAN in step.  A chain that
    verifies but names a different host is rejected as a name mismatch.
────────────────────────────────────────────────────────────────────────

EOF
