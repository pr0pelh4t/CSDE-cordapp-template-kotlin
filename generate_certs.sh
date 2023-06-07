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

#default signing key
echo '-----BEGIN CERTIFICATE-----
MIIB7zCCAZOgAwIBAgIEFyV7dzAMBggqhkjOPQQDAgUAMFsxCzAJBgNVBAYTAkdC
MQ8wDQYDVQQHDAZMb25kb24xDjAMBgNVBAoMBUNvcmRhMQswCQYDVQQLDAJSMzEe
MBwGA1UEAwwVQ29yZGEgRGV2IENvZGUgU2lnbmVyMB4XDTIwMDYyNTE4NTI1NFoX
DTMwMDYyMzE4NTI1NFowWzELMAkGA1UEBhMCR0IxDzANBgNVBAcTBkxvbmRvbjEO
MAwGA1UEChMFQ29yZGExCzAJBgNVBAsTAlIzMR4wHAYDVQQDExVDb3JkYSBEZXYg
Q29kZSBTaWduZXIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQDjSJtzQ+ldDFt
pHiqdSJebOGPZcvZbmC/PIJRsZZUF1bl3PfMqyG3EmAe0CeFAfLzPQtf2qTAnmJj
lGTkkQhxo0MwQTATBgNVHSUEDDAKBggrBgEFBQcDAzALBgNVHQ8EBAMCB4AwHQYD
VR0OBBYEFLMkL2nlYRLvgZZq7GIIqbe4df4pMAwGCCqGSM49BAMCBQADSAAwRQIh
ALB0ipx6EplT1fbUKqgc7rjH+pV1RQ4oKF+TkfjPdxnAAiArBdAI15uI70wf+xlL
zU+Rc5yMtcOY4/moZUq36r0Ilg==
-----END CERTIFICATE-----' > ./gradle-plugin-default-key.pem

rm -f signingkeys.pfx

###############
# OPENSSL WAY #
###############

echo "---Creating CA directories---"
rm -rf ~/myCA
mkdir -p ~/myCA/rootCA/{certs,crl,newcerts,private}
mkdir -p ~/myCA/intermediateCA/{certs,crl,newcerts,private}
echo "---Creating Initial CA files ---"
echo 1000 > ~/myCA/rootCA/serial
echo 1000 > ~/myCA/intermediateCA/serial
echo 0100 > ~/myCA/rootCA/crlnumber
echo 0100 > ~/myCA/intermediateCA/crlnumber
touch ~/myCA/rootCA/index.txt
touch ~/myCA/intermediateCA/index.txt

echo "[ ca ]                                                   # The default CA section
default_ca = CA_default                                  # The default CA name

[ CA_default ]                                           # Default settings for the CA
dir               = /users/$(whoami)/myCA/rootCA                        # CA directory
certs             = \$dir/certs                           # Certificates directory
new_certs_dir     = \$dir/newcerts                        # New certificates directory
database          = \$dir/index.txt                       # Certificate index file
crl_dir           = \$dir/crl                             # CRL directory
serial            = \$dir/serial                          # Serial number file
RANDFILE          = \$dir/private/.rand                   # Random number filex
private_key       = \$dir/private/ca.key.pem              # Root CA private key
certificate       = \$dir/certs/ca.cert.pem               # Root CA certificate
crl               = \$dir/crl/ca.crl.pem                  # Root CA CRL
crlnumber         = \$dir/crlnumber                       # Root CA CRL number
crl_extensions    = crl_ext                              # CRL extensions
default_crl_days  = 30                                   # Default CRL validity days
default_md        = sha256                               # Default message digest
preserve          = no                                   # Preserve existing extensions
email_in_dn       = no                                   # Exclude email from the DN
name_opt          = ca_default                           # Formatting options for names
cert_opt          = ca_default                           # Certificate output options
policy            = policy_strict                        # Certificate policy
unique_subject    = no                                   # Allow multiple certs with the same DN

[ policy_strict ]                                        # Policy for stricter validation
countryName             = match                          # Must match the issuer's country
stateOrProvinceName     = optional                       # Optional Must match the issuer's state
localityName            = supplied                       # Locality is optional
organizationName        = match                          # Must match the issuer's organization
organizationalUnitName  = optional                       # Organizational unit is optional
commonName              = supplied                       # Must provide a common name
emailAddress            = optional                       # Email address is optional

[ req ]
#default_bits		= 2048
#default_md		= sha256
#default_keyfile 	= privkey.pem
distinguished_name	= req_distinguished_name
attributes		= req_attributes

[ req_distinguished_name ]
countryName			= Country Name (2 letter code)
countryName_min			= 2
countryName_max			= 2
stateOrProvinceName		= State or Province Name (full name)
localityName			= Locality Name (eg, city)
0.organizationName		= Organization Name (eg, company)
organizationalUnitName		= Organizational Unit Name (eg, section)
commonName			= Common Name (eg, fully qualified host name)
commonName_max			= 64
emailAddress			= Email Address
emailAddress_max		= 64

