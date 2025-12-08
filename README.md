# liquid-library
The complete Library of Processors and Features of NoCodeNation

## AttributeToParameter
A Python-based NiFi processor that updates a specific parameter in a Parameter Context with a value from a FlowFile attribute.

### Key Features
*   **Dynamic Updates:** Update parameters at runtime driven by data flow.
*   **Asynchronous Updates:** Uses the NiFi "Update Request" flow (`/update-requests`) to safely update Parameter Contexts even when they are referenced by running processors. NiFi automatically handles stopping, updating, and restarting affected components.
*   **Optimistic Locking:** Implements retry logic (3 attempts with backoff) to handle `409 Conflict` errors if multiple threads try to update the context simultaneously.
*   **Security:** Supports Authentication Tokens, Client Certificates (mTLS), and Username/Password exchange.

### Usage Caveats: Parallel vs. Downstream Visibility
When using this processor, it is important to understand when the updated parameter value becomes visible:

1.  **Downstream Processors (Safe):**
    The processor waits (polls) for the update to complete before transferring the FlowFile to the `success` relationship. Therefore, **any processor downstream of the `success` relationship is guaranteed to see the new parameter value**.

2.  **Parallel Processors:**
    For processors running in parallel (not downstream of this processor):
    *   **Race Condition:** If they execute before the update completes, they see the old value.
    *   **Interruption:** NiFi *must* stop all components referencing the Parameter Context to apply the update. Parallel processors using the context will be temporarily stopped and restarted by NiFi during the update process.

### Configuration
| Property | Description |
| :--- | :--- |
| **NiFi API URL** | Base URL of the NiFi API (e.g., `http://localhost:8080/nifi-api`). **Note:** If running inside a container, use the container hostname/ID (e.g., `https://<container-id>:8443/nifi-api`) instead of `localhost`. |
| **Parameter Context Name** | Name of the context to update. |
| **Parameter Name** | Name of the parameter to set. |
| **Parameter Value** | Value to set (supports Expression Language). |
| **Authentication Strategy** | Priority: Token > SSL Context Service > Check Client Cert Prop > Username/Password. |

## AnalyzeVideoFrames
Analyzes a video chunk by sampling frames and sending them to an OpenAI Vision model (GPT-4o) to generate a textual description.

### Key Features
*   **Frame Sampling**: Extracts frames at specified intervals to reduce token usage and latency.
*   **Vision Analysis**: keyframes are sent to OpenAI's Vision model to understand context, actions, and objects.

### Configuration
| Property | Description |
| :--- | :--- |
| **OpenAI API Key** | Your OpenAI API Key. |
| **Frame Interval** | Extract one frame every N frames. |
| **Max Frames** | Maximum number of frames to send to the model per execution. |
| **Analysis Prompt** | The prompt to send to the model (e.g., "Describe the video detail"). |
| **Video File Path** | Optional path to a video file (if not using FlowFile content). |

## AttributesFromJSON
Parses a JSON object (from FlowFile content, a property, or a file) and promotes top-level keys to FlowFile attributes.

### Key Features
*   **Flexible Source:** Read JSON from Content, Text Property, or external File.
*   **Type Handling:** Nested objects/lists are stringified before being added as attributes.

### Configuration
| Property | Description |
| :--- | :--- |
| **JSON Source** | Where to read JSON from ("FlowFile Content", "JSON Text Property", "File"). |
| **JSON Text** | The JSON string (if Source is "JSON Text Property"). |
| **JSON File Path** | Path to the JSON file (if Source is "File"). |

## ChunkVideo
Splits a video file into smaller temporal chunks (e.g., 60 seconds) with optional overlap. Useful for processing large videos in parallel or for RAG ingestion.

### Usage Note
This processor outputs a **JSON List** of chunk metadata (paths, timestamps). It writes the chunk files to a temporary directory. You should follow this processor with `SplitJson` -> `FetchFile` (with Completion Strategy 'Delete File') to process the chunks.

### Configuration
| Property | Description |
| :--- | :--- |
| **Chunk Duration** | Duration of each chunk in seconds. |
| **Overlap Duration** | Overlap with previous chunk in seconds (useful for maintaining context). |
| **Temporary Directory** | Directory to store the generated video chunks. |

## ExtractAudioFromVideo
Extracts the audio track from a video FlowFile and outputs an MP3 FlowFile.

### Configuration
| Property | Description |
| :--- | :--- |
| **Video File Path** | Optional path to video file (if not using FlowFile content). |

