# PresidioAnonymizer

This processor provides privacy-preserving PII anonymization using Microsoft Presidio, designed for RAG (Retrieval Augmented Generation) pipelines.

## Processors

- **PresidioAnonymizer** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Scans text for PII (Person, Email, Phone), replaces them with deterministic synthetic data, persists the mapping, and embeds the anonymized text.

## Configuration

### PresidioAnonymizer

- **Spacy Model File**: Path to the local spaCy model wheel file (e.g., `/files/spacy_models/en_core_web_lg-3.8.0-py3-none-any.whl`).
    - **Note**: The `en_core_web_lg` model is large (~400MB). To prevent timeouts, it is highly recommended to [download it directly](https://github.com/explosion/spacy-models/releases/download/en_core_web_lg-3.8.0/en_core_web_lg-3.8.0-py3-none-any.whl) and specify the local path.
- **ChromaDB Path**: Filesystem path where the ChromaDB collection will be persisted (e.g., `/files/chroma_db`).
- **Map Database Path**: Path to the SQLite database used to store the mapping between original PII and generated pseudonyms (e.g., `/files/sqlite3/anonymization_map.db`).
- **Faker Seed**: An integer seed to ensure deterministic generation of fake data (e.g., `42`).

## Usage

This processor is essential for anonymizing sensitive unstructured text before it enters an LLM context or vector database.
1.  **Ingest Content**: Receive text flowfiles.
2.  **Anonymize**: The processor identifies PII entities and replaces them with realistic fake data.
3.  **Persist**: The mapping (Original <-> Fake) is stored in SQLite for later deanonymization.
4.  **Embed**: The anonymized text is embedded into the configured ChromaDB collection (`rag_collection`).
