import os
import tempfile
import shutil
import math
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# We can't use FlowFileTransform for 1-to-Many. We must use the raw Processor.
# However, the current nifi-python-extensions framework (nifiapi) provided by NiFi 2.0 is primarily helper classes.
# If we want to write a "pure" python processor that accesses 'session', we inherit from FlowFileTransform 
# but we must be careful.
# Actually, in NiFi 2.0, the correct class to inherit from for generic processing is often just `FlowFileTransform` 
# if 1-to-1, or `RecordTransform` etc.
# If we want arbitrary behavior, we should look at the `Processor` interface if available in `nifiapi`.
# But typically, for Python, `FlowFileTransform` IS the way, and maybe we just publish to a relationship manually?
# NO, the `FlowFileTransform` class handles session commit/rollback for you. You return a result.
# If it doesn't support list return, we CANNOT use it for 1-to-Many.

# We should implement this as a `FlowFileSource` that takes an input? No.
# We should look at `FlowFileTransform` source code (which I can't see directly, but I know the pattern).
# Wait, if I can't use FlowFileTransform for 1-to-Many, I have to use the lower level `FlowFileProcessor` or similar?
# In NiFi 2.0 Python, you can define a class that doesn't inherit from `FlowFileTransform` but implements the protocol.
# The protocol is: `transform(context, flowFile)`? No.

# Standard pattern for general processor in NiFi Python:
# class MyProcessor:
#     def onTrigger(self, context, session):
#         ...

# This gives us full control. We don't inherit from FlowFileTransform.
# We just need to expose `Java` class configuration correctly.

try:
    import ffmpeg
except ImportError:
    ffmpeg = None

class ChunkVideo:
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']
    
    # Wait, if I implement FlowFileTransform Java interface, I MUST use FlowFileTransform logic on Java side?
    # The Python side bridge expects `transform` method returning a Result.
    # If I want full control, I should implement `org.apache.nifi.processor.Processor`? 
    # But Python support usually bridges specific interfaces.
    
    # Actually, the error `AttributeError: 'list' object has no attribute '_get_object_id'` came from 
    # the bridge trying to process the return value.
    
    # If I cannot return a list, I CANNOT use `FlowFileTransform`.
    # Is there another interface? `RecordTransform`? No.
    
    # Let's try to implement the standard Processor pattern if supported.
    # class ChunkVideo(Processor): ...
    # But `nifiapi` might not expose `Processor`.
    
    # WORKAROUND: 
    # If we are forced to use FlowFileTransform (1-to-1), we can output a ZIP file containing all chunks?
    # Or a JSON list of paths (if files are stored)? No, that's messy.
    
    # Let's try to look for `FlowFileSource`? No.
    
    # Correct approach for NiFi Python 1-to-Many:
    # We should NOT inherit from `FlowFileTransform`.
    # We should just be a plain class that has an `onTrigger` method.
    # And we tell NiFi we implement `org.apache.nifi.processor.Processor`.
    
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform'] 
        # ^ This ties us to the Bridge that expects a single return.
        
    # Change `implements` to 'org.apache.nifi.processor.Processor'?
    # Python support usually requires specific interfaces.
    
    # Let's try to use the Python API `FlowFileSource`? No.
    
    # Let's check `nifiapi` docs (mental model).
    # There isn't a "FlowFileSplitter" in the high-level API yet.
    
    # HOWEVER, we can "cheat".
    # If we return a Result with `relationship="success"` and `contents=...`, that's one.
    # If we want more... 
    
    # Maybe I should modify the processor to output ONE chunk per execution?
    # Use input as a "Job" and maintain state? 
    # "Chunk 1 of 10", then "Chunk 2 of 10"?
    # That's complex state management.
    
    # ALTERNATIVE:
    # Use `FlowFileTransform` to output a **List of Paths** (JSON) to the chunks (saved to disk/temp).
    # Then use a second processor `FetchVideoChunk` to read them?
    # Or `SplitJson` -> `FetchFile`?
    
    # Let's do this:
    # 1. ChunkVideo saves chunks to a directory (e.g., `/tmp/nifi-chunks/...`).
    # 2. ChunkVideo outputs a JSON List of these file paths.
    #    `[{"chunk_index": 0, "path": "/tmp/...", ...}, ...]`
    # 3. User uses `SplitJson` to get individual chunk metadata.
    # 4. User uses `FetchFile` (standard NiFi) to read the content.
    # 5. User uses `DeleteFile` (standard NiFi) to clean up?
    
    # This is robust but requires cleanup.
    
    # BETTER:
    # Can I access `session` in `transform`?
    # `def transform(self, context, flowFile):`
    # The `context` is `ProcessContext`. `flowFile` is `FlowFile`.
    # Is `session` available? usually via `context.getSession()`? No.
    
    # Okay, let's go with the JSON List of Metadata + Content in Attribute? No, too large.
    
    # Let's go with the **JSON List of Paths** approach.
    # But wait, `FetchFile` needs absolute paths.
    # And we need to ensure unique temp dirs.
    
    # Strategy:
    # Output: JSON List of objects.
    # Each object: { "chunk_index": 0, "filename": "...", "absolute_path": "..." }
    # Content of Output FlowFile: The JSON List.
    # Attributes: `chunk.count`.
    
    # Then standard `SplitJson` -> `FetchFile` (move mode? or read+delete?)
    
    # Let's refine `ChunkVideo` to output this JSON list.
    pass

