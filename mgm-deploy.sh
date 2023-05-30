#!/bin/sh

echo "---Set Env---"
RPC_HOST=localhost
RPC_PORT=8888
P2P_GATEWAY_HOST=localhost
P2P_GATEWAY_PORT=8080
API_URL="https://$RPC_HOST:$RPC_PORT/api/v1"
WORK_DIR=./register-mgm
mkdir -p "$WORK_DIR"
RUNTIME_OS=../../../corda5/corda-runtime-os

echo "\n---Create a mock CA and signing keys---"
cd "$WORK_DIR"
WORK_DIR_ABS=$PWD


cd "$RUNTIME_OS"
RUNTIME_OS_ABS=$PWD
#./gradlew :applications:tools:p2p-test:fake-ca:clean :applications:tools:p2p-test:fake-ca:appJar
#java -jar ./applications/tools/p2p-test/fake-ca/build/bin/corda-fake-ca-5.0.0.0-SNAPSHOT.jar -m /tmp/ca -a RSA -s 3072 ca

cd "$WORK_DIR_ABS"


echo "\n---Build mgm CPB---"
cd ../../../corda5/mgm
#cd "$RUNTIME_OS"
echo $PWD
#./gradlew testing:cpbs:mgm:build
./gradlew cpb

echo "\n---Built mgm CPB---"
cp ./build/libs/mgm-1.0-SNAPSHOT-package.cpb "$WORK_DIR_ABS"
echo '{
  "fileFormatVersion" : 1,
  "groupId" : "CREATE_ID",
  "registrationProtocol" :"net.corda.membership.impl.registration.dynamic.mgm.MGMRegistrationService",
  "synchronisationProtocol": "net.corda.membership.impl.synchronisation.MgmSynchronisationServiceImpl"
}' > $WORK_DIR_ABS/MgmGroupPolicy.json
cd "$WORK_DIR_ABS"
mv ./mgm-1.0-SNAPSHOT-package.cpb mgm.cpb


echo "\n---Build and upload MGM CPI---"
cd "$WORK_DIR_ABS"
#Run this command to turn a CPB into a CPI
#sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb mgm.cpb --group-policy MgmGroupPolicy.json --cpi-name "cpi name" --cpi-version "1.0.0.0-SNAPSHOT" --file mgm.cpi --keystore signingkeys.pfx --storepass "keystore password" --key "signing key 1"
rm -f mgm.cpi
sh ~/.corda/cli/corda-cli.sh package create-cpi --cpb mgm.cpb --group-policy MgmGroupPolicy.json --cpi-name "cpi name" --cpi-version "1.0.0.0-SNAPSHOT" --file mgm.cpi --keystore signingkeys.pfx --storepass "keystore password" --key "kdk"
#Import the gradle plugin default key into Corda
echo "\n---Upload gradle default plugin key into corda---"
curl --insecure -u admin:admin -X PUT -F alias="gradle-plugin-default-key" -F certificate=@gradle-plugin-default-key.pem https://localhost:8888/api/v1/certificates/cluster/code-signer
echo "\n---Upload kdk signing key into corda---"
keytool -exportcert -rfc -alias "kdk" -keystore signingkeys.pfx -storepass "keystore password" -file signingkey1.pem
#curl --insecure -u admin:admin -X PUT -F alias="kdk" -F certificate=@signingkey1.pem https://localhost:8888/api/v1/certificates/cluster/code-signer

#Export the signing key certificate from the key store
#keytool --importcert --keystore signingkeys2.pfx --storepass "keystore password" -alias "KDK cert" --file signingkey1.pem

#keytool -exportcert -rfc -alias "freetsa" -keystore signingkeys2.pfx -storepass "keystore password" -file tsa.pem


#Import the signing key into Corda
curl --insecure -u admin:admin -X PUT -F alias="KDK" -F certificate=@signingkey1.pem https://localhost:8888/api/v1/certificates/cluster/code-signer

echo "\n---Upload ca key into corda---"
curl --insecure -u admin:admin -X PUT -F alias="ca" -F certificate=@ca-chain.cert.pem https://localhost:8888/api/v1/certificates/cluster/code-signer
curl --insecure -u admin:admin -X PUT -F alias="rootca" -F certificate=@ca.cert.pem https://localhost:8888/api/v1/certificates/cluster/code-signer

CPI_PATH=./mgm.cpi
curl --insecure -u admin:admin -F upload=@$CPI_PATH $API_URL/cpi/
echo "\n"
read -p "Enter the CPI_ID from the returned body:" CPI_ID
echo "CPI_ID:" $CPI_ID


