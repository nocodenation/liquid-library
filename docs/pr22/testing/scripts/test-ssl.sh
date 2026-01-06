#!/bin/bash

echo "=== Test H: SSL/TLS Configuration ==="
echo ""

echo "1. Testing HTTP (should fail after SSL is enabled):"
curl -v http://localhost:5050/api/time 2>&1 | head -10
echo ""

echo "2. Testing HTTPS with -k flag (skip cert validation):"
curl -k https://localhost:5050/api/time
echo ""
echo ""

echo "3. Testing HTTPS certificate details:"
echo | openssl s_client -connect localhost:5050 -showcerts 2>/dev/null | grep -A 2 "subject="
echo ""

echo "✅ If HTTP fails and HTTPS works, SSL is configured correctly!"