[ v3_ca ]                                                    # Root CA certificate extensions
subjectKeyIdentifier     = hash                              # Subject key identifier
authorityKeyIdentifier   = keyid:always,issuer               # Authority key identifier
basicConstraints         = critical, CA:true                 # Basic constraints for a CA
keyUsage                 = critical, keyCertSign, cRLSign    # Key usage for a CA

[ req_attributes ]
challengePassword		= A challenge password
challengePassword_min		= 4
challengePassword_max		= 20

[ crl_ext ]                                         # CRL extensions
authorityKeyIdentifier   = keyid:always,issuer        # Authority key identifier

[ v3_intermediate_ca ]                              # Intermediate CA certificate extensions
subjectKeyIdentifier = hash                                 # Subject key identifier
authorityKeyIdentifier = keyid:always,issuer                # Authority key identifier
basicConstraints = critical, CA:true, pathlen:0             # Basic constraints for a CA
keyUsage = critical, digitalSignature, cRLSign, keyCertSign # Key usage for a CA
" > ~/myCA/rootCA/openssl_root.cnf

echo "[ ca ]                           # The default CA section
default_ca = CA_default          # The default CA name

[ CA_default ]                                           # Default settings for the intermediate CA
dir               = /users/$(whoami)/myCA/intermediateCA            # Intermediate CA directory
certs             = \$dir/certs                           # Certificates directory
crl_dir           = \$dir/crl                             # CRL directory
new_certs_dir     = \$dir/newcerts                        # New certificates directory
database          = \$dir/index.txt                       # Certificate index file
serial            = \$dir/serial                          # Serial number file
RANDFILE          = \$dir/private/.rand                   # Random number file
private_key       = \$dir/private/intermediate.key.pem    # Intermediate CA private key
certificate       = \$dir/certs/intermediate.cert.pem     # Intermediate CA certificate
crl               = \$dir/crl/intermediate.crl.pem        # Intermediate CA CRL
crlnumber         = \$dir/crlnumber                       # Intermediate CA CRL number
crl_extensions    = crl_ext                              # CRL extensions
default_crl_days  = 30                                   # Default CRL validity days
default_md        = sha256                               # Default message digest
preserve          = no                                   # Preserve existing extensions
email_in_dn       = no                                   # Exclude email from the DN
name_opt          = ca_default                           # Formatting options for names
cert_opt          = ca_default                           # Certificate output options
policy            = policy_loose                         # Certificate policy

[ policy_loose ]                                         # Policy for less strict validation
countryName             = supplied                       # Country is optional
stateOrProvinceName     = optional                       # State or province is optional
localityName            = supplied                       # Locality is optional
organizationName        = optional                       # Organization is optional
organizationalUnitName  = optional                       # Organizational unit is optional
commonName              = supplied                       # Must provide a common name
emailAddress            = optional                       # Email address is optional

[ req ]                                                  # Request settings
default_bits        = 2048                               # Default key size
distinguished_name  = req_distinguished_name             # Default DN template
string_mask         = utf8only                           # UTF-8 encoding
default_md          = sha256                             # Default message digest
x509_extensions     = v3_intermediate_ca                 # Extensions for intermediate CA certificate

[ req_distinguished_name ]                               # Template for the DN in the CSR
countryName                     = Country Name (2 letter code)
stateOrProvinceName             = State or Province Name
localityName                    = Locality Name
0.organizationName              = Organization Name
organizationalUnitName          = Organizational Unit Name
commonName                      = Common Name
emailAddress                    = Email Address

[ v3_intermediate_ca ]                                      # Intermediate CA certificate extensions
subjectKeyIdentifier = hash                                 # Subject key identifier
authorityKeyIdentifier = keyid:always,issuer                # Authority key identifier
basicConstraints = critical, CA:true, pathlen:0             # Basic constraints for a CA
keyUsage = critical, digitalSignature, cRLSign, keyCertSign # Key usage for a CA

[ crl_ext ]                                                 # CRL extensions
authorityKeyIdentifier=keyid:always                         # Authority key identifier

[ v3_code_sign ]                                            # Codesign certificate extensions
basicConstraints = CA:FALSE                                 # Not a CA certificate
keyUsage = critical, digitalSignature                       # Key usage for a server cert
extendedKeyUsage = critical, codeSigning                    # Extended key usage
subjectKeyIdentifier = hash
" > ~/myCA/intermediateCA/openssl_intermediate.cnf

echo \
"[ req ]                                                  # Request settings
default_bits        = 2048                               # Default key size
distinguished_name  = req_distinguished_name             # Default DN template
string_mask         = utf8only                           # UTF-8 encoding
default_md          = sha256                             # Default message digest
req_extensions      = v3_code_sign                       # Extensions for intermediate CA certificate

