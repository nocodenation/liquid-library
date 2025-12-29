Build output can be found at:

- `./oauth-token-broker-processors-nar/target/`
- `./oauth-token-broker-service-api-nar/target/`
- `./oauth-token-broker-service-nar/target/`

Grab the nar file and mount them into the NiFi container at following path:

- `/opt/nifi/nifi-current/lib/nar-file-name.nar`

Only mount individual nar files, not the folders!