class ChunkVideo(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Splits a video file into smaller temporal chunks.
        Input: Video FlowFile.
        Output: A JSON List containing metadata and paths for each chunk.
        The actual chunk files are written to a temporary directory.
        User should follow this with SplitJson -> FetchFile (with Completion Strategy 'Delete File').
        """
        tags = ['video', 'split', 'chunk', 'ffmpeg', 'python']
        dependencies = ['ffmpeg-python']

    def __init__(self, **kwargs):
        pass

    CHUNK_DURATION = PropertyDescriptor(
        name="Chunk Duration (seconds)",
        description="Duration of each chunk in seconds.",
        required=True,
        default_value="60",
        validators=[StandardValidators.POSITIVE_INTEGER_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )
    
    OVERLAP_DURATION = PropertyDescriptor(
        name="Overlap Duration (seconds)",
        description="Amount of time (in seconds) to overlap with the previous chunk. Useful for preserving context in audio transcription.",
        required=True,
        default_value="0",
        validators=[StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )
    
    TEMP_DIR = PropertyDescriptor(
        name="Temporary Directory",
        description="Directory to store video chunks.",
        required=True,
        default_value="/tmp/nifi-video-chunks",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.CHUNK_DURATION, self.OVERLAP_DURATION, self.TEMP_DIR]

    def transform(self, context, flowFile):
        if ffmpeg is None:
            self.logger.error("ffmpeg-python library not found.")
            return FlowFileTransformResult(relationship="failure")

        if shutil.which('ffmpeg') is None:
             self.logger.error("ffmpeg binary not found in system PATH.")
             return FlowFileTransformResult(relationship="failure")

        chunk_duration = int(context.getProperty(self.CHUNK_DURATION).evaluateAttributeExpressions(flowFile).getValue())
        overlap_duration = int(context.getProperty(self.OVERLAP_DURATION).evaluateAttributeExpressions(flowFile).getValue())
        base_temp_dir = context.getProperty(self.TEMP_DIR).getValue()
        
        # Create unique subdir for this flowfile
        job_id = flowFile.getAttribute('uuid')
        job_dir = os.path.join(base_temp_dir, job_id)
        os.makedirs(job_dir, exist_ok=True)
        
        input_path = os.path.join(job_dir, "input_video")
        
        try:
            # Write input flowfile to disk
            with open(input_path, 'wb') as f:
                f.write(flowFile.getContentsAsBytes())

            # Get video duration
            try:
                probe = ffmpeg.probe(input_path)
                # Safely find video stream
                video_info = next((s for s in probe['streams'] if s['codec_type'] == 'video'), None)
                if not video_info:
                     self.logger.error("No video stream found.")
                     return FlowFileTransformResult(relationship="failure")
                     
                duration = float(probe['format']['duration'])
            except Exception as e:
                self.logger.error(f"Failed to probe video: {str(e)}")
                return FlowFileTransformResult(relationship="failure")

            # Calculate effective stride (how much we move forward each time)
            stride = chunk_duration - overlap_duration
            if stride <= 0:
                self.logger.error("Overlap duration must be smaller than chunk duration.")
                return FlowFileTransformResult(relationship="failure")

            num_chunks = math.ceil(duration / stride)
            
            # Ensure we don't produce tiny chunks at the end if not needed, 
            # but the math above roughly covers it.
            
            self.logger.info(f"Splitting video of {duration}s into approx {num_chunks} chunks of {chunk_duration}s with {overlap_duration}s overlap")

            chunks_metadata = []
            
            for i in range(num_chunks):
                start_time = i * stride
                
                # If start time is beyond duration, break
                if start_time >= duration:
                    break
                    
                output_filename = f"chunk_{i:03d}.mp4"
                output_path = os.path.join(job_dir, output_filename)
                
                try:
                    (
                        ffmpeg
                        .input(input_path, ss=start_time, t=chunk_duration)
                        .output(output_path, c='copy', avoid_negative_ts=1)
                        .overwrite_output()
                        .run(capture_stdout=True, capture_stderr=True)
                    )
                    
                    if os.path.exists(output_path):
                        chunks_metadata.append({
                            "chunk_index": i,
                            "chunk_total": num_chunks,
                            "chunk_start_time": start_time,
                            "chunk_duration": chunk_duration,
                            "chunk_overlap": overlap_duration,
                            "filename": output_filename,
                            "absolute_path": os.path.abspath(output_path),
                            "original_uuid": job_id
                        })
                except ffmpeg.Error as e:
                    self.logger.error(f"FFmpeg error on chunk {i}: {e.stderr.decode('utf8')}")
                    continue

            # Return JSON List of Chunks
            import json
            return FlowFileTransformResult(
                relationship="success",
                contents=json.dumps(chunks_metadata, indent=2),
                attributes={
                    "chunk.count": str(len(chunks_metadata)), 
                    "mime.type": "application/json",
                    "video.job.id": job_id
                }
            )

        except Exception as e:
            self.logger.error(f"Error chunking video: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
        finally:
            # We DO NOT clean up job_dir here, because downstream processors need the files.
            # Users MUST use FetchFile (Completion Strategy: Delete) or a cleanup flow.
            # We remove input file though to save space
            if os.path.exists(input_path):
                os.remove(input_path)