## FetchGMailMessage
Fetches the full content of a specific Gmail message by ID. Designed to be used after `ListGMailInbox`.

### Key Features
*   **Flexible Output:** Returns either full JSON metadata/body or Raw RFC822 content.
*   **State Management:** Can mark messages as read after fetching.

### Configuration
| Property | Description |
| :--- | :--- |
| **Token File Path** | Path to `token.json` (OAuth2 credentials). |
| **Message ID** | ID of the message to fetch. |
| **Output Format** | `JSON` or `RAW` (RFC822). |
| **Mark as Read** | Remove UNREAD label after fetching. |

## GenerateEmbedding
Generates a vector embedding for input text using OpenAI's embedding models (e.g., `text-embedding-3-small`).

### Key Features
*   **RAG Ready:** Produces JSON array embeddings suitable for vector databases.
*   **Flexible I/O:** input text from content or attribute; output to content or attribute.

### Configuration
| Property | Description |
| :--- | :--- |
| **OpenAI API Key** | Your OpenAI API Key. |
| **Input Text** | Text to embed (defaults to FlowFile content). |
| **Embedding Model** | Model name (e.g., `text-embedding-3-small`). |
| **Output Attribute** | Attribute to store result (if empty, writes to Content). |

## GetGoogleMail
A Source processor that fetches emails from Gmail. Similar to `FetchGMailMessage` but functions as a standalone entry point. Currently fetches one message per execution.

### Configuration
| Property | Description |
| :--- | :--- |
| **Token File Path** | Path to `token.json`. |
| **Search Query** | Gmail search query (e.g., `is:unread`). |
| **Mark as Read** | Mark fetched messages as read. |
| **Output Format** | `JSON` or `RAW`. |

## GetMicrosoftMail
Fetches emails from Microsoft 365 / Outlook using the Microsoft Graph API.

### Key Features
*   **Graph API:** Uses the modern Microsoft Graph API.
*   **Token Refresh:** Handles automatic token refresh using config/tokens generated by `MicrosoftOAuthManager`.

### Configuration
| Property | Description |
| :--- | :--- |
| **Token File Path** | Path to `token.json` (generated by MicrosoftOAuthManager). |
| **Folder ID** | Folder to search (default `Inbox`). |
| **Filter Query** | OData filter (e.g., `isRead eq false`). |
| **Mark as Read** | Mark messages as read after fetching. |
| **Output Format** | `JSON` or `RAW`. |

## GetYouTubeVideo
Downloads a YouTube video as an MP4 file using `yt-dlp`.

### Configuration
| Property | Description |
| :--- | :--- |
| **YouTube URL** | The URL of the video to download. |

## GoogleOAuthManager
A helper processor for the Google OAuth 2.0 Authorization Code Flow.
*   **Mode 1 (Generate URL):** Generates the login URL for the user.
*   **Mode 2 (Exchange Code):** Exchanges the callback code for access/refresh tokens.

### Configuration
| Property | Description |
| :--- | :--- |
| **Credentials File** | Path to `credentials.json` (from Google Console). |
| **Redirect URI** | Callback URI (must match Google Console). |
| **Scopes** | OAuth scopes. |

## ListGMailInbox
Lists emails from Gmail based on a search query. Outputs metadata (Snippet, Subject, Date) but not full body.

### Configuration
| Property | Description |
| :--- | :--- |
| **Token File Path** | Path to `token.json`. |
| **Search Query** | Query to filter emails. |
| **Max Results** | Maximum emails to list per execution. |

## MicrosoftOAuthManager
A helper processor for the Microsoft Graph OAuth 2.0 Authorization Code Flow.
*   **Generate URL:** Generates the Microsoft login URL.
*   **Exchange Code:** Exchanges the code for tokens and saves them to a file structure usage by `GetMicrosoftMail`.

### Configuration
| Property | Description |
| :--- | :--- |
| **Client ID** | Application Client ID (from Entra ID). |
| **Client Secret** | Application Client Secret. |
| **Tenant ID** | Tenant ID (or `common`). |
| **Redirect URI** | Callback URI. |
| **Scopes** | OAuth scopes. |

## TranscribeAudio
Transcribes audio files (MP3, WAV, etc.) into text using OpenAI's Whisper API.

### Configuration
| Property | Description |
| :--- | :--- |
| **OpenAI API Key** | Your OpenAI API Key. |
| **Language** | ISO-639-1 language code (optional/auto-detect). |
| **Prompt** | Text prompt to guide style or context. |
