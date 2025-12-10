package com.liquid.library.nifi.processors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.liquid.library.nifi.service.WebPawnService;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.*;

@Tags({"ai", "agent", "browser", "automation", "llm", "webpawn"})
@CapabilityDescription("An autonomous agent that navigates the web to achieve a specified goal. It iteratively analyzes pages using a global policy and task description.")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
public class OrchestrateWebPawn extends AbstractProcessor {

    public static final PropertyDescriptor WEBPAWN_SERVICE = new PropertyDescriptor.Builder()
            .name("WebPawn Service")
            .description("The WebPawn Controller Service to use for browser interaction.")
            .required(true)
            .identifiesControllerService(WebPawnService.class)
            .build();

    public static final PropertyDescriptor SESSION_ID = new PropertyDescriptor.Builder()
            .name("Session ID")
            .description("The ID of the browser session to use. If empty, defaults to the FlowFile UUID.")
            .required(false)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor TASK = new PropertyDescriptor.Builder()
            .name("Task")
            .description("The high-level goal to achieve (e.g., 'Find the pricing page and extract the cost').")
            .required(true)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor INSTRUCTIONS = new PropertyDescriptor.Builder()
            .name("Instructions")
            .description("Global rules and policies (e.g., 'Accept cookies', 'Do not click ads').")
            .required(true)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor MAX_STEPS = new PropertyDescriptor.Builder()
            .name("Max Steps")
            .description("The maximum number of steps the agent can take to solve the task.")
            .required(true)
            .defaultValue("10")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor VISION_MODEL = new PropertyDescriptor.Builder()
            .name("Vision Model")
            .description("The Gemini model to use for analyzing screenshots (The Eye). e.g. gemini-2.0-flash-001")
            .required(true)
            .defaultValue("gemini-2.0-flash-001")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor REASONING_MODEL = new PropertyDescriptor.Builder()
            .name("Reasoning Model")
            .description("The Gemini model to use for planning the next step (The Brain). e.g. gemini-2.5-pro")
            .required(true)
            .defaultValue("gemini-2.5-pro")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("The goal was achieved.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("System error or exception.")
            .build();

    public static final Relationship REL_HUMAN = new Relationship.Builder()
            .name("human_notification")
            .description("Agent failed to achieve goal within max steps or gave up.")
            .build();

    private static final List<PropertyDescriptor> properties;
    private static final Set<Relationship> relationships;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(WEBPAWN_SERVICE);
        props.add(SESSION_ID);
        props.add(TASK);
        props.add(INSTRUCTIONS);
        props.add(MAX_STEPS);
        props.add(VISION_MODEL);
        props.add(REASONING_MODEL);
        properties = Collections.unmodifiableList(props);

        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_FAILURE);
        rels.add(REL_HUMAN);
        relationships = Collections.unmodifiableSet(rels);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) return;

        WebPawnService service = context.getProperty(WEBPAWN_SERVICE).asControllerService(WebPawnService.class);
        String sessionId = context.getProperty(SESSION_ID).evaluateAttributeExpressions(flowFile).getValue();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = flowFile.getAttribute("uuid");
        }
        
        String task = context.getProperty(TASK).evaluateAttributeExpressions(flowFile).getValue();
        String instructions = context.getProperty(INSTRUCTIONS).evaluateAttributeExpressions(flowFile).getValue();
        int maxSteps = context.getProperty(MAX_STEPS).asInteger();
        String visionModel = context.getProperty(VISION_MODEL).getValue();
        String reasoningModel = context.getProperty(REASONING_MODEL).getValue();

        List<String> history = new ArrayList<>();
        boolean goalMet = false;
        String finalResult = "";
        
        Gson gson = new Gson();

        try {
            // Ensure session exists.
            // If it doesn't exist, this will create a new one (starting at about:blank).
            service.ensureSession(sessionId, null);

            for (int step = 1; step <= maxSteps; step++) {
                // 1. Observe: Get Screenshot
                String screenshotBase64;
                try {
                    screenshotBase64 = service.getScreenshot(sessionId);
                } catch (Exception e) {
                   // Session might be dead or invalid
                   getLogger().error("Session invalid or error taking screenshot", e);
                   session.transfer(flowFile, REL_FAILURE);
                   return;
                }

                // 2. Vision: Describe Page
                String visionPrompt = "Describe the UI elements, interactive components (buttons, links, inputs), and current page state in detail. Focus on elements relevant to: " + task;
                String pageDescription = service.askGemini(sessionId, visionPrompt, screenshotBase64, visionModel);

                // 3. Reason: Plan Next Step
                // Construct reasoning prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("You are an autonomous web agent. Your Goal is: ").append(task).append("\n");
                prompt.append("Global Instructions: ").append(instructions).append("\n");
                prompt.append("Current Page Description: ").append(pageDescription).append("\n");
                prompt.append("History of Actions:\n");
                for (String h : history) {
                    prompt.append("- ").append(h).append("\n");
                }
                prompt.append("\nDecide the next step. Output ONLY valid JSON with this format:\n");
                prompt.append("{\n  \"thought\": \"reasoning here\",\n  \"status\": \"CONTINUE\" or \"SUCCESS\" or \"FAILURE\",\n  \"action_type\": \"NAVIGATE\" or \"PROMPT\",\n  \"action_payload\": \"url or instruction\"\n}");

                String agentResponse = service.askGemini(sessionId, prompt.toString(), null, reasoningModel);
                
                // Parse Response
                // Extract JSON from potential markdown blocks
                String jsonStr = cleanupJson(agentResponse);
                JsonObject decision;
                try {
                     decision = gson.fromJson(jsonStr, JsonObject.class);
                } catch (Exception e) {
                    getLogger().warn("Failed to parse agent decision JSON: " + jsonStr);
                    // Retry step? Or fail? Let's just log and continue loop (wasting a step)
                    history.add("System Error: Failed to parse JSON response. " + e.getMessage());
                    continue;
                }

                String status = decision.get("status").getAsString().toUpperCase();
                String thought = decision.get("thought").getAsString();
                String actionType = decision.has("action_type") ? decision.get("action_type").getAsString().toUpperCase() : "";
                String actionPayload = decision.has("action_payload") ? decision.get("action_payload").getAsString() : "";

                history.add("Step " + step + ": " + thought + " -> " + status + " [" + actionType + "]");
                getLogger().info("Agent Step {}: {} -> {}", new Object[]{step, status, thought});

                if ("SUCCESS".equals(status)) {
                    goalMet = true;
                    finalResult = actionPayload; // The answer
                    break;
                } else if ("FAILURE".equals(status)) {
                    finalResult = actionPayload; // Failure reason
                    break;
                }

                // Execute Action
                if ("NAVIGATE".equals(actionType)) {
                    service.navigate(sessionId, actionPayload);
                } else if ("PROMPT".equals(actionType)) {
                    // We treat PROMPT as "Execute Playwright Action via Gemini" (The recursive usage of WebPawnService.executePrompt)
                    // Wait, executePrompt uses generic Gemini to map instruction -> playwright. 
                    // Since we have the Reasoning model deciding the intent, we can feed that intent to executePrompt
                    // which uses standard WebPawn logic to convert "Click button" -> Playwright code.
                    service.executePrompt(sessionId, actionPayload);
                }
                
                // Small sleep? 
                Thread.sleep(1000);
            }

            // Write Output
            Map<String, String> outputMap = new HashMap<>();
            outputMap.put("history", gson.toJson(history));
            outputMap.put("result", finalResult);
            outputMap.put("outcome", goalMet ? "SUCCESS" : "FAILURE"); // or TIMEOUT if loop finished without status
            
            String jsonOutput = gson.toJson(outputMap);
            flowFile = session.write(flowFile, out -> out.write(jsonOutput.getBytes()));

            if (goalMet) {
                session.transfer(flowFile, REL_SUCCESS);
            } else {
                session.transfer(flowFile, REL_HUMAN);
            }

        } catch (Exception e) {
            getLogger().error("Orchestration failed", e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }

    private String cleanupJson(String input) {
        if (input.contains("```json")) {
            return input.substring(input.indexOf("```json") + 7, input.lastIndexOf("```")).trim();
        } else if (input.contains("```")) {
            return input.substring(input.indexOf("```") + 3, input.lastIndexOf("```")).trim();
        }
        return input.trim();
    }
}
