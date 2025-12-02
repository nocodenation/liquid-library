import os
import tempfile
import shutil
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    import ffmpeg
except ImportError:
    ffmpeg = None

class ExtractAudioFromVideo(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Extracts the audio track from a video file.
        Input: Video FlowFile.
        Output: Audio FlowFile (MP3).
        Requires: FFmpeg installed on the system.
        """
        tags = ['audio', 'extract', 'video', 'ffmpeg', 'python']

    def __init__(self, **kwargs):
        pass

    VIDEO_FILE_PATH = PropertyDescriptor(
        name="Video File Path",
        description="Path to the video file. If provided, FlowFile content is ignored.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.VIDEO_FILE_PATH]

    def transform(self, context, flowFile):
        if ffmpeg is None:
            self.logger.error("ffmpeg-python library not found.")
            return FlowFileTransformResult(relationship="failure")

        if shutil.which('ffmpeg') is None:
             self.logger.error("ffmpeg binary not found.")
             return FlowFileTransformResult(relationship="failure")

        video_path_prop = context.getProperty(self.VIDEO_FILE_PATH).evaluateAttributeExpressions(flowFile).getValue()
        
        temp_dir = None
        if video_path_prop and os.path.exists(video_path_prop):
            input_path = video_path_prop
            self.logger.info(f"Extracting audio from file: {input_path}")
            # Create temp dir only for output
            temp_dir = tempfile.mkdtemp()
        else:
            temp_dir = tempfile.mkdtemp()
            input_path = os.path.join(temp_dir, "input_video")
            try:
                with open(input_path, 'wb') as f:
                    f.write(flowFile.getContentsAsBytes())
            except Exception as e:
                self.logger.error(f"Failed to write content: {str(e)}")
                shutil.rmtree(temp_dir)
                return FlowFileTransformResult(relationship="failure")

        output_path = os.path.join(temp_dir, "output.mp3")
        
        try:
            try:
                (
                    ffmpeg
                    .input(input_path)
                    .output(output_path, acodec='mp3', ac=1, ar='16k') # Optimized for speech (mono, 16k)
                    .overwrite_output()
                    .run(capture_stdout=True, capture_stderr=True)
                )
                
                if os.path.exists(output_path):
                    with open(output_path, 'rb') as f:
                        content = f.read()
                        
                    return FlowFileTransformResult(
                        relationship="success",
                        contents=content,
                        attributes={"mime.type": "audio/mp3"}
                    )
                else:
                    self.logger.error("FFmpeg finished but output file missing.")
                    return FlowFileTransformResult(relationship="failure")

            except ffmpeg.Error as e:
                self.logger.error(f"FFmpeg extraction failed: {e.stderr.decode('utf8')}")
                return FlowFileTransformResult(relationship="failure")

        except Exception as e:
            self.logger.error(f"Error extracting audio: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
        finally:
            if temp_dir:
                shutil.rmtree(temp_dir)