[ req_distinguished_name ]                               # Template for the DN in the CSR
countryName                     = Country Name (2 letter code)
stateOrProvinceName             = State or Province Name
localityName                    = Locality Name
0.organizationName              = Organization Name
organizationalUnitName          = Organizational Unit Name
commonName                      = Common Name
emailAddress                    = Email Address

[ v3_code_sign ]                                            # Codesign certificate extensions
basicConstraints = CA:FALSE                                 # Not a CA certificate
keyUsage = critical, digitalSignature                       # Key usage for a server cert
extendedKeyUsage = critical, codeSigning                    # Extended key usage
subjectKeyIdentifier = hash
" > ~/myCA/intermediateCA/code_sign.cnf

openssl genrsa -out ~/myCA/rootCA/private/ca.key.pem 4096
chmod 400 ~/myCA/rootCA/private/ca.key.pem

openssl req -config ~/myCA/rootCA/openssl_root.cnf -key ~/myCA/rootCA/private/ca.key.pem -new -x509 -days 7300 -sha256 -extensions v3_ca -out ~/myCA/rootCA/certs/ca.cert.pem -subj "/C=FI/L=Helsinki/O=KLab/CN=Root CA"
chmod 444 ~/myCA/rootCA/certs/ca.cert.pem

openssl genrsa -out ~/myCA/intermediateCA/private/intermediate.key.pem 4096
chmod 400 ~/myCA/intermediateCA/private/intermediate.key.pem

#create intermediate CA CSR
openssl req -config ~/myCA/intermediateCA/openssl_intermediate.cnf -key ~/myCA/intermediateCA/private/intermediate.key.pem -new -sha256 -out ~/myCA/intermediateCA/certs/intermediate.csr.pem -subj "/O=KLab/L=Helsinki/C=FI/CN=Intermediate CA"

#Sign intermediate CA with rootCA
openssl ca -config ~/myCA/rootCA/openssl_root.cnf -extensions v3_intermediate_ca -days 3650 -notext -md sha256 -in ~/myCA/intermediateCA/certs/intermediate.csr.pem -out ~/myCA/intermediateCA/certs/intermediate.cert.pem
chmod 444 ~/myCA/intermediateCA/certs/intermediate.cert.pem
#Chain root and intermediate CAs together
cat ~/myCA/intermediateCA/certs/intermediate.cert.pem ~/myCA/rootCA/certs/ca.cert.pem > ~/myCA/intermediateCA/certs/ca-chain.cert.pem

echo "jumpingfox" > passphrase

openssl req -config ~/myCA/intermediateCA/code_sign.cnf -new -newkey rsa:2048 -keyout signingkey.key.pem -passout file:passphrase  -sha256 -out  signingkey.csr.pem -days 365 -subj "/CN=local corda signing key/O=KLab/L=Helsinki/C=FI"

openssl ca -config ~/myCA/intermediateCA/openssl_intermediate.cnf -extensions v3_code_sign -days 3650 -notext -md sha256 -in signingkey.csr.pem -out signingkey.cert.pem

cat signingkey.cert.pem ~/myCA/intermediateCA/certs/ca-chain.cert.pem > import.pem
cp ~/myCA/intermediateCA/certs/ca-chain.cert.pem .
cp ~/myCA/rootCA/certs/ca.cert.pem .
openssl pkcs12 -export -in import.pem -inkey signingkey.key.pem -passin file:passphrase -name kdk -passout pass:"keystore password" > signingkeys.pfx

#################
# THE SHORT WAY #
#################

#keytool -genkeypair -alias rootca -dname 'CN=KLab Dev Root CA, OU=Inno, O=Klab, L=Helsinki, C=FI' \
#        -keyalg EC -validity 4000 -keystore signingkeys3.pfx -storetype pkcs12 -storepass "keystore password" \
#        -ext BasicConstraints:critical -ext KeyUsage=cRLSign,digitalSignature,keyCertSign

#keytool -genkeypair \
#    -alias "kdk" \
#    -keystore signingkeys3.pfx \
#    -storepass "keystore password" \
#    -dname "CN=local corda signing key, O=KLab, L=Helsinki, C=FI" \
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

#cp signingkeys.pfx signingkeys2.pfx
keytool -importcert -keystore signingkeys.pfx -storetype pkcs12 -storepass "keystore password" -noprompt -alias ca -file ~/myCA/intermediateCA/certs/ca-chain.cert.pem
keytool -importcert -keystore signingkeys.pfx -storepass "keystore password" -noprompt -alias gradle-plugin-default-key -file gradle-plugin-default-key.pem
keytool -importcert -keystore signingkeys.pfx -storepass "keystore password" -noprompt -alias r3-beta-ca -file beta-r3.pem
#keytool -importcert -keystore signingkeys2.pfx -storepass "keystore password" -noprompt -alias gradle-plugin-default-key -file gradle-plugin-default-key.pem
