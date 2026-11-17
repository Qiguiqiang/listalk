TEST CERTIFICATE - TEST ONLY - NOT FOR DISTRIBUTION
====================================================

driver-test-cert.cer : public certificate (DER). install.bat imports it into
                       the Local Machine Root + TrustedPublisher stores so
                       Windows accepts drivers signed with it (Test Mode only).
driver-test-cert.pfx : certificate + private key. build-and-sign.bat uses it
                       with signtool to test-sign the .sys and the .cat.

Subject : CN=AirBridge Test Driver Signing (TEST ONLY), O=AirBridge
Usage   : Code Signing EKU only, self-signed, generated with OpenSSL on the
          Linux build box. Valid 10 years from generation (2026-11).
PFX password: AirBridge-Test-2026      <-- TEST ONLY, throwaway by design.

This certificate provides NO real-world trust: it only works on machines
where (a) this cert was imported and (b) Test Mode is ON and Secure Boot
is OFF. Never ship it in a product build. Route B (product) replaces it
with an EV certificate + Microsoft attestation signing; this cert and its
password then get deleted.
