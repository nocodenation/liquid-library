# WebPawn - Human-in-the-Loop Web Automation

WebPawn is a powerful NiFi Python Processor that enables **Human-in-the-loop web automation** using **Playwright** and **Google Gemini 1.5 Flash**.

It is designed to handle complex web tasks that require intelligent decision-making or human guidance, persisting the state of the browser session across NiFi's stateless executions via a JSON journal.

## Features

- **Helper-Free**: Operates without external helper utilities or paid subscriptions (besides the Gemini API).
- **Persistent Sessions**: Uses a lightweight JSON journal to record and replay browser actions, allowing the processor to pick up where it left off.
- **Smart Interactions**: Leverages a Multimodal LLM (Gemini 1.5 Flash) to interpret natural language instructions (e.g., "Click the login button") and translate them into precise Playwright actions.
- **Accessibility Tree & Visual Context**: Sends both the screenshot and context to the LLM for accurate element targeting.
- **Scroll Support**: Natively supports "scroll" actions to navigate pages.

## Configuration

| Property | Description | Default Value |
| :--- | :--- | :--- |
| **Start URL** | The initial URL to load when starting a new session. | - |
| **Journal Directory** | Directory to store and load action journals (`.json`). | `/files/webpawn_journals` |
| **Screenshot Directory** | Directory to save screenshots for each step. | `/files/webpawn_screenshots` |
| **Gemini API Key** | Your Google Gemini API Key. | - |
| **Gemini Model Name** | The Gemini model to use (e.g., `gemini-2.5-flash`). | `gemini-2.5-flash` |
| **Headless Mode** | Run the browser in headless mode (true/false). | `true` |
| **Browser Type** | The browser engine to use (`chromium`, `firefox`, `webkit`). | `chromium` |

## Usage

1.  **Start a Session**:
    *   Send an empty FlowFile or a FlowFile with a `start_url` attribute (or configure the property).
    *   WebPawn will launch the browser, navigate to the URL, take a screenshot, and output a FlowFile with a `json_journal_location` attribute.

2.  **Execute Instructions**:
    *   Feed the FlowFile back into WebPawn with a `next_step_prompt` attribute (e.g., "Click the 'Sign In' button").
    *   WebPawn will:
        1.  Replay the previous session history from the journal.
        2.  Take a fresh screenshot.
        3.  Ask Gemini how to perform the requested action.
        4.  Execute the action (Click/Fill/Press/Scroll).
        5.  Update the journal and output the FlowFile with the new state.

## Output Attributes

-   `json_journal_location`: Path to the current session's journal file.
-   `screenshot_path`: Path to the latest screenshot.
-   `current_url`: The URL of the page (if available).
-   `previous_action_step`: The instruction that was just executed.
