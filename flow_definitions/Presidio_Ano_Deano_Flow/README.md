# Presidio Anonymization & Deanonymization Flow

This NiFi flow demonstrates the end-to-end capabilities of the Presidio Python Processors. It performs a full cycle of anonymizing confidential data and then deanonymizing it back to its original form.

## Flow Description

The flow performs the following steps:

1.  **Ingest**: Watches the directory `/files/test_data` for new text files (using `ListFile` and `FetchFile`).
2.  **Anonymize**: Passes the text through the `PresidioAnonymizer`.
    -   Scans for PII (Person, Email, Phone).
    -   Replaces PII with synthetic data.
    -   Stores mapping in SQLite.
    -   Embeds content in ChromaDB.
3.  **Store Anonymized**: Updates filename with prefix `ANO-` and saves to `/files/test_data/anonymized`.
4.  **Deanonymize**: Takes the anonymized text and passes it through `PresidioDeanonymizer`.
    -   Looks up pseudonyms in the SQLite map.
    -   Restores original PII.
5.  **Store Deanonymized**: Updates filename with prefix `DE-` and saves to `/files/test_data/deanonymized`.

## Requirements

-   **Processors**: `PresidioAnonymizer`, `PresidioDeanonymizer` (must be installed in NiFi).
-   **Filesystem**: Ensure the input strings/directories in the processors match your environment/container volume mounts (e.g., `/files/test_data`).
-   **Model**: The flow is configured to look for the spaCy model at `/files/spacy_models/en_core_web_lg-3.8.0-py3-none-any.whl`. Update this property if your path differs.
