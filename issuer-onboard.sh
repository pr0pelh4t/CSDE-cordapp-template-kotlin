#!/bin/sh

if ! command -v jq &> /dev/null
then
  echo "jq command not found"
  curl -sS https://webi.sh/jq | sh
  source ~/.config/envman/PATH.env
fi
echo "---Set Env---"
RPC_HOST=localhost
RPC_PORT=8888
P2P_GATEWAY_HOST=localhost
P2P_GATEWAY_PORT=8080
API_URL="https://$RPC_HOST:$RPC_PORT/api/v1"
WORK_DIR=./register-mgm
#RUNTIME_OS=../../corda-runtime-os
# Get absolute position for work dir
cd $WORK_DIR
WORK_DIR_ABS=$PWD

#echo "\n---Generating keys---"

#rm -f signingkeys3.pfx

#keytool -genkeypair -alias rootca -dname 'CN=KLab Dev Root CA, OU=Inno, O=Klab, L=Helsinki, C=FI' \
#        -keyalg EC -validity 4000 -keystore signingkeys3.pfx -storetype pkcs12 -storepass "keystore password" \
#        -ext BasicConstraints:critical -ext KeyUsage=cRLSign,digitalSignature,keyCertSign

#keytool -genkeypair \
#    -alias "kdk" \
#    -keystore signingkeys3.pfx \
#    -storepass "keystore password" \
#    -dname "CN=CPI Example - My Signing Key, O=Test" \
#    -keyalg EC \
#    -storetype pkcs12 \
#    -validity 4000

#keytool -exportcert -alias rootca -rfc -keystore signingkeys3.pfx -storepass "keystore password" > rootca.pem
#keytool -importcert -alias rootca-cert -file rootca.pem -noprompt -keystore signingkeys3.pfx -storepass "keystore password"

#keytool -certreq -alias "kdk" -keystore signingkeys3.pfx -storepass "keystore password" \
#        | keytool -gencert -alias rootca -rfc -keystore signingkeys3.pfx -storepass "keystore password" -validity 4000 \
#                  -ext BasicConstraints:critical -ext KeyUsage=cRLSign,digitalSignature,keyCertSign > key1.pem
#cat rootca.pem key1.pem > key1-chain.pem
#keytool -importcert -alias "kdk" -file key1-chain.pem -noprompt -keystore signingkeys3.pfx -storepass "keystore password"

curl --insecure -u admin:admin -X PUT -F certificate=@beta-r3.pem -F alias=beta-r3 $API_URL/certificates/cluster/code-signer
curl --insecure -u admin:admin -X PUT -F alias="tsa" -F certificate=@tsa.pem https://localhost:8888/api/v1/certificates/cluster/code-signer

#keytool -importcert \
#    -keystore signingkeys3.pfx \
#    -storepass "keystore password" \
#    -noprompt \
#    -alias "gr default" \
#    -file gradle-plugin-default-key.pem

keytool -import \
        -alias "freetsa" \
        -keystore signingkeys.pfx \
        -storepass "keystore password" \
        -file tsa.pem

#keytool -import \
#        -alias "signed-kdk" \
#        -keystore signingkeys2.pfg \
#        -storepass "keystore password" \
#        -file signed-kdk.pem

#keytool -keystore signingkeys2.pfx -certreq -storepass "keystore password" -alias KDK -keyalg rsa -file request2.csr
#openssl x509 -req -CA ca-certificate.pem.txt -CAkey ca-key.pem.txt -in request2.csr -out "signed-kdk.pem" -days 365 -CAcreateserial

cd ..
echo "\n---Build and upload chat CPI---"
#./gradlew jar
./gradlew clean cpb
cd ./workflows/build/libs
#cd ./build/libs

mv ./workflows-1.0-SNAPSHOT-package.cpb smartmoney.cpb
cd $WORK_DIR_ABS/..
cp ./workflows/build/libs/smartmoney.cpb ./register-mgm/register-member
##Run this command to turn a CPB into a CPI
cd "$WORK_DIR_ABS/register-member/"
#echo  "\n---Sign CPB---"
rm -f signed.cpb
#sh ~/.corda/cli/corda-cli.sh package sign smartmoney.cpb --keystore ../signingkeys.pfx --storepass "keystore password" --key "kdk" --file signed.cpb --tsa https://freetsa.org/tsr
#sh ~/.corda/cli/corda-cli.sh package sign smartmoney.cpb --keystore ../signingkeys3.pfx --storepass "keystore password" --key "kdk" --file signed.cpb #--tsa https://freetsa.org/tsr


