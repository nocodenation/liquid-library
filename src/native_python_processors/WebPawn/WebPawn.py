import json
import os
import base64
import time
from typing import List, Dict, Any, Optional

# NiFi API
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# External Dependencies
# Note: These need to be installed in the environment where NiFi runs
try:
    from playwright.sync_api import sync_playwright, Page, BrowserContext
    import google.generativeai as genai
except ImportError:
    # We'll handle missing dependencies gracefully or let it fail if required
    pass

class WebPawn(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """A "Human-in-the-loop" web automation processor using Playwright and Gemini 1.5 Flash. 
        It executes actions, takes screenshots, and replays journals to restore state.
        
        Features:
        - Helper-Free: No recurring costs or subscriptions beyond Gemini API.
        - Persistent Sessions: Uses JSON journals to replay browser state.
        - Smart Interactions: Uses LLM to determine CSS selectors for generic instructions.
        - Scroll Support: Supports "scroll down/up" commands.
        """
        tags = ['playwright', 'browser', 'automation', 'llm', 'gemini', 'web']

    def __init__(self, **kwargs):
        pass

    # ==========================================================================
    # Properties
    # ==========================================================================

    START_URL = PropertyDescriptor(
        name="Start URL",
        description="The initial URL to load when starting a new session (empty input FlowFile or reset).",
        required=False,
        validators=[StandardValidators.URL_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    JOURNAL_DIRECTORY = PropertyDescriptor(
        name="Journal Directory",
        description="Directory to store and load action journals (JSON files).",
        required=True,
        default_value="/files/webpawn_journals",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    SCREENSHOT_DIRECTORY = PropertyDescriptor(
        name="Screenshot Directory",
        description="Directory to save screenshots.",
        required=True,
        default_value="/files/webpawn_screenshots",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    GEMINI_API_KEY = PropertyDescriptor(
        name="Gemini API Key",
        description="API Key for Google Gemini 1.5 Flash (used to interpret instructions).",
        required=True,
        sensitive=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    GEMINI_MODEL_NAME = PropertyDescriptor(
        name="Gemini Model Name",
        description="The name of the Gemini model to use (e.g., gemini-2.0-flash, gemini-flash-latest). Check logs for available models if 404 occurs.",
        required=True,
        default_value="gemini-2.5-flash",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    HEADLESS_MODE = PropertyDescriptor(
        name="Headless Mode",
        description="Run browser in headless mode.",
        required=True,
        default_value="true",
        allowable_values=["true", "false"]
    )

    BROWSER_TYPE = PropertyDescriptor(
        name="Browser Type",
        description="The type of browser to use.",
        required=True,
        default_value="chromium",
        allowable_values=["chromium", "firefox", "webkit"]
    )

    def getPropertyDescriptors(self):
        return [
            self.START_URL,
            self.JOURNAL_DIRECTORY,
            self.SCREENSHOT_DIRECTORY,
            self.GEMINI_API_KEY,
            self.GEMINI_MODEL_NAME,
            self.HEADLESS_MODE,
            self.BROWSER_TYPE
        ]

    # ==========================================================================
    # Transform Logic
    # ==========================================================================

    def transform(self, context, flowFile):
        # 1. Setup Configuration
        start_url = context.getProperty(self.START_URL).evaluateAttributeExpressions(flowFile).getValue()
        journal_dir = context.getProperty(self.JOURNAL_DIRECTORY).evaluateAttributeExpressions().getValue()
        screenshot_dir = context.getProperty(self.SCREENSHOT_DIRECTORY).evaluateAttributeExpressions().getValue()
        api_key = context.getProperty(self.GEMINI_API_KEY).getValue()
        model_name_prop = context.getProperty(self.GEMINI_MODEL_NAME).evaluateAttributeExpressions().getValue()
        headless = context.getProperty(self.HEADLESS_MODE).asBoolean()
        browser_type_name = context.getProperty(self.BROWSER_TYPE).getValue()

        # Ensure directories exist
        os.makedirs(journal_dir, exist_ok=True)
        os.makedirs(screenshot_dir, exist_ok=True)

        # Auto-install browser to ensure binary matches python package version
        self._ensure_browser_installed(browser_type_name)

        # Configure GenAI
        if api_key:
            genai.configure(api_key=api_key)

        # Determine Input State
        # - Attributes mapping:
        #   - 'json_journal_location': If present, we are continuing a session
        #   - 'next_step_prompt': The instruction for the next step
        #   - 'start_url': Overrides property if present (handled above partially, need priority)
        
        attributes = flowFile.getAttributes()
        journal_loc_attr = attributes.get('json_journal_location')
        next_step_prompt = attributes.get('next_step_prompt')
        
        # Override start_url from attribute if present
        if attributes.get('start_url'):
            start_url = attributes.get('start_url')

        # Logic Branching
        try:
            if not journal_loc_attr and start_url:
                return self._start_new_session(start_url, journal_dir, screenshot_dir, headless, browser_type_name)
            elif journal_loc_attr and next_step_prompt:
                return self._execute_next_step(journal_loc_attr, next_step_prompt, journal_dir, screenshot_dir, headless, browser_type_name, model_name_prop)
            elif journal_loc_attr and attributes.get('replay_mode') == 'true':
                 return self._replay_session(journal_loc_attr, headless, browser_type_name)
            else:
                # No valid trigger found
                # If flowfile is empty and no start_url property, we can't do anything
                if not start_url and not journal_loc_attr:
                     self.logger.error("No Start URL configured and no Journal Location provided.")
                     return FlowFileTransformResult(relationship="failure")
                
                # Default to starting new session if we have a start_url property but no incoming journal
                if start_url:
                    return self._start_new_session(start_url, journal_dir, screenshot_dir, headless, browser_type_name)
                
                return FlowFileTransformResult(relationship="failure")

        except Exception as e:
            self.logger.error(f"WebPawn Error: {str(e)}")
            # In a real scenario, we might want to attach the error as an attribute
            return FlowFileTransformResult(relationship="failure")

    # ==========================================================================
    # Helper Methods
    # ==========================================================================

    def _start_new_session(self, url, journal_dir, screenshot_dir, headless, browser_type_name):
        self.logger.info(f"Starting new WebPawn session for {url}")
        
        session_id = str(int(time.time() * 1000))
        screenshot_filename = f"screenshot_{session_id}_step_0.png"
        screenshot_path = os.path.join(screenshot_dir, screenshot_filename)
        journal_filename = f"journal_{session_id}.json"
        journal_path = os.path.join(journal_dir, journal_filename)

        try:
            with sync_playwright() as p:
                browser = self._launch_browser(p, browser_type_name, headless)
                context = browser.new_context()
                page = context.new_page()
                
                self.logger.info(f"Navigating to {url}")
                page.goto(url)
                page.wait_for_load_state("networkidle") # Wait for simple load
                
                # Take initial screenshot
                page.screenshot(path=screenshot_path)
                
                browser.close()

            # Create Initial Journal
            initial_journal = [
                {
                    "step_id": 0,
                    "action": "goto",
                    "params": {"url": url},
                    "timestamp": time.time(),
                    "screenshot_path": screenshot_path
                }
            ]
            self._save_journal(journal_path, initial_journal)
            
            # Attributes to return
            attributes = {
                "json_journal_location": journal_path,
                "screenshot_path": screenshot_path,
                "current_url": url, # Ideally we get page.url but browser is closed. Input url is close enough for start.
                "previous_action_step": "start"
            }
            
            return FlowFileTransformResult(relationship="success", attributes=attributes)

        except Exception as e:
            self.logger.error(f"Failed to start session: {e}")
            return FlowFileTransformResult(relationship="failure")

    def _launch_browser(self, playwright, browser_type_name, headless):
        if browser_type_name == "firefox":
            return playwright.firefox.launch(headless=headless)
        elif browser_type_name == "webkit":
            return playwright.webkit.launch(headless=headless)
        else:
            return playwright.chromium.launch(headless=headless)

    def _ensure_browser_installed(self, browser_type_name):
        if hasattr(self, '_browser_installed_checked'):
            return

        import subprocess
        import sys
        
        self.logger.info(f"Checking {browser_type_name} installation...")
        try:
            cmd = [sys.executable, "-m", "playwright", "install", browser_type_name]
            self.logger.info(f"Running: {' '.join(cmd)}")
            subprocess.check_call(cmd)
            self._browser_installed_checked = True
        except Exception as e:
            self.logger.error(f"Failed to auto-install browser: {e}")

    def _save_journal(self, path, journal_data):
        with open(path, 'w') as f:
            json.dump(journal_data, f, indent=2)

    def _load_journal(self, path):
        if not os.path.exists(path):
            raise FileNotFoundError(f"Journal not found at {path}")
        with open(path, 'r') as f:
            return json.load(f)

    def _execute_next_step(self, journal_path, prompt, journal_dir, screenshot_dir, headless, browser_type_name, model_name):
        self.logger.info(f"Executing next step: {prompt}")
        
        try:
            journal = self._load_journal(journal_path)
            if not journal:
                raise ValueError("Journal is empty")

            current_step_id = len(journal)
            screenshot_filename = f"screenshot_{int(time.time())}_step_{current_step_id}.png"
            screenshot_path = os.path.join(screenshot_dir, screenshot_filename)

            with sync_playwright() as p:
                browser = self._launch_browser(p, browser_type_name, headless)
                context = browser.new_context()
                page = context.new_page()
                
                # REPLAY
                self._replay_journal(page, journal)
                
                # EXECUTE NEXT STEP via LLM
                resolved_action = self._get_llm_action(page, prompt, model_name)
                self.logger.info(f"LLM Resolved Action: {resolved_action}")
                
                self._execute_action(page, resolved_action)
                
                # Take result screenshot
                page.screenshot(path=screenshot_path)
                browser.close()

            # Update Journal
            new_entry = {
                "step_id": current_step_id,
                "user_instruction": prompt,
                "resolved_action": resolved_action["action"],
                "resolved_params": resolved_action.get("params", {}),
                "timestamp": time.time(),
                "screenshot_path": screenshot_path
            }
            journal.append(new_entry)
            self._save_journal(journal_path, journal)

            attributes = {
                "json_journal_location": journal_path,
                "screenshot_path": screenshot_path,
                "current_url": "unknown", # browser closed
                "previous_action_step": prompt
            }
            return FlowFileTransformResult(relationship="success", attributes=attributes)

        except Exception as e:
            self.logger.error(f"Failed to execute step: {e}")
            return FlowFileTransformResult(relationship="failure")

    def _get_llm_action(self, page, prompt, model_name):
        # 1. Capture Screenshot as Base64 for Gemini
        screenshot_bytes = page.screenshot()

        # 2. Prepare Prompt
        system_instruction = """
        You are an advanced web automation agent. You will receive a screenshot of a website and a user instruction.
        
        Your goal is to output a JSON object describing the precise Playwright action to perform to fulfill the instruction.
        
        Supported Actions:
        - {"action": "click", "params": {"selector": "css_selector"}}
        - {"action": "fill", "params": {"selector": "css_selector", "value": "text_to_type"}}
        - {"action": "press", "params": {"selector": "css_selector", "key": "Enter"}}
        - {"action": "scroll", "params": {"direction": "down", "amount": "page"}} (amount can be "page" or pixel int)
        - {"action": "goto", "params": {"url": "https://..."}}
        
        Rules:
        - Return ONLY valid JSON.
        - Create specific, robust CSS selectors (prefer IDs, unique classes, data-testid, or aria-labels).
        - If the instruction is vague, do your best to infer the intent from the UI state.
        """
        
        # Use provided model name, fallback handling is still useful for debugging
        model = genai.GenerativeModel(model_name)
        
        retries = 3
        last_error = None
        
        for i in range(retries):
            try:
                response = model.generate_content([
                    system_instruction,
                    f"User Instruction: {prompt}",
                    {"mime_type": "image/png", "data": screenshot_bytes}
                ])
                
                # Clean response if markdown code blocks are present
                text = response.text.strip()
                if text.startswith("```json"):
                    text = text[7:]
                if text.endswith("```"):
                    text = text[:-3]
                
                action_data = json.loads(text.strip())
                return action_data

            except Exception as e:
                last_error = e
                # Check for 429 (Resource Exhausted)
                if "429" in str(e):
                    wait_time = (i + 1) * 2 # 2s, 4s, 6s
                    self.logger.info(f"Quota exceeded (429). Retrying in {wait_time}s... (Attempt {i+1}/{retries})")
                    time.sleep(wait_time)
                    continue
                else:
                    self.logger.error(f"Failed to call Gemini ({model_name}): {e}")
                    # Fallback debug: List available models to help user fix it
                    try:
                        available = [m.name for m in genai.list_models()]
                        self.logger.error(f"Available models: {available}")
                    except:
                        pass
                    raise ValueError(f"LLM Interpretation failed. Check logs for available models. Error: {e}")

        # If we exhausted retries
        raise ValueError(f"LLM Interpretation failed after {retries} retries. Last Error: {last_error}")

    def _execute_action(self, page, action_data):
        action = action_data.get("action")
        params = action_data.get("params", {})
        
        self.logger.info(f"Playwright Action: {action} on {params}")
        
        # We use .first to handle cases where the LLM returns a selector that matches multiple elements 
        # (e.g., "Dismiss" buttons). This prevents "strict mode violation" errors.
        if action == "click":
            # Heuristic: If multiple elements match, try to find the first VISIBLE one.
            # This handles cases like multiple "Dismiss" buttons where only one is shown.
            loc = page.locator(params["selector"])
            count = loc.count()
            
            if count > 1:
                clicked = False
                self.logger.info(f"Selector matched {count} elements. Searching for first visible one...")
                for i in range(count):
                    if loc.nth(i).is_visible():
                        self.logger.info(f"Clicking visible element index {i}")
                        loc.nth(i).click()
                        clicked = True
                        break
                if not clicked:
                    self.logger.warning("No visible elements found among matches. Forcing click on first...")
                    loc.first.click(force=True)
            else:
                loc.click() # Standard wait for visibility
        elif action == "fill":
            page.locator(params["selector"]).first.fill(params["value"])
        elif action == "press":
            page.locator(params["selector"]).first.press(params["key"])
        elif action == "scroll":
            direction = params.get("direction", "down")
            amount = params.get("amount", "page")
            
            if amount == "page":
                 # Get viewport height
                viewport_height = page.viewport_size['height'] if page.viewport_size else 800
                delta_y = viewport_height if direction == "down" else -viewport_height
            else:
                try:
                    delta_y = int(amount)
                    if direction == "up": delta_y = -delta_y
                except:
                    delta_y = 500 if direction == "down" else -500

            page.mouse.wheel(0, delta_y)

        elif action == "goto":
            page.goto(params["url"])
        else:
            raise ValueError(f"Unknown action: {action}")
        
        # Stability wait
        try:
            page.wait_for_load_state("networkidle", timeout=3000)
        except:
            pass # Timeout is fine if page didn't reload

    def _replay_journal(self, page, journal):
        self.logger.info("Replaying journal...")
        for step in journal:
            action = step.get("action")
            params = step.get("params", {})
            
            if action == "goto":
                page.goto(params.get("url"))
            elif action == "click":
                page.click(params.get("selector"))
            elif action == "fill":
                page.fill(params.get("selector"), params.get("value"))
            elif action == "press":
                page.locator(params.get("selector")).first.press(params.get("key"))
            elif action == "scroll":
                direction = params.get("direction", "down")
                amount = params.get("amount", "page")
                # Simple replay using wheel
                viewport_height = page.viewport_size['height'] if page.viewport_size else 800
                delta_y = viewport_height if direction == "down" else -viewport_height
                if amount != "page":
                     try:
                        delta_y = int(amount)
                        if direction == "up": delta_y = -delta_y
                     except: pass
                page.mouse.wheel(0, delta_y)
            # Add more actions as needed
            
            # Should we wait after each step?
            # page.wait_for_load_state("networkidle") # Maybe too aggressive for every step
            page.wait_for_timeout(500) # Small stability wait

    def _replay_session(self, journal_path, headless, browser_type_name):
        self.logger.info(f"Replaying session from {journal_path}")
        try:
            journal = self._load_journal(journal_path)
            if not journal:
                raise ValueError("Journal is empty")

            with sync_playwright() as p:
                browser = self._launch_browser(p, browser_type_name, headless)
                context = browser.new_context()
                page = context.new_page()
                
                # REPLAY ALL
                self._replay_journal(page, journal)
                
                # Take final screenshot
                final_screenshot_path = os.path.join(os.path.dirname(journal_path), f"replay_final_{int(time.time())}.png")
                page.screenshot(path=final_screenshot_path)
                browser.close()
            
            return FlowFileTransformResult(relationship="success")
            
        except Exception as e:
            self.logger.error(f"Failed to replay session: {e}")
            return FlowFileTransformResult(relationship="failure")
