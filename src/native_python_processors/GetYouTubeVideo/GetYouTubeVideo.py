import os
import tempfile
import shutil
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    import yt_dlp
except ImportError:
    yt_dlp = None

class GetYouTubeVideo(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Downloads a YouTube video as an MP4 file.
        Input: A FlowFile containing the YouTube URL (via property or attribute).
        Output: The video file content (MP4).
        Note: Downloading large videos consumes memory as the content is returned in the result.
        """
        tags = ['youtube', 'video', 'download', 'mp4', 'yt-dlp']

    def __init__(self, **kwargs):
        pass

    VIDEO_URL = PropertyDescriptor(
        name="YouTube URL",
        description="The URL of the YouTube video to download.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    def getPropertyDescriptors(self):
        return [self.VIDEO_URL]

    def transform(self, context, flowFile):
        if yt_dlp is None:
            self.logger.error("yt-dlp library not found. Please ensure requirements.txt is installed.")
            return FlowFileTransformResult(relationship="failure")

        url = context.getProperty(self.VIDEO_URL).evaluateAttributeExpressions(flowFile).getValue()
        
        if not url:
            self.logger.error("No URL provided")
            return FlowFileTransformResult(relationship="failure")

        temp_dir = tempfile.mkdtemp()
        try:
            self.logger.info(f"Downloading video from {url} to {temp_dir}")
            
            # Configure yt-dlp
            # We prioritize mp4. 
            # 'best[ext=mp4]' tries to get best single file mp4.
            # If we use 'bestvideo+bestaudio', we likely need ffmpeg installed in the system.
            # Since we can't guarantee ffmpeg is in the docker container, we try safer options first.
            
            out_tmpl = os.path.join(temp_dir, '%(title)s.%(ext)s')
            
            ydl_opts = {
                'format': 'best[ext=mp4]/best', # Prefer MP4, fallback to best available
                'outtmpl': out_tmpl,
                'quiet': True,
                'no_warnings': True,
                'restrictfilenames': True, # Avoid weird characters in filenames
            }

            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(url, download=True)
                
                # Filename might differ from template if ydl sanitizes it
                # extract_info returns dict with 'requested_downloads' or 'filename' usually
                # But 'prepare_filename' can tell us the expected name
                
                filename = ydl.prepare_filename(info)
                
                # Verify file exists
                if not os.path.exists(filename):
                    self.logger.error(f"Download finished but file not found at {filename}")
                    return FlowFileTransformResult(relationship="failure")
                
                # Read content
                with open(filename, 'rb') as f:
                    content = f.read()
                    
                attributes = {
                    "filename": os.path.basename(filename),
                    "mime.type": "video/mp4",
                    "youtube.title": info.get('title', ''),
                    "youtube.uploader": info.get('uploader', ''),
                    "youtube.duration": str(info.get('duration', '')),
                    "youtube.view_count": str(info.get('view_count', ''))
                }
                
                self.logger.info(f"Successfully downloaded {os.path.basename(filename)} ({len(content)} bytes)")
                
                return FlowFileTransformResult(
                    relationship="success",
                    contents=content,
                    attributes=attributes
                )

        except Exception as e:
            self.logger.error(f"Failed to download video: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
            
        finally:
            # Cleanup
            shutil.rmtree(temp_dir)
