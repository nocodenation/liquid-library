import os
import tempfile
import shutil
import base64
import cv2
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    from openai import OpenAI
except ImportError:
    OpenAI = None

class AnalyzeVideoFrames(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Analyzes a video chunk by sampling frames and sending them to an OpenAI Vision model.
        Input: Video FlowFile.
        Output: Text description of the video content.
        """
        tags = ['vision', 'video', 'analysis', 'openai', 'python']
        dependencies = ['openai', 'opencv-python', 'numpy']

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

    FRAME_INTERVAL = PropertyDescriptor(
        name="Frame Interval",
        description="Extract one frame every N frames.",
        required=True,
        default_value="30",
        validators=[StandardValidators.POSITIVE_INTEGER_VALIDATOR]
    )
    
    MAX_FRAMES = PropertyDescriptor(
        name="Max Frames",
        description="Maximum number of frames to send to the model.",
        required=True,
        default_value="10",
        validators=[StandardValidators.POSITIVE_INTEGER_VALIDATOR]
    )

    PROMPT = PropertyDescriptor(
        name="Analysis Prompt",
        description="The prompt to send to the model.",
        required=True,
        default_value="Describe the events, objects, and actions in this video segment in detail.",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    VIDEO_FILE_PATH = PropertyDescriptor(
        name="Video File Path",
        description="Path to the video file to analyze. If provided, FlowFile content is ignored. Use this for large files.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.OPENAI_API_KEY, self.FRAME_INTERVAL, self.MAX_FRAMES, self.PROMPT, self.VIDEO_FILE_PATH]

    def transform(self, context, flowFile):
        if OpenAI is None:
            self.logger.error("openai library not found.")
            return FlowFileTransformResult(relationship="failure")

        api_key = context.getProperty(self.OPENAI_API_KEY).evaluateAttributeExpressions().getValue()
        frame_interval = int(context.getProperty(self.FRAME_INTERVAL).getValue())
        max_frames = int(context.getProperty(self.MAX_FRAMES).getValue())
        prompt = context.getProperty(self.PROMPT).evaluateAttributeExpressions(flowFile).getValue()
        file_path_prop = context.getProperty(self.VIDEO_FILE_PATH).evaluateAttributeExpressions(flowFile).getValue()

        temp_dir = None
        if file_path_prop and os.path.exists(file_path_prop):
            input_path = file_path_prop
            self.logger.info(f"Analyzing video from file: {input_path}")
        else:
            # Use content
            temp_dir = tempfile.mkdtemp()
            input_path = os.path.join(temp_dir, "input_video.mp4")
            try:
                with open(input_path, 'wb') as f:
                    f.write(flowFile.getContentsAsBytes())
            except Exception as e:
                self.logger.error(f"Failed to write video content to temp file: {str(e)}")
                if temp_dir: shutil.rmtree(temp_dir)
                return FlowFileTransformResult(relationship="failure")

        try:
            # Extract frames
            video = cv2.VideoCapture(input_path)
            
            if not video.isOpened():
                self.logger.error("Could not open video file with OpenCV.")
                return FlowFileTransformResult(relationship="failure")
                
            base64Frames = []
            count = 0
            extracted = 0
            
            # Get total frame count if possible to debug
            total_frames = int(video.get(cv2.CAP_PROP_FRAME_COUNT))
            self.logger.info(f"Video has approx {total_frames} frames. Interval: {frame_interval}. Max: {max_frames}.")
            
            while extracted < max_frames:
                success, frame = video.read()
                if not success:
                    break
                
                if count % frame_interval == 0:
                    _, buffer = cv2.imencode(".jpg", frame)
                    base64Frames.append(base64.b64encode(buffer).decode("utf-8"))
                    extracted += 1
                count += 1
            
            video.release()
            self.logger.info(f"Extracted {len(base64Frames)} frames for analysis.")

            if not base64Frames:
                self.logger.error("No frames extracted from video.")
                return FlowFileTransformResult(relationship="failure")

            # Call OpenAI
            client = OpenAI(api_key=api_key)
            
            # Construct message with images
            content_content = [{"type": "text", "text": prompt}]
            for b64 in base64Frames:
                content_content.append({
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:image/jpeg;base64,{b64}"
                    }
                })

            response = client.chat.completions.create(
                model="gpt-4o-mini", # Using mini for cost/speed
                messages=[
                    {
                        "role": "user", 
                        "content": content_content
                    }
                ],
                max_tokens=500,
            )
            
            description = response.choices[0].message.content
            
            return FlowFileTransformResult(
                relationship="success",
                contents=description,
                attributes={"mime.type": "text/plain", "video.analysis.model": "gpt-4o-mini"}
            )

        except Exception as e:
            self.logger.error(f"Analysis failed: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
        finally:
            if temp_dir:
                shutil.rmtree(temp_dir)