echo "---Create MGM VNode---"
curl --insecure -u admin:admin $API_URL/cpi/status/$CPI_ID
echo "\n"
read -p "Enter the CPI_CHECKSUM from the returned body:" CPI_CHECKSUM
curl --insecure -u admin:admin -d '{ "request": {"cpiFileChecksum": "'$CPI_CHECKSUM'", "x500Name": "C=GB, L=London, O=MGM"}}' $API_URL/virtualnode
echo "\n"
read -p "Enter the MGM_HOLDING_ID from the returned body:" MGM_HOLDING_ID
echo "MGM_HOLDING_ID:" $MGM_HOLDING_ID


echo "---Assign soft HSM---"
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$MGM_HOLDING_ID/SESSION_INIT
echo "\n"
curl --insecure -u admin:admin -X POST $API_URL/keys/$MGM_HOLDING_ID/alias/$MGM_HOLDING_ID-session/category/SESSION_INIT/scheme/CORDA.ECDSA.SECP256R1
echo "\n"
read -p "Enter the SESSION_KEY_ID from the returned body:" SESSION_KEY_ID
echo "SESSION_KEY_ID:" $SESSION_KEY_ID
curl --insecure -u admin:admin -X POST $API_URL/hsm/soft/$MGM_HOLDING_ID/PRE_AUTH
echo "\nECDH_KEY_ID: "
curl --insecure -u admin:admin -X POST $API_URL/keys/$MGM_HOLDING_ID/alias/$MGM_HOLDING_ID-auth/category/PRE_AUTH/scheme/CORDA.ECDSA.SECP256R1
echo "\n"
read -p "Enter the ECDH_KEY_ID from the returned body:" ECDH_KEY_ID
echo "ECDH_KEY_ID:" $ECDH_KEY_ID


echo "\n--Set up the TLS key pair and certificate---"
curl -k -u admin:admin -X POST -H "Content-Type: application/json" $API_URL/keys/p2p/alias/p2p-TLS/category/TLS/scheme/CORDA.RSA
echo "\n"
read -p "Enter the TLS_KEY_ID from the returned body:" TLS_KEY_ID
echo "TLS_KEY_ID:" $TLS_KEY_ID
echo "\n---Fetching xxx from Corda---"
#curl -k -u admin:admin  -X POST -H "Content-Type: application/json" -d '{"x500Name": "CN=CordaOperator, C=GB, L=London, O=Org", "subjectAlternativeNames": ["'$P2P_GATEWAY_HOST'"]}' $API_URL"/certificates/p2p/"$TLS_KEY_ID > "$WORK_DIR_ABS"/request1.csr
curl -k -u admin:admin  -X POST -H "Content-Type: application/json" -d '{"x500Name": "CN=Kela, C=FI, L=Helsinki, O=Inno", "subjectAlternativeNames": ["'$P2P_GATEWAY_HOST'"]}' $API_URL"/certificates/p2p/"$TLS_KEY_ID > "$WORK_DIR_ABS"/request1.csr

read -p "Wait for download to be finished, Then press any key to continue..." ANY
cd "$RUNTIME_OS_ABS"
java -jar ./applications/tools/p2p-test/fake-ca/build/bin/corda-fake-ca-5.0.0.0-SNAPSHOT.jar -m /tmp/ca csr "$WORK_DIR_ABS"/request1.csr
cd $WORK_DIR_ABS
echo $PWD
#echo "\n---Create certificate request---"
#keytool -keystore signingkeys2.pfx -certreq -alias KDK -keyalg rsa -file request1.csr
#openssl x509 -req -CA import.pem -CAkey ca-key.pem.txt -in request1.csr -out "register-member/request1.cer" -days 365 -CAcreateserial
openssl x509 -req -CA ~/myCA/intermediateCA/certs/ca-chain.cert.pem -CAkey ~/myCA/intermediateCA/private/intermediate.key.pem -extensions server_cert -in request1.csr -sha256 -out  "register-member/request1.cer" -days  365 -CAcreateserial
rm -f p2p.pfx
keytool -import -file ./register-member/request1.cer -keystore p2p.pfx -storepass "keystore password" -alias p2p-tls-cert
keytool -exportcert -rfc -alias "p2p-tls-cert" -keystore p2p.pfx -storepass "keystore password" -file p2p.pem

cd "$WORK_DIR_ABS"
#curl -k -u admin:admin -X PUT  -F certificate=@/tmp/ca/request1/certificate.pem -F alias=p2p-tls-cert $API_URL/certificates/cluster/p2p-tls
#curl -k -u admin:admin -X PUT  -F certificate=@p2p.pem -F alias=p2p-tls-cert $API_URL/certificates/cluster/p2p-tls
curl -k -u admin:admin -X PUT  -F certificate=@p2p.pem -F alias=p2p-tls-cert $API_URL/certificates/cluster/p2p-tls

