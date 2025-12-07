from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope
import json
import os
import sqlite3
import hashlib

import subprocess
import sys

# Imports that might fail if dependencies aren't installed yet (handled by venv usage in real run)
try:
    from presidio_analyzer import AnalyzerEngine
    from presidio_analyzer.nlp_engine import NlpEngineProvider
    from presidio_anonymizer import AnonymizerEngine
    from presidio_anonymizer.entities import OperatorConfig
    from faker import Faker
    import chromadb
    from chromadb.config import Settings
except ImportError:
    pass

class PresidioAnonymizer(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1'
        description = 'Anonymizes text using Microsoft Presidio, replaces PII with Faker pseudonyms, and embeds content into ChromaDB.'
        tags = ['presidio', 'anonymization', 'rag', 'llm', 'chromadb']

    # Properties
    CHROMA_PATH = PropertyDescriptor(
        name="ChromaDB Path",
        description="Local path to store the ChromaDB vector index.",
        required=True,
        default_value="./chroma_db",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    MAP_DB_PATH = PropertyDescriptor(
        name="Map Database Path",
        description="Local path to the SQLite DB used for PII mapping (Original <-> Pseudonym).",
        required=True,
        default_value="./anonymization_map.db",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    FAKER_SEED = PropertyDescriptor(
        name="Faker Seed",
        description="Seed for deterministic pseudonym generation.",
        required=False,
        default_value="42",
        validators=[StandardValidators.INTEGER_VALIDATOR]
    )

    SPACY_MODEL_FILE = PropertyDescriptor(
        name="Spacy Model File",
        description="Path to a local spaCy model file (.whl) or directory. If a .whl file is provided, it will be installed. If not provided, the model will be downloaded automatically.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def __init__(self, **kwargs):
        self.analyzer = None

    def getPropertyDescriptors(self):
        return [self.CHROMA_PATH, self.MAP_DB_PATH, self.FAKER_SEED, self.SPACY_MODEL_FILE]

    def onScheduled(self, context):
        self.chroma_client = chromadb.PersistentClient(path=context.getProperty(self.CHROMA_PATH).getValue())
        self.collection = self.chroma_client.get_or_create_collection(name="rag_collection")
        
        # Install spaCy model if provided as a .whl file
        spacy_model = context.getProperty(self.SPACY_MODEL_FILE).getValue()
        if spacy_model and spacy_model.endswith(".whl"):
            try:
                import en_core_web_lg
            except ImportError:
                try:
                    subprocess.check_call([sys.executable, "-m", "pip", "install", spacy_model], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                except subprocess.CalledProcessError as e:
                    raise ValueError(f"Failed to install spaCy model from {spacy_model}: {e}")
        
        # Analyzer is lazy loaded in transform
        self.anonymizer = AnonymizerEngine()
        self.faker = Faker()
        self.faker.seed_instance(int(context.getProperty(self.FAKER_SEED).getValue()))

        # Setup SQLite Map
        db_path = context.getProperty(self.MAP_DB_PATH).getValue()
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.execute('CREATE TABLE IF NOT EXISTS mappings (original TEXT PRIMARY KEY, pseudonym TEXT)')
        self.conn.execute('CREATE INDEX IF NOT EXISTS idx_pseudo ON mappings(pseudonym)')
        self.conn.commit()

    def get_or_create_pseudonym(self, original_text, entity_type):
        # Check specific map first
        cursor = self.conn.cursor()
        cursor.execute("SELECT pseudonym FROM mappings WHERE original = ?", (original_text,))
        row = cursor.fetchone()
        if row:
            return row[0]
        
        # Generate new
        if entity_type == 'PERSON':
            fake = self.faker.name()
        elif entity_type == 'EMAIL_ADDRESS':
            fake = self.faker.email()
        elif entity_type == 'PHONE_NUMBER':
            fake = self.faker.phone_number()
        else:
            fake = f"<{entity_type}_{self.faker.word()}>"
        
        # Save
        cursor.execute("INSERT OR IGNORE INTO mappings (original, pseudonym) VALUES (?, ?)", (original_text, fake))
        self.conn.commit()
        # Re-fetch in case of race condition
        cursor.execute("SELECT pseudonym FROM mappings WHERE original = ?", (original_text,))
        return cursor.fetchone()[0]

    def transform(self, context, flowFile):
        input_text = flowFile.getContentsAsBytes().decode('utf-8')
        
        # Lazy load analyzer
        if self.analyzer is None:
            spacy_model = context.getProperty(self.SPACY_MODEL_FILE).getValue()
            if spacy_model:
                # If it's a whl file, the model name is usually the file name without extension and version info, 
                # but standard spaCy models (like en_core_web_lg) install as their name.
                # If it's a directory, we use the path.
                
                # Assumption: If user provides a wheel for en_core_web_lg, the installed package name is 'en_core_web_lg'
                # If they provide a directory, we use the directory path.
                model_name = "en_core_web_lg" if spacy_model.endswith(".whl") else spacy_model
                
                provider = NlpEngineProvider(nlp_configuration={
                    "nlp_engine_name": "spacy",
                    "models": [{"lang_code": "en", "model_name": model_name}]
                })
                self.analyzer = AnalyzerEngine(nlp_engine=provider.create_engine())
            else:
                self.analyzer = AnalyzerEngine()

        # 1. Analyze
        results = self.analyzer.analyze(text=input_text, entities=["PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER"], language='en')
        
        # 2. Generate Mapping & Anonymize
        # We need a custom operator for Presidio that does the lookup
        # But presidio's 'custom' operator is stateless. Easier to iterate results and build a mapping dict.
        
        mapping = {}
        for res in results:
            entity_text = input_text[res.start:res.end]
            if entity_text not in mapping:
                mapping[entity_text] = self.get_or_create_pseudonym(entity_text, res.entity_type)

        # Create Presidio operators based on the mapping
        operators = {}
        for orig, pseudo in mapping.items():
            operators[orig] = OperatorConfig("replace", {"new_value": pseudo})

        # To use standard anonymizer efficiently with many distinct replacements is tricky.
        # Simple strategy: Manual string replacement sorted by length (desc) to avoid substrings issues, 
        # OR use Presidio's engine with a lambda.
        # Let's use the engine with a generic replacement:
        
        # Actually, simpler: Presidio Anonymizer supports 'custom' logic but passing the map is hard.
        # Let's do a robust replacement on the text directly since we have the distinct mapping.
        
        anonymized_text = input_text
        # Sort by start index reverse to modify string without offset issues
        results.sort(key=lambda x: x.start, reverse=True)
        
        for res in results:
            orig = input_text[res.start:res.end]
            pseudo = mapping[orig]
            anonymized_text = anonymized_text[:res.start] + pseudo + anonymized_text[res.end:]

        # 3. Embed & Store in Chroma
        # Using default Chroma embedding function (SentenceTransformer)
        doc_id = flowFile.getAttribute("filename") or hashlib.sha256(input_text.encode()).hexdigest()
        
        self.collection.add(
            documents=[anonymized_text],
            metadatas=[{"original_filename": flowFile.getAttribute("filename")}],
            ids=[doc_id]
        )
        
        # 4. Return
        # We attach the mapping as an attribute for debugging? No, keep it secret.
        return FlowFileTransformResult(relationship="success", contents=anonymized_text.encode('utf-8'))
