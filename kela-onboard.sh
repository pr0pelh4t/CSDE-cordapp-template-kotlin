#!/bin/sh

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
#keytool -genkey \
#    -alias "KDK" \
#    -keystore signingkeys2.pfx \
#    -storepass "keystore password" \
#    -dname "cn=CPI Example - My Signing Key, o=Kela, L=Helsinki, c=FI" \
#    -keyalg RSA \
#    -storetype pkcs12 \
#    -validity 4000

#keytool -import \
#        -alias "freetsa" \
#        -keystore signingkeys2.pfx \
#        -storepass "keystore password" \
#        -file cacert.pem

#keytool -import \
#        -alias "signed-kdk" \
#        -keystore signingkeys2.pfg \
#        -storepass "keystore password" \
#        -file signed-kdk.pem

#keytool -keystore signingkeys2.pfx -certreq -storepass "keystore password" -alias KDK -keyalg rsa -file request2.csr
#openssl x509 -req -CA ca-certificate.pem.txt -CAkey ca-key.pem.txt -in request2.csr -out "signed-kdk.pem" -days 365 -CAcreateserial
#keytool -import -file signed-kdk.pem -keystore signingkeys2.pfx -storepass "keystore password" -alias signed-kdk

cd ..

echo "\n---Build and upload chat CPI---"
#./gradlew jar
./gradlew clean cpb
cd ./workflows/build/libs
mv ./workflows-1.0-SNAPSHOT-package.cpb smartmoney.cpb
cd $WORK_DIR_ABS/..
cp ./workflows/build/libs/smartmoney.cpb ./register-mgm/register-member
##Run this command to turn a CPB into a CPI
cd "$WORK_DIR_ABS/register-member/"
echo  "\n---Sign CPB---"
rm -f signed.cpb
#sh ~/.corda/cli/corda-cli.sh package sign smartmoney.cpb --keystore ../signingkeys2.pfx --storepass "keystore password" --key "KDK-cert" --file signed.cpb

#sh ~/.corda/cli/corda-cli.sh package sign smartmoney.cpb --keystore ../signingkeys2.pfx --storepass "keystore password" --key "KDK" --file signed.cpb
sh ~/.corda/cli/corda-cli.sh package sign smartmoney.cpb --keystore ../signingkeys.pfx --storepass "keystore password" --key "kdk" --file signed.cpb


echo  "\n---Build CPI---"
#sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb smartmoney.cpb --group-policy GroupPolicy.json --cpi-name "smartmoney cpi" --cpi-version "1.0.0.0-SNAPSHOT" --file smartmoney.cpi --keystore ../signingkeys.pfx --storepass "keystore password" --key "kdk" #--tsa https://freetsa.org/tsr
#sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb signed.cpb --group-policy GroupPolicy.json --cpi-name "smartmoney cpi" --cpi-version "1.0.0.0-SNAPSHOT" --file smartmoney.cpi --keystore ../signingkeys2.pfx --storepass "keystore password" --key "KDK" #--tsa https://freetsa.org/tsr
sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb signed.cpb --group-policy GroupPolicy.json --cpi-name "smartmoney cpi" --cpi-version "1.0.0.0-SNAPSHOT" --file smartmoney.cpi --keystore ../signingkeys.pfx --storepass "keystore password" --key "kdk" #--tsa https://freetsa.org/tsr

CPI_PATH="$WORK_DIR_ABS/register-member/smartmoney.cpi"
curl --insecure -u admin:admin -F upload=@$CPI_PATH $API_URL/cpi/
echo "\n"
read -p "Enter the smartmoney CPI_ID from the returned body:" CPI_ID
echo "CPI_ID:" $CPI_ID
curl --insecure -u admin:admin $API_URL/cpi/status/$CPI_ID
echo "\n"
read -p "Enter the CPI_CHECKSUM from the returned body:" CPI_CHECKSUM


echo "\n---Create a Member virtual node---"
echo "\n"
read -p "Enter the X500_NAME from the returned body (Format: C=GB,L=London,O=Alice):" X500_NAME
curl --insecure -u admin:admin -d '{"request": {"cpiFileChecksum": "'$CPI_CHECKSUM'", "x500Name": "'$X500_NAME'"}}' $API_URL/virtualnode
echo "\n"
read -p "Enter the HOLDING_ID from the returned body:" HOLDING_ID
echo "HOLDING_ID:" $HOLDING_ID


echo "---Assign soft HSM---"
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/SESSION_INIT
echo "\n"
curl --insecure -u admin:admin -X POST $API_URL'/keys/'$HOLDING_ID'/alias/'$HOLDING_ID'-session/category/SESSION_INIT/scheme/CORDA.ECDSA.SECP256R1'
echo "\n"
read -p "Enter the SESSION_KEY_ID from the returned body:" SESSION_KEY_ID
echo "SESSION_KEY_ID:" $SESSION_KEY_ID
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$HOLDING_ID/LEDGER
echo "\n"
curl --insecure -u admin:admin -X POST $API_URL/keys/$HOLDING_ID/alias/$HOLDING_ID-ledger/category/LEDGER/scheme/CORDA.ECDSA.SECP256R1
echo "\n"
read -p "Enter the LEDGER_KEY_ID from the returned body:" LEDGER_KEY_ID
echo "LEDGER_KEY_ID:" $LEDGER_KEY_ID


echo "\n---Configure virtual node as network participant---"
curl -k -u admin:admin -X PUT -d '{"p2pTlsCertificateChainAlias": "p2p-tls-cert", "useClusterLevelTlsCertificateAndKey": true, "sessionKeysAndCertificates": [{"preferred": true, "sessionKeyId": "'$SESSION_KEY_ID'"}]}' $API_URL/network/setup/$HOLDING_ID


echo "\n---Build registration context---"
REGISTRATION_CONTEXT='{
  "corda.session.key.id": "'$SESSION_KEY_ID'",
  "corda.session.key.signature.spec": "SHA256withECDSA",
  "corda.ledger.keys.0.id": "'$LEDGER_KEY_ID'",
  "corda.ledger.keys.0.signature.spec": "SHA256withECDSA",
  "corda.endpoints.0.connectionURL": "https://'$P2P_GATEWAY_HOST':'$P2P_GATEWAY_PORT'",
  "corda.endpoints.0.protocolVersion": "1"
}'
REGISTRATION_REQUEST='{"memberRegistrationRequest":{"context": '$REGISTRATION_CONTEXT'}}'


echo "\n---Register Member VNode---"
curl --insecure -u admin:admin -d "$REGISTRATION_REQUEST" $API_URL/membership/$HOLDING_ID
echo "\n"
read -p "Enter the REGISTRATION_ID from the returned body:" REGISTRATION_ID
echo "REGISTRATION_ID:" $REGISTRATION_ID
curl --insecure -u admin:admin -X GET $API_URL/membership/$HOLDING_ID/$REGISTRATION_ID
echo "\n"
curl --insecure -u admin:admin -X GET $API_URL/members/$HOLDING_ID
echo "\n"
echo "\n---The Chat app CPI_CHECKSUM is : "
echo $CPI_CHECKSUM