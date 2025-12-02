# Helper Script for GetGoogleMail Processor

This directory contains a helper script to generate the OAuth 2.0 `token.json` required by the `GetGoogleMail` NiFi processor.

## Prerequisites

1.  **Google Cloud Project**:
    *   Create a project in the [Google Cloud Console](https://console.cloud.google.com/).
    *   Enable the **Gmail API**.
    *   Go to **APIs & Services > Credentials**.
    *   Create **OAuth 2.0 Client IDs** with application type **Desktop app**.
    *   Download the JSON file, rename it to `credentials.json`, and place it in this directory (`python_extensions/GetGoogleMail/Helpers/`).

2.  **Python**: Ensure you have Python installed on your local machine.

## Setup and Usage

It is recommended to use a virtual environment to install dependencies to avoid conflicts with your system Python.

### 1. Create a Virtual Environment

Run the following command in your terminal (from the project root or inside this folder):

```bash
# Create a virtual environment named 'venv'
python3 -m venv venv
```

### 2. Activate the Virtual Environment

*   **macOS/Linux:**
    ```bash
    source venv/bin/activate
    ```
*   **Windows:**
    ```cmd
    venv\Scripts\activate
    ```

### 3. Install Dependencies

With the virtual environment activated, install the required libraries:

```bash
pip install -r requirements.txt
```

### 4. Generate the Token

Run the script:

```bash
python generate_token.py
```

1.  A browser window will open asking you to log in to your Google account.
2.  Grant the requested permissions (Read/Write access to Gmail).
3.  Once successful, the script will generate a `token.json` file in this directory.

### 5. Use in NiFi

1.  Copy the generated `token.json` to a location accessible by your NiFi container (e.g., the `files/` directory mounted at `/files/`).
2.  In NiFi, configure the **GetGoogleMail** processor:
    *   **Token File Path**: `/files/token.json` (or wherever you mounted it).

### 6. Deactivate Virtual Environment

When finished, you can exit the virtual environment:

```bash
deactivate
```
