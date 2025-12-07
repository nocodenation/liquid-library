# liquid-library
The complete Library of Processors and Features of NoCodeNation

## Presidio Anonymization Processors (Prototypes)

This library contains prototypes for two NiFi Python Processors using Microsoft Presidio for PII Anonymization and Deanonymization in RAG (Retrieval Augmented Generation) pipelines.

### Processors

#### 1. PresidioAnonymizer
- **Purpose**: Scans text for PII (Person, Email, Phone), replaces them with synthetic data (fake names, etc.) using `Faker`, and stores the mapping in a local SQLite DB. It also embeds the anonymized text into ChromaDB.
- **Key Features**:
  - Deterministic replacement (same PII -> same fake entity).
  - Lazy-loading of NLP models.
  - Integration with ChromaDB.

#### 2. PresidioDeanonymizer
- **Purpose**: Reverts the anonymization by looking up pseudonyms in the SQLite map and replacing them with the original PII.
- **Key Features**:
  - Reverse lookup from local SQLite DB.

### Prerequisites & Configuration

**Important**: The `en_core_web_lg` model file is quite large (~400MB). **Downloading it can be very slow.** To prevent NiFi processor timeouts and avoid repeated heavy downloads, it is **highly recommended** to download the wheel file manually and place it in a local folder accessible to the processor.

1.  **Download Model**: [en_core_web_lg-3.8.0-py3-none-any.whl](https://github.com/explosion/spacy-models/releases/download/en_core_web_lg-3.8.0/en_core_web_lg-3.8.0-py3-none-any.whl) (**Note: Direct download from GitHub Releases is recommended.**)
2.  **Configure Processor**:
    -   Set `Spacy Model File` property to the absolute path of the downloaded `.whl` file.
    -   The processor will detect and install/load this local model automatically.
