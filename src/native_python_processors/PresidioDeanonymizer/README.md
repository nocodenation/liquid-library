# PresidioDeanonymizer

This processor reverts the anonymization performed by `PresidioAnonymizer`, restoring the original PII in the text.

## Processors

- **PresidioDeanonymizer** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Replaces pseudonyms in the input text with their original PII values by performing a reverse lookup.

## Configuration

### PresidioDeanonymizer

- **Spacy Model File**: Path to the local spaCy model wheel file (e.g., `/files/spacy_models/en_core_web_lg-3.8.0-py3-none-any.whl`).
    - **Note**: Ensure this points to the same model file used by the Anonymizer.
- **Map Database Path**: Path to the SQLite database containing the anonymization mapping (e.g., `/files/sqlite3/anonymization_map.db`).
    - **Note**: This must point to the *same* database file used by the `PresidioAnonymizer` that processed the data.

## Usage

Use this processor when retrieving anonymized context (e.g., from an LLM response or vector search) to restore the original PII for the authorized end-user.
1.  **Analyze**: Detects the synthetic entities (pseudonyms) in the text.
2.  **Lookup**: Queries the SQLite database to find the original PII corresponding to the found pseudonyms.
3.  **Restore**: Replaces the pseudonyms with the original values.
