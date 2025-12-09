from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators
import subprocess
import sys
import sqlite3

try:
    from presidio_analyzer import AnalyzerEngine
    from presidio_analyzer.nlp_engine import NlpEngineProvider
except ImportError:
    pass

class PresidioDeanonymizer(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1'
        description = 'Reverts pseudonyms in text back to original entities using a local SQLite map.'
        tags = ['presidio', 'deanonymization', 'rag', 'llm']

    MAP_DB_PATH = PropertyDescriptor(
        name="Map Database Path",
        description="Local path to the SQLite DB used for PII mapping.",
        required=True,
        default_value="/files/PresidioAnonymizer/anonymization_map.db",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
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
        return [self.MAP_DB_PATH, self.SPACY_MODEL_FILE]

    def onScheduled(self, context):
        # Analyzer is lazy loaded in transform
        pass
        
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

        db_path = context.getProperty(self.MAP_DB_PATH).getValue()
        self.conn = sqlite3.connect(db_path, check_same_thread=False)

    def transform(self, context, flowFile):
        input_text = flowFile.getContentsAsBytes().decode('utf-8')
        
        # Lazy load analyzer
        if self.analyzer is None:
            spacy_model = context.getProperty(self.SPACY_MODEL_FILE).getValue()
            if spacy_model:
                model_name = "en_core_web_lg" if spacy_model.endswith(".whl") else spacy_model
                provider = NlpEngineProvider(nlp_configuration={
                    "nlp_engine_name": "spacy",
                    "models": [{"lang_code": "en", "model_name": model_name}]
                })
                self.analyzer = AnalyzerEngine(nlp_engine=provider.create_engine())
            else:
                self.analyzer = AnalyzerEngine()

        # 1. Analyze Output to find potential pseudonyms
        # We look for the same entity types we obfuscated (PERSON, EMAIL, PHONE, etc)
        results = self.analyzer.analyze(text=input_text, entities=["PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER"], language='en')
        
        candidates = set()
        for res in results:
            candidates.add(input_text[res.start:res.end])
            
        # 2. Lookup Candidates
        replacements = {}
        cursor = self.conn.cursor()
        for cand in candidates:
            # Reverse lookup: Pseudonym -> Original
            cursor.execute("SELECT original FROM mappings WHERE pseudonym = ?", (cand,))
            row = cursor.fetchone()
            if row:
                replacements[cand] = row[0]
                
        # 3. Replace
        # Simple string replacement in reverse start order to maintain indices? 
        # Since we have specific matches from Presidio, we can use the spans.
        
        # Sort results by start reverse
        results.sort(key=lambda x: x.start, reverse=True)
        
        deanonymized_text = input_text
        for res in results:
            cand = input_text[res.start:res.end]
            if cand in replacements:
                original = replacements[cand]
                deanonymized_text = deanonymized_text[:res.start] + original + deanonymized_text[res.end:]
                
        return FlowFileTransformResult(relationship="success", contents=deanonymized_text.encode('utf-8'))
