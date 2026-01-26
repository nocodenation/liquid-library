Build output can be found at:

- `./log-viewer-service-api-nar/target/`
- `./log-viewer-service-nar/target/`

Grab the nar files and mount them into the NiFi container at following path:

- `/opt/nifi/nifi-current/lib/nar-file-name.nar`

Only mount individual nar files, not the folders!
