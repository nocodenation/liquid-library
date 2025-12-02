import json
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    from openai import OpenAI
except ImportError:
    OpenAI = None

class GenerateEmbedding(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Generates a vector embedding for the input text using OpenAI's embedding model.
        Input: FlowFile with text content or text in an attribute.
        Output: The vector embedding as a JSON array string (either in content or attribute).
        """
        tags = ['openai', 'embedding', 'vector', 'rag', 'python']
        dependencies = ['openai==2.8.1']

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

    INPUT_TEXT = PropertyDescriptor(
        name="Input Text",
        description="The text to embed. If empty, the FlowFile content is used.",
        required=False,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    MODEL = PropertyDescriptor(
        name="Embedding Model",
        description="The model to use.",
        required=True,
        default_value="text-embedding-3-small",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    OUTPUT_ATTRIBUTE = PropertyDescriptor(
        name="Output Attribute",
        description="Name of the attribute to store the embedding JSON. If empty, writes to Content.",
        required=False,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.OPENAI_API_KEY, self.INPUT_TEXT, self.MODEL, self.OUTPUT_ATTRIBUTE]

    def transform(self, context, flowFile):
        if OpenAI is None:
            self.logger.error("openai library not found.")
            return FlowFileTransformResult(relationship="failure")

        api_key = context.getProperty(self.OPENAI_API_KEY).evaluateAttributeExpressions().getValue()
        input_text = context.getProperty(self.INPUT_TEXT).evaluateAttributeExpressions(flowFile).getValue()
        model = context.getProperty(self.MODEL).evaluateAttributeExpressions(flowFile).getValue()
        output_attr = context.getProperty(self.OUTPUT_ATTRIBUTE).evaluateAttributeExpressions(flowFile).getValue()

        # Determine input
        if not input_text:
            try:
                content = flowFile.getContentsAsBytes()
                if content:
                    input_text = content.decode('utf-8')
            except Exception as e:
                self.logger.error(f"Failed to read content: {str(e)}")
                return FlowFileTransformResult(relationship="failure")

        if not input_text or not input_text.strip():
            self.logger.error("No input text to embed.")
            return FlowFileTransformResult(relationship="failure")

        try:
            client = OpenAI(api_key=api_key)
            
            # Clean text slightly (newlines can sometimes affect embeddings)
            input_text = input_text.replace("\n", " ")
            
            response = client.embeddings.create(
                input=input_text,
                model=model
            )
            
            embedding_vector = response.data[0].embedding
            embedding_json = json.dumps(embedding_vector)
            
            if output_attr:
                return FlowFileTransformResult(
                    relationship="success",
                    attributes={output_attr: embedding_json}
                )
            else:
                return FlowFileTransformResult(
                    relationship="success",
                    contents=embedding_json,
                    attributes={"mime.type": "application/json", "embedding.model": model}
                )

        except Exception as e:
            self.logger.error(f"Embedding generation failed: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
