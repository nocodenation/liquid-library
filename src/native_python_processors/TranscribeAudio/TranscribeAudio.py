import os
import tempfile
import shutil
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    from openai import OpenAI
except ImportError:
    OpenAI = None

class TranscribeAudio(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Transcribes audio content using OpenAI Whisper API.
        Input: Audio FlowFile (mp3, wav, etc.).
        Output: Transcription text.
        """
        tags = ['audio', 'transcription', 'openai', 'whisper', 'python']
        dependencies = ['openai']

    def __init__(self, **kwargs):
        pass

    OPENAI_API_KEY = PropertyDescriptor(
        name="OpenAI API Key",
        description="Your OpenAI API Key.",
        required=True,
        sensitive=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    LANGUAGE = PropertyDescriptor(
        name="Language",
        description="The language of the audio (ISO-639-1). Leave empty to auto-detect.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    PROMPT = PropertyDescriptor(
        name="Prompt",
        description="Optional text to guide the model's style or continue a previous audio segment.",
        required=False,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.OPENAI_API_KEY, self.LANGUAGE, self.PROMPT]

    def transform(self, context, flowFile):
        if OpenAI is None:
            self.logger.error("openai library not found.")
            return FlowFileTransformResult(relationship="failure")

        api_key = context.getProperty(self.OPENAI_API_KEY).evaluateAttributeExpressions().getValue()
        language = context.getProperty(self.LANGUAGE).evaluateAttributeExpressions(flowFile).getValue()
        prompt = context.getProperty(self.PROMPT).evaluateAttributeExpressions(flowFile).getValue()

        client = OpenAI(api_key=api_key)
        temp_dir = tempfile.mkdtemp()
        
        # We need to preserve extension if possible or default to mp3
        # NiFi doesn't always give us filename with extension in flowfile object directly in this context easily
        # unless we read attributes.
        filename = flowFile.getAttribute("filename")
        if not filename:
            filename = "audio.mp3"
            
        input_path = os.path.join(temp_dir, filename)

        try:
            with open(input_path, 'wb') as f:
                f.write(flowFile.getContentsAsBytes())

            self.logger.info(f"Transcribing {filename}...")

            with open(input_path, "rb") as audio_file:
                # Prepare args
                args = {
                    "model": "whisper-1",
                    "file": audio_file,
                    "response_format": "text"
                }
                if language:
                    args["language"] = language
                if prompt:
                    args["prompt"] = prompt

                transcription = client.audio.transcriptions.create(**args)

            return FlowFileTransformResult(
                relationship="success",
                contents=transcription,
                attributes={"mime.type": "text/plain", "transcription.model": "whisper-1"}
            )

        except Exception as e:
            self.logger.error(f"Transcription failed: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
        finally:
            shutil.rmtree(temp_dir)
