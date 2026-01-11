# NiFi SSL Context Service Setup

## Files Created

✅ **nifi-keystore.p12** - PKCS12 keystore containing:
- Private key
- End-entity certificate (nocodenation.org)
- Certificate chain (NoCodeNation CA + Dilcher GmbH Root CA)

## Keystore Details

- **Format**: PKCS12 (recommended for modern NiFi)
- **Password**: `changeit`
- **Alias**: `nocodenation`
- **Location**: `/Users/christof/dev/Claude/Claude_Code/Liquid.MX/nifi-keystore.p12`

## NiFi StandardSSLContextService Configuration

### Option 1: Using PKCS12 Keystore (Recommended)

1. In NiFi UI, add a `StandardSSLContextService` controller service
2. Configure the following properties:

```
Keystore Filename: /Users/christof/dev/Claude/Claude_Code/Liquid.MX/nifi-keystore.p12
Keystore Type: PKCS12
Keystore Password: changeit
Key Password: changeit
```

3. For the **Truststore** (optional, for mutual TLS):
   - You can leave it empty if you only need server authentication
   - Or create a truststore with the CA certificates if you need to verify client certificates

### Option 2: Copy to NiFi Container

To make the keystore available inside the liquid-playground container:

```bash
# Create SSL directory in NiFi
docker exec liquid-playground mkdir -p /opt/nifi/ssl

# Copy keystore to container
docker cp /Users/christof/dev/Claude/Claude_Code/Liquid.MX/nifi-keystore.p12 \
  liquid-playground:/opt/nifi/ssl/

# Set permissions
docker exec liquid-playground chown nifi:nifi /opt/nifi/ssl/nifi-keystore.p12
docker exec liquid-playground chmod 600 /opt/nifi/ssl/nifi-keystore.p12
```

Then configure the SSL Context Service with:
```
Keystore Filename: /opt/nifi/ssl/nifi-keystore.p12
Keystore Type: PKCS12
Keystore Password: changeit
Key Password: changeit
```

## Using SSL with NodeJSAppAPIGateway

Once the StandardSSLContextService is configured and enabled:

1. Open your `StandardNodeJSAppAPIGateway` controller service
2. Set the **SSL Context Service** property to your SSL service
3. The gateway will automatically start using HTTPS instead of HTTP
4. Access it at: `https://localhost:5050` (instead of `http://localhost:5050`)

## Testing SSL Configuration

### Test 1: HTTP Should Fail
```bash
curl http://localhost:5050/api/time
# Expected: Connection refused or "Empty reply from server"
```

### Test 2: HTTPS Should Work
```bash
curl -k https://localhost:5050/api/time
# Expected: JSON response with timestamp

# -k flag skips certificate validation (for testing only)
```

### Test 3: With Certificate Validation
```bash
# Extract the CA certificate for validation
docker exec liquid-playground sh -c \
  'openssl s_client -connect localhost:5050 -showcerts </dev/null 2>/dev/null | \
   openssl x509 -outform PEM > /tmp/server-cert.pem'

# Test with validation
curl --cacert /tmp/server-cert.pem https://localhost:5050/api/time
```

## Certificate Information

The certificate in `nocodenation_company_administrator.pem` contains:

- **Subject**: CN=NoCodeNation - NoCodeNation Company Administrator, O=NoCodeNation
- **Issuer**: CN=nocodenation.org, O=NoCodeNation
- **Valid From**: 2025-08-04
- **Valid Until**: 2026-09-05
- **SAN**: DNS:localhost

The certificate is valid for **localhost**, which makes it perfect for testing with liquid-playground!

## Security Notes

⚠️ **Production Considerations**:
1. Change the keystore password from `changeit` to a strong password
2. Protect the keystore file with appropriate file permissions (600 or 400)
3. Consider using a truststore for mutual TLS authentication
4. Use proper certificates from a trusted CA for production

## Troubleshooting

### "Unable to load keystore"
- Check file path is correct
- Verify password is correct
- Ensure file permissions allow NiFi user to read it

### "Certificate not trusted"
- Add `-k` flag to curl for testing (skips validation)
- Or add the CA certificate to your system's trust store

### "Connection refused" when using HTTP after enabling SSL
- This is expected - the gateway only listens on HTTPS when SSL is configured
- Update all clients to use `https://` instead of `http://`