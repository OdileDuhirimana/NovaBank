#!/usr/bin/env bash
set -euo pipefail

# NOVA Bank Core — End-to-End API Flow using curl
# This script exercises the full flow of the public APIs:
# - Register a customer
# - Login and get JWT
# - Create two accounts
# - Deposit, Withdraw, Transfer
# - List accounts and personal transaction history
# - Login as ADMIN (seeded by BootstrapConfig) and fetch audit/fraud logs
#
# Prerequisites:
# - Server running at BASE_URL (default http://localhost:8080)
# - jq and curl installed
# - Database reachable with seeded ADMIN: admin / admin12345 (change via env vars)
#
# Usage:
#   bash scripts/curl-full-flow.sh
# Optional env vars:
#   BASE_URL, CUSTOMER_USERNAME, CUSTOMER_EMAIL, CUSTOMER_PASSWORD,
#   ADMIN_USERNAME, ADMIN_PASSWORD

command -v jq >/dev/null 2>&1 || { echo "This script requires 'jq'. Please install jq."; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "This script requires 'curl'. Please install curl."; exit 1; }

BASE_URL=${BASE_URL:-http://localhost:8080}
CUSTOMER_USERNAME=${CUSTOMER_USERNAME:-alice}
CUSTOMER_EMAIL=${CUSTOMER_EMAIL:-alice@example.com}
CUSTOMER_PASSWORD=${CUSTOMER_PASSWORD:-StrongPass123}
ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin12345}

hr() { printf '\n------------------------------\n'; }

api() {
  local method=$1
  local path=$2
  local data=${3:-}
  local token=${4:-}
  if [[ -n "$data" ]]; then
    if [[ -n "$token" ]]; then
      curl -sS -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -H "Authorization: Bearer $token" -d "$data"
    else
      curl -sS -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$data"
    fi
  else
    if [[ -n "$token" ]]; then
      curl -sS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token"
    else
      curl -sS -X "$method" "$BASE_URL$path"
    fi
  fi
}

set +e
curl -sS "$BASE_URL/actuator/health" | jq . >/dev/null 2>&1
HEALTH_RC=$?
set -e
if [[ $HEALTH_RC -ne 0 ]]; then
  echo "Server not reachable at $BASE_URL. Ensure the app is running (docker compose up or mvn spring-boot:run)."
  exit 1
fi

hr; echo "1) Register customer: $CUSTOMER_USERNAME"
REG_PAYLOAD=$(jq -n --arg u "$CUSTOMER_USERNAME" --arg e "$CUSTOMER_EMAIL" --arg p "$CUSTOMER_PASSWORD" '{username:$u,email:$e,password:$p,role:"CUSTOMER"}')
REG_RESP=$(api POST /api/auth/register "$REG_PAYLOAD") || true
# If user already exists, registration will fail; continue.
echo "$REG_RESP" | jq . || echo "$REG_RESP"

hr; echo "2) Login customer to get JWT"
LOGIN_PAYLOAD=$(jq -n --arg u "$CUSTOMER_USERNAME" --arg p "$CUSTOMER_PASSWORD" '{username:$u,password:$p}')
LOGIN_RESP=$(api POST /api/auth/login "$LOGIN_PAYLOAD")
echo "$LOGIN_RESP" | jq .
TOKEN=$(echo "$LOGIN_RESP" | jq -r .token)
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "Failed to obtain JWT for customer. Aborting."; exit 1
fi

hr; echo "3) Create Account A"
ACC_A_RESP=$(api POST /api/accounts "" "$TOKEN")
echo "$ACC_A_RESP" | jq .
ACC_A=$(echo "$ACC_A_RESP" | jq -r .accountNumber)

hr; echo "4) Deposit 200.00 into Account A"
DEP_PAYLOAD=$(jq -n --arg acc "$ACC_A" '{accountNumber:$acc,amount:200.00,note:"seed"}')
api POST /api/accounts/deposit "$DEP_PAYLOAD" "$TOKEN" | jq .

hr; echo "5) Create Account B"
ACC_B_RESP=$(api POST /api/accounts "" "$TOKEN")
echo "$ACC_B_RESP" | jq .
ACC_B=$(echo "$ACC_B_RESP" | jq -r .accountNumber)

hr; echo "6) Withdraw 25.50 from Account A"
WD_PAYLOAD=$(jq -n --arg acc "$ACC_A" '{accountNumber:$acc,amount:25.50,note:"atm"}')
api POST /api/accounts/withdraw "$WD_PAYLOAD" "$TOKEN" | jq .

hr; echo "7) Transfer 50.00 from A -> B"
TR_PAYLOAD=$(jq -n --arg from "$ACC_A" --arg to "$ACC_B" '{fromAccount:$from,toAccount:$to,amount:50.00,note:"move"}')
api POST /api/transactions/transfer "$TR_PAYLOAD" "$TOKEN" | jq .

hr; echo "8) List my accounts"
api GET /api/accounts "" "$TOKEN" | jq .

hr; echo "9) My transactions"
api GET /api/transactions/my "" "$TOKEN" | jq .

hr; echo "10) Login as ADMIN and fetch audit/fraud logs"
ADMIN_LOGIN_PAYLOAD=$(jq -n --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}')
ADMIN_LOGIN_RESP=$(api POST /api/auth/login "$ADMIN_LOGIN_PAYLOAD")
echo "$ADMIN_LOGIN_RESP" | jq .
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN_RESP" | jq -r .token)
if [[ -n "$ADMIN_TOKEN" && "$ADMIN_TOKEN" != "null" ]]; then
  echo "Audit logs (page=0,size=10):"
  api GET "/api/admin/audit?page=0&size=10" "" "$ADMIN_TOKEN" | jq '.content // .'
  echo "Fraud logs (page=0,size=10):"
  api GET "/api/admin/fraud?page=0&size=10" "" "$ADMIN_TOKEN" | jq '.content // .'
else
  echo "Warning: Could not login as ADMIN (username=$ADMIN_USERNAME). Check BootstrapConfig or credentials."
fi

hr; echo "Flow completed successfully."