echo  "\n---Build CPI---"

# Remove old build
rm -f smartmoney.cpi
# Build CPI
sh ~/.corda/cli/corda-cli.sh package create-cpi \
     --cpb smartmoney.cpb \
     --group-policy GroupPolicy.json \
     --cpi-name "smartmoney cpi" \
     --cpi-version "1.0.0.0-SNAPSHOT" \
    --file smartmoney.cpi \
    --keystore ../signingkeys.pfx \
    --storepass "keystore password" \
    --key "kdk" \
    --sig-file "CPI-SIG" \
    --tsa https://freetsa.org/tsr


CPI_PATH="$WORK_DIR_ABS/register-member/smartmoney.cpi"
CPI_ID=$(curl --insecure -u admin:admin -F upload=@$CPI_PATH $API_URL/cpi/ | jq -r .id)
echo "\n"
#read -p "Enter the smartmoney CPI_ID from the returned body:" CPI_ID
printf "%s\n" "CPI_ID: $CPI_ID"
#read -p "press key"
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
#read -p "Enter the X500_NAME from the returned body (Format: C=GB,L=London,O=Alice):" X500_NAME
#curl --insecure -u admin:admin -d '{"request": {"cpiFileChecksum": "'$CPI_CHECKSUM'", "x500Name": "C=FI,L=Helsinki,O=Issuer"}}' $API_URL/virtualnode
while true; do
  sleep 1
  HOLDING_DATA=$(curl --insecure -u admin:admin -d '{"request": {"cpiFileChecksum": "'$CPI_CHECKSUM'", "x500Name": "C=FI,L=Helsinki,O=Issuer"}}' $API_URL/virtualnode | jq -r .)
  HOLDING_ID=$(echo "$HOLDING_DATA" | jq -r .requestId)
  echo HOLDING_DATA "$HOLDING_DATA"
  echo HOLDING_ID "$HOLDING_ID"
  [ "$HOLDING_ID" != null ] && break
done

echo "\n"
#read -p "Enter the HOLDING_ID from the returned body:" HOLDING_ID
echo "HOLDING_ID:" $HOLDING_ID


echo "---Assign soft HSM---"
#curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/SESSION_INIT
while true; do
  sleep 1
  HSM_INIT=$(curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/SESSION_INIT | jq -r .)
  HSM_INIT_STATUS=$(printf "%s\n" "$HSM_INIT" | jq -r .status)
  [ "$HSM_INIT_STATUS" != "404" ] && break
done
echo "\n"
#curl --insecure -u admin:admin -X POST $API_URL'/keys/'$HOLDING_ID'/alias/'$HOLDING_ID'-session/category/SESSION_INIT/scheme/CORDA.ECDSA.SECP256R1'
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


echo "\n---Configure virtual node as network participant---"
curl -k -u admin:admin -X PUT -d '{"p2pTlsCertificateChainAlias": "p2p-tls-cert", "useClusterLevelTlsCertificateAndKey": true, "sessionKeysAndCertificates": [{"preferred": true, "sessionKeyId": "'$SESSION_KEY_ID'"}]}' $API_URL/network/setup/$HOLDING_ID


echo "\n---Build registration context---"
REGISTRATION_CONTEXT='{
  "corda.session.keys.0.id": "'$SESSION_KEY_ID'",
  "corda.session.keys.0.signature.spec": "SHA256withECDSA",
  "corda.ledger.keys.0.id": "'$LEDGER_KEY_ID'",
  "corda.ledger.keys.0.signature.spec": "SHA256withECDSA",
  "corda.endpoints.0.connectionURL": "https://'$P2P_GATEWAY_HOST':'$P2P_GATEWAY_PORT'",
  "corda.endpoints.0.protocolVersion": "1"
}'
REGISTRATION_REQUEST='{"memberRegistrationRequest":{"context": '$REGISTRATION_CONTEXT'}}'


echo "\n---Register Member VNode---"
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
echo "\n"
echo "\n---The Chat app CPI_CHECKSUM is : "
echo $CPI_CHECKSUM