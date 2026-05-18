#!/usr/bin/env bash
# Creates a Keycloak realm + service account client for the migrator.
# Requires: a running Keycloak at $KC_URL (default http://localhost:8080) and admin/admin credentials.
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8080}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PW="${KC_ADMIN_PW:-admin}"
REALM="${REALM:-migrate-target}"
CLIENT_ID="${CLIENT_ID:-migrator-cli}"

echo ">> getting admin token"
TOKEN=$(curl -sf -d "client_id=admin-cli" \
  -d "username=$KC_ADMIN" \
  -d "password=$KC_ADMIN_PW" \
  -d "grant_type=password" \
  "$KC_URL/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

auth=(-H "Authorization: Bearer $TOKEN")

echo ">> ensuring realm $REALM exists"
status=$(curl -s -o /dev/null -w '%{http_code}' "${auth[@]}" "$KC_URL/admin/realms/$REALM" || true)
if [ "$status" != "200" ]; then
  curl -sf "${auth[@]}" -H "Content-Type: application/json" -X POST "$KC_URL/admin/realms" -d "{
    \"realm\": \"$REALM\",
    \"enabled\": true,
    \"sslRequired\": \"none\",
    \"loginWithEmailAllowed\": true,
    \"registrationEmailAsUsername\": true,
    \"duplicateEmailsAllowed\": false
  }"
  echo ">> realm created"
else
  echo ">> realm already exists"
fi
# Ensure SSL is not required so local HTTP works
curl -sf "${auth[@]}" -H "Content-Type: application/json" -X PUT "$KC_URL/admin/realms/$REALM" -d "{\"realm\":\"$REALM\",\"sslRequired\":\"none\"}" || true

# Enable unmanaged-attribute policy so the migrator's workos.* user attributes survive.
profile=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/users/profile")
profile=$(printf '%s' "$profile" | python3 -c 'import sys,json; d=json.load(sys.stdin); d["unmanagedAttributePolicy"]="ENABLED"; print(json.dumps(d))')
curl -sf "${auth[@]}" -H "Content-Type: application/json" -X PUT "$KC_URL/admin/realms/$REALM/users/profile" --data "$profile" >/dev/null
echo ">> enabled unmanagedAttributePolicy=ENABLED"

echo ">> ensuring client $CLIENT_ID exists"
clients=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID")
exists=$(printf '%s' "$clients" | python3 -c 'import sys,json; xs=json.load(sys.stdin); print("y" if xs else "n")')
if [ "$exists" = "n" ]; then
  curl -sf "${auth[@]}" -H "Content-Type: application/json" -X POST "$KC_URL/admin/realms/$REALM/clients" -d "{
    \"clientId\": \"$CLIENT_ID\",
    \"enabled\": true,
    \"publicClient\": false,
    \"serviceAccountsEnabled\": true,
    \"standardFlowEnabled\": false,
    \"directAccessGrantsEnabled\": false,
    \"protocol\": \"openid-connect\"
  }"
fi

# Fetch the (id) of the client
CLIENT_UUID=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')

# Grant realm-admin to the service account
SA_USER=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients/$CLIENT_UUID/service-account-user" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
REALM_MGMT_CLIENT=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients?clientId=realm-management" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
REALM_ADMIN_ROLE=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients/$REALM_MGMT_CLIENT/roles/realm-admin" )
echo ">> assigning realm-admin"
curl -sf "${auth[@]}" -H "Content-Type: application/json" -X POST \
  "$KC_URL/admin/realms/$REALM/users/$SA_USER/role-mappings/clients/$REALM_MGMT_CLIENT" \
  -d "[$REALM_ADMIN_ROLE]"

SECRET=$(curl -sf "${auth[@]}" "$KC_URL/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" | python3 -c 'import sys,json; print(json.load(sys.stdin)["value"])')

echo
echo "===== Migrator credentials ====="
echo "--keycloak-url=$KC_URL"
echo "--keycloak-realm=$REALM"
echo "--keycloak-client-id=$CLIENT_ID"
echo "--keycloak-client-secret=$SECRET"
echo "================================"
