#!/bin/bash
# After cert renewal: refresh the shadow-copy in /opt and restart the relay.
# Node reads certs only at startup, so a restart is required to pick up the new cert.
set -e
DOMAIN=cryptsafe-relay.duckdns.org
SRC=/etc/letsencrypt/live/$DOMAIN
DST=/opt/libcryptsafe/certs
cp -L $SRC/fullchain.pem $DST/fullchain.pem
cp -L $SRC/privkey.pem $DST/privkey.pem
chown libcrypt:libcrypt $DST/fullchain.pem $DST/privkey.pem
chmod 600 $DST/privkey.pem
systemctl restart libcryptsafe-relay-443
logger -t certbot-hook "relay cert refreshed + services restarted after renewal"
