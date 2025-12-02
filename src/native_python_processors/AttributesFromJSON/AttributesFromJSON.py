import json
import os
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

class AttributesFromJSON(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = 'Creates attributes from a JSON object. Keys become attribute names and values become attribute values.'
        tags = ['json', 'attributes', 'python']

    def __init__(self, **kwargs):
        pass

    JSON_SOURCE = PropertyDescriptor(
        name="JSON Source",
        description="Where to read the JSON content from.",
        required=True,
        default_value="FlowFile Content",
        allowable_values=["FlowFile Content", "JSON Text Property", "File"]
    )

    JSON_TEXT = PropertyDescriptor(
        name="JSON Text",
        description="The JSON object to parse. Used only if JSON Source is 'JSON Text Property'.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    JSON_FILE_PATH = PropertyDescriptor(
        name="JSON File Path",
        description="Path to a file containing the JSON object. Used only if JSON Source is 'File'.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.JSON_SOURCE, self.JSON_TEXT, self.JSON_FILE_PATH]

    def transform(self, context, flowFile):
        json_content = ""
        source = context.getProperty(self.JSON_SOURCE).getValue()
        
        if source == "File":
            file_path = context.getProperty(self.JSON_FILE_PATH).evaluateAttributeExpressions(flowFile).getValue()
            if not file_path:
                self.logger.error("JSON Source is 'File' but 'JSON File Path' is empty")
                return FlowFileTransformResult(relationship="failure")
                
            if os.path.exists(file_path) and os.path.isfile(file_path):
                try:
                    with open(file_path, 'r') as f:
                        json_content = f.read()
                except Exception as e:
                    self.logger.error(f"Failed to read file {file_path}: {str(e)}")
                    return FlowFileTransformResult(relationship="failure")
            else:
                 self.logger.error(f"File not found: {file_path}")
                 return FlowFileTransformResult(relationship="failure")
                
        elif source == "JSON Text Property":
            json_content = context.getProperty(self.JSON_TEXT).evaluateAttributeExpressions(flowFile).getValue()
            if not json_content:
                self.logger.error("JSON Source is 'JSON Text Property' but 'JSON Text' is empty")
                return FlowFileTransformResult(relationship="failure")
            
        else: # FlowFile Content
            # For FlowFileTransform, getting content is not as direct as `session.read()`.
            # The `transform` method signature receives `flowFile`, but this object doesn't expose content directly.
            # However, in NiFi Python API, FlowFileTransform is often used when you return *new* content.
            # To read *existing* content, the documentation suggests passing `flowFile` to a helper or using the method `flowFile.getContentsAsBytes()`.
            # Let's check if `getContentsAsBytes()` is available on the `flowFile` object passed to transform.
            # Based on NiFi Python API: flowFile has `getContentsAsBytes()`.
            
            try:
                content_bytes = flowFile.getContentsAsBytes()
                if content_bytes:
                    json_content = content_bytes.decode('utf-8')
            except Exception as e:
                self.logger.error(f"Failed to read FlowFile content: {str(e)}")
                return FlowFileTransformResult(relationship="failure")

        if not json_content:
             self.logger.error(f"No JSON content found from source: {source}")
             return FlowFileTransformResult(relationship="failure")


        if not json_content:
             # If neither is provided or result is empty
             self.logger.error("No JSON content provided via File Path or JSON Text property")
             return FlowFileTransformResult(relationship="failure")

        try:
            data = json.loads(json_content)
        except json.JSONDecodeError as e:
            self.logger.error(f"Invalid JSON content: {str(e)}")
            return FlowFileTransformResult(relationship="failure")

        if not isinstance(data, dict):
             self.logger.error("JSON content must be an object (dictionary)")
             return FlowFileTransformResult(relationship="failure")

        # Convert all values to strings as FlowFile attributes must be strings
        # We handle nested structures by converting them to string representation
        attributes = {}
        for k, v in data.items():
            if isinstance(v, (dict, list)):
                attributes[str(k)] = json.dumps(v)
            else:
                attributes[str(k)] = str(v)

        return FlowFileTransformResult(relationship="success", attributes=attributes)