echo "---Disable revocation checks---"
curl --insecure -u admin:admin -X GET $API_URL/config/corda.p2p.gateway
echo "\n"
read -p "Enter the CONFIG_VERSION from the returned body:" CONFIG_VERSION
echo "CONFIG_VERSION:" $CONFIG_VERSION
curl -k -u admin:admin -X PUT -d '{"section":"corda.p2p.gateway", "version":"'$CONFIG_VERSION'", "config":"{ \"sslConfig\": { \"revocationCheck\": { \"mode\": \"OFF\" }  }  }", "schemaVersion": {"major": 1, "minor": 0}}' $API_URL"/config"


echo "\n---Register MGM---"
#TLS_CA_CERT=$(cat /tmp/ca/ca/root-certificate.pem | awk '{printf "%s\\n", $0}')
#TLS_CA_CERT=$(cat tsa.pem | awk '{printf "%s\n", $0}')
#TLS_CA_CERT=$(awk 'NF {sub(/\r/, ""); printf "%s\\n",$0;}' ca-certificate.pem.txt)
#TLS_CA_CERT=$(awk 'NF {sub(/\r/, ""); printf "%s\\n",$0;}' ca.cert.pem)
TLS_CA_CERT=$(awk 'NF {sub(/\r/, ""); printf "%s\\n",$0;}' ~/myCA/intermediateCA/certs/intermediate.cert.pem)
TLS_CAROOT_CERT=$(awk 'NF {sub(/\r/, ""); printf "%s\\n",$0;}' ~/myCA/rootCA/certs/ca.cert.pem)

#REGISTRATION_CONTEXT='{
#  "corda.session.key.id": "'$SESSION_KEY_ID'",
#  "corda.ecdh.key.id": "'$ECDH_KEY_ID'",
#  "corda.group.protocol.registration": "net.corda.membership.impl.registration.dynamic.member.DynamicMemberRegistrationService",
#  "corda.group.protocol.synchronisation": "net.corda.membership.impl.synchronisation.MemberSynchronisationServiceImpl",
#  "corda.group.protocol.p2p.mode": "Authenticated_Encryption",
#  "corda.group.key.session.policy": "Combined",
#  "corda.group.pki.session": "NoPKI",
#  "corda.group.pki.tls": "Standard",
#  "corda.group.tls.version": "1.3",
#  "corda.endpoints.0.connectionURL": "https://'$P2P_GATEWAY_HOST':'$P2P_GATEWAY_PORT'",
#  "corda.endpoints.0.protocolVersion": "1",
#  "corda.group.truststore.tls.0" : "'$TLS_CA_CERT'"
#}'
REGISTRATION_CONTEXT='{"corda.session.keys.0.id": "'$SESSION_KEY_ID'", "corda.ecdh.key.id": "'$ECDH_KEY_ID'", "corda.group.protocol.registration": "net.corda.membership.impl.registration.dynamic.member.DynamicMemberRegistrationService", "corda.group.protocol.synchronisation": "net.corda.membership.impl.synchronisation.MemberSynchronisationServiceImpl", "corda.group.protocol.p2p.mode": "Authenticated_Encryption", "corda.group.key.session.policy": "Combined", "corda.group.pki.session": "NoPKI", "corda.group.pki.tls": "Standard", "corda.group.tls.version": "1.3", "corda.endpoints.0.connectionURL": "https://'$P2P_GATEWAY_HOST':'$P2P_GATEWAY_PORT'", "corda.endpoints.0.protocolVersion": "1", "corda.group.trustroot.tls.0": "'$TLS_CA_CERT'", "corda.group.trustroot.tls.1": "'$TLS_CAROOT_CERT'"}'
#REGISTRATION_REQUEST='{"memberRegistrationRequest":{"action": "requestJoin", "context": '$REGISTRATION_CONTEXT'}}'
REGISTRATION_REQUEST='{"memberRegistrationRequest":{"context":'$REGISTRATION_CONTEXT'}}'

curl --insecure -u admin:admin -d "$REGISTRATION_REQUEST" $API_URL/membership/$MGM_HOLDING_ID
echo "\n"
read -p "Enter the REGISTRATION_ID from the returned body:" REGISTRATION_ID
echo "REGISTRATION_ID:" $REGISTRATION_ID
curl --insecure -u admin:admin -X GET $API_URL/membership/$MGM_HOLDING_ID/$REGISTRATION_ID
echo "\n"


echo "---Configure virtual node as network participant---"
curl -k -u admin:admin -X PUT -d '{"p2pTlsCertificateChainAlias": "p2p-tls-cert", "useClusterLevelTlsCertificateAndKey": true, "sessionKeysAndCertificates": [{"preferred": true, "sessionKeyId": "'$SESSION_KEY_ID'"}]}' $API_URL/network/setup/$MGM_HOLDING_ID
echo "\n"


echo "---Export group policy for group---"
cd "$WORK_DIR_ABS"
mkdir -p "./register-member"
curl --insecure -u admin:admin -X GET $API_URL/mgm/$MGM_HOLDING_ID/info > "$WORK_DIR_ABS/register-member/GroupPolicy.json"