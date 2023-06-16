#!/bin/sh

echo "---Set Env---"
RPC_HOST=localhost
RPC_PORT=8888
P2P_GATEWAY_HOST=localhost
P2P_GATEWAY_PORT=8080
API_URL="https://$RPC_HOST:$RPC_PORT/api/v1"
WORK_DIR=./register-mgm
RUNTIME_OS=../../corda-runtime-os


echo "\n---Build and upload Notary CPI---"
cd $WORK_DIR
WORK_DIR_ABS=$PWD
curl --insecure -u admin:admin -X PUT -F certificate=@beta-r3.pem -F alias=beta-r3 $API_URL/certificates/cluster/code-signer

rm -f notary.cpb
cp ./notary-plugin-non-validating-server-5.0.0.0-Hawk1.0.1-package.cpb ./register-member/notary.cpb
cd "$WORK_DIR_ABS/register-member/"
##Run this command to turn a CPB into a CPI
rm -f notary.cpi
sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb notary.cpb --group-policy GroupPolicy.json --cpi-name "notary cpi" --cpi-version "1.0.0.0-SNAPSHOT" --file notary.cpi --keystore ../signingkeys.pfx --storepass "keystore password" --key "kdk"
CPI_PATH="$WORK_DIR_ABS/register-member/notary.cpi"
CPI_ID=$(curl --insecure -u admin:admin -F upload=@$CPI_PATH $API_URL/cpi/ | jq -r .id)

echo "\n"
#read -p "Enter the Notary CPI_ID from the returned body:" CPI_ID
echo "CPI_ID:" $CPI_ID
#curl --insecure -u admin:admin $API_URL/cpi/status/$CPI_ID
while true; do
  sleep 1
  CPI_DATA=$(curl --insecure -u admin:admin $API_URL/cpi/status/$CPI_ID | jq -r .)
  CPI_STATUS=$(echo "$CPI_DATA" | jq -r .status)
  CPI_CHECKSUM=$(echo "$CPI_DATA" | jq -r .cpiFileChecksum)
  echo CPI_STATUS "$CPI_STATUS"
  [ "$CPI_STATUS" == "OK" ]||[ "$CPI_STATUS" == "409" ] && break
done
echo "\n"
#read -p "Enter the CPI_CHECKSUM from the returned body:" CPI_CHECKSUM


echo "\n---Create a Member virtual node---"
echo "\n"
#read -p "Enter the X500_NAME from the returned body (Formatt: C=GB,L=London,O=NotaryRep1):" X500_NAME
while true; do
  sleep 1
  HOLDING_DATA=$(curl --insecure -u admin:admin -d '{"request": {"cpiFileChecksum": "'$CPI_CHECKSUM'", "x500Name": "C=FI,L=Helsinki,O=NotaryRep1"}}' $API_URL/virtualnode | jq -r .)
  HOLDING_ID=$(echo "$HOLDING_DATA" | jq -r .requestId)
  echo HOLDING_DATA "$HOLDING_DATA"
  echo HOLDING_ID "$HOLDING_ID"
  [ "$HOLDING_ID" != null ] && break
done
echo "\n"
#read -p "Enter the HOLDING_ID from the returned body:" HOLDING_ID
echo "HOLDING_ID:" $HOLDING_ID
echo "\n"

echo "---Assign soft HSM---"
while true; do
  sleep 1
  HSM_INIT=$(curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/SESSION_INIT | jq -r .)
  HSM_INIT_STATUS=$(printf "%s\n" "$HSM_INIT" | jq -r .status)
  [ "$HSM_INIT_STATUS" != "404" ] && break
done
echo "\n"
while true; do
  sleep 1
  SESSION_KEY_DATA=$(curl --insecure -u admin:admin -X POST $API_URL'/keys/'$HOLDING_ID'/alias/'$HOLDING_ID'-session/category/SESSION_INIT/scheme/CORDA.ECDSA.SECP256R1' | jq -r .)
  SESSION_KEY_ID=$(echo "$SESSION_KEY_DATA" | jq -r .id)
  echo SESSION_KEY_DATA "$SESSION_KEY_DATA"
  [ "$SESSION_KEY_ID" != null ] && break
done
echo "\n"
#read -p "Enter the SESSION_KEY_ID from the returned body:" SESSION_KEY_ID
echo "SESSION_KEY_ID:" $SESSION_KEY_ID
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/LEDGER
echo "\n"
#curl --insecure -u admin:admin -X POST $API_URL/keys/$HOLDING_ID/alias/$HOLDING_ID-ledger/category/LEDGER/scheme/CORDA.ECDSA.SECP256R1
while true; do
  sleep 1
  LEDGER_KEY_DATA=$(curl --insecure -u admin:admin -X POST $API_URL/keys/$HOLDING_ID/alias/$HOLDING_ID-ledger/category/LEDGER/scheme/CORDA.ECDSA.SECP256R1 | jq -r .)
  LEDGER_KEY_ID=$(echo "$LEDGER_KEY_DATA" | jq -r .id)
  echo LEDGER_KEY_DATA "$LEDGER_KEY_DATA"
  [ "$LEDGER_KEY_ID" != null ] && break
done
echo "\n"
#read -p "Enter the LEDGER_KEY_ID from the returned body:" LEDGER_KEY_ID
echo "LEDGER_KEY_ID:" $LEDGER_KEY_ID
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/NOTARY
echo "\n"
while true; do
  sleep 1
  NOTARY_KEY_DATA=$(curl --insecure -u admin:admin -X POST $API_URL/keys/$HOLDING_ID/alias/$HOLDING_ID-notary/category/NOTARY/scheme/CORDA.ECDSA.SECP256R1 | jq -r .)
  NOTARY_KEY_ID=$(echo "$NOTARY_KEY_DATA" | jq -r .id)
  echo NOTARY_KEY_DATA "$NOTARY_KEY_DATA"
  [ "$NOTARY_KEY_ID" != null ] && break
done
echo "\n"
#read -p "Enter the NOTARY_KEY_ID from the returned body:" NOTARY_KEY_ID
echo "NOTARY_KEY_ID:" $NOTARY_KEY_ID


echo "\n---Configure virtual node as network participant---"
curl -k -u admin:admin -X PUT -d '{"p2pTlsCertificateChainAlias": "p2p-tls-cert", "useClusterLevelTlsCertificateAndKey": true, "sessionKeysAndCertificates": [{"preferred": true, "sessionKeyId": "'$SESSION_KEY_ID'"}]}' $API_URL/network/setup/$HOLDING_ID
echo "\n"


echo "\n---Build Notary registration context---"
echo "\n"
#read -p "Enter the NOTARY_SERVICE_NAME (Formatt: C=GB,L=London,O=NotaryServiceA):" NOTARY_SERVICE_NAME
NOTARY_SERVICE_NAME="C=FI,L=Helsinki,O=NotaryServiceA"
echo "NOTARY_SERVICE_NAME:" $NOTARY_SERVICE_NAME

REGISTRATION_CONTEXT='{
  "corda.session.keys.0.id": "'$SESSION_KEY_ID'",
  "corda.session.keys.0.signature.spec": "SHA256withECDSA",
  "corda.ledger.keys.0.id": "'$LEDGER_KEY_ID'",
  "corda.ledger.keys.0.signature.spec": "SHA256withECDSA",
  "corda.notary.keys.0.id": "'$NOTARY_KEY_ID'",
  "corda.notary.keys.0.signature.spec": "SHA256withECDSA",
  "corda.endpoints.0.connectionURL": "https://'$P2P_GATEWAY_HOST':'$P2P_GATEWAY_PORT'",
  "corda.endpoints.0.protocolVersion": "1",
  "corda.roles.0" : "notary",
  "corda.notary.service.name" : "'$NOTARY_SERVICE_NAME'",
  "corda.notary.service.flow.protocol.name" : "com.r3.corda.notary.plugin.nonvalidating",
  "corda.notary.service.flow.protocol.version.0": "1"
}'

REGISTRATION_REQUEST='{"memberRegistrationRequest":{"context": '$REGISTRATION_CONTEXT'}}'


echo "\n---Register Notary VNode---"
#curl --insecure -u admin:admin -d "$REGISTRATION_REQUEST" $API_URL/membership/$HOLDING_ID
while true; do
  sleep 1
  REGISTRATION_DATA=$(curl --insecure -u admin:admin -d "$REGISTRATION_REQUEST" $API_URL/membership/$HOLDING_ID | jq -r .)
  REGISTRATION_ID=$(echo "$REGISTRATION_DATA" | awk 'NF {sub(/\r/, ""); printf "%s", $0;}' | jq -r .registrationId)
  echo REGISTRATION_DATA $REGISTRATION_DATA
  echo REGISTRATION_ID $REGISTRATION_ID
  [ "$REGISTRATION_ID" != null ] && break
done

echo "\n"
#read -p "Enter the REGISTRATION_ID from the returned body:" REGISTRATION_ID
echo "REGISTRATION_ID:" $REGISTRATION_ID
curl --insecure -u admin:admin -X GET $API_URL/membership/$HOLDING_ID/$REGISTRATION_ID
echo "\n"
curl --insecure -u admin:admin -X GET $API_URL/members/$HOLDING_ID
