# Agentic Workflow for Video Analysis

This flow implements an agentic workflow for analyzing video content. It downloads video, extracts audio, transcribes it, analyzes video frames using AI, and generates embeddings for the analysis.

## Processors

-   **GetYouTubeVideo** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Downloads a video from YouTube given a URL.
-   **ExtractAudioFromVideo** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Extracts the audio track from the video file.
-   **AnalyzeVideoFrames** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Analyzes frames from the video using an AI model (e.g., OpenAI) to describe events and objects.
-   **Extract AudioTranscriptionContent** ![Built-in](https://img.shields.io/badge/built--in-grey): Extracts the text content from the audio transcription.
-   **GenerateEmbedding** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Generates vector embeddings for the combined analysis of visuals and audio transcription.
-   **AttributesFromJSON** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Extracts attributes from JSON content.
-   **SplitJson** ![Built-in](https://img.shields.io/badge/built--in-grey): Splits JSON content into individual FlowFiles.

## Configuration

### GetYouTubeVideo
-   **YouTube URL**: The URL of the YouTube video to process (referenced by `${yt_url}`).

### AnalyzeVideoFrames
-   **Max Frames**: Number of frames to analyze (e.g., 10).
-   **Analysis Prompt**: The prompt sent to the AI model (e.g., "Describe the events, objects, and actions in this video segment in detail.").
-   **Frame Interval**: Interval between frames to analyze.

### GenerateEmbedding
-   **Input Text**: Combines visual analysis and audio transcription for embedding generation.
-   **Embedding Model**: The model used for generating embeddings (e.g., `text-embedding-3-small`).
-   **Output Attribute**: The attribute where the embedding vector will be stored (`embedding_vector_json`).

## Usage

This workflow is designed for deep content analysis of videos, enabling search, summarization, or indexing of video content based on both visual and audio information.
