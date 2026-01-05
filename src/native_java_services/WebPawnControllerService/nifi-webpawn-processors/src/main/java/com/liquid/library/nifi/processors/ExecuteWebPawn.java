package com.liquid.library.nifi.processors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import com.liquid.library.nifi.service.ExecutionResult;
import com.liquid.library.nifi.service.WebPawnService;

@Tags({"web", "browser", "automation", "pawn", "gemini"})
@CapabilityDescription("Executes commands against a persistent WebPawn browser session via the WebPawnService.")
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
public class ExecuteWebPawn extends AbstractProcessor {

    public static final PropertyDescriptor WEBPAWN_SERVICE = new PropertyDescriptor.Builder()
            .name("WebPawn Service")
            .description("The WebPawn Controller Service to use.")
            .required(true)
            .identifiesControllerService(WebPawnService.class)
            .build();

    public static final PropertyDescriptor SESSION_ID = new PropertyDescriptor.Builder()
            .name("Session ID")
            .description("Unique identifier for the browser session.")
            .required(true)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ACTION = new PropertyDescriptor.Builder()
            .name("Action")
            .description("The action to perform. Valid values: START_SESSION, PROMPT, NAVIGATE, GET_SCREENSHOT, CLOSE_SESSION.")
            .required(true)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("PROMPT")
            .build();

    public static final PropertyDescriptor INSTRUCTION = new PropertyDescriptor.Builder()
            .name("Instruction / Promp / URL")
            .description("The prompt for the LLM, or the URL for navigation. Not used for START/CLOSE/SCREENSHOT.")
            .required(false)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor OUTPUT_FORMAT = new PropertyDescriptor.Builder()
            .name("Output Format")
            .description("The format of the output content. Valid values: JSON, RAW_BYTES.")
            .required(true)
            .expressionLanguageSupported(org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("JSON")
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successful execution.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Failed execution.")
            .build();

    private static final List<PropertyDescriptor> properties;
    private static final Set<Relationship> relationships;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(WEBPAWN_SERVICE);
        props.add(SESSION_ID);
        props.add(ACTION);
        props.add(INSTRUCTION);
        props.add(OUTPUT_FORMAT);
        properties = Collections.unmodifiableList(props);

        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_FAILURE);
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
        if (flowFile == null && !context.getProperty(ACTION).evaluateAttributeExpressions().getValue().toUpperCase().equals("START_SESSION")) {
             // START_SESSION might be triggered without input, but typically even that has a trigger.
             if (flowFile == null) return; 
        }
        if (flowFile == null) {
            flowFile = session.create();
        }

        try {
            WebPawnService service = context.getProperty(WEBPAWN_SERVICE).asControllerService(WebPawnService.class);
            String sessionId = context.getProperty(SESSION_ID).evaluateAttributeExpressions(flowFile).getValue();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = flowFile.getAttribute("uuid");
            }
            
            String action = context.getProperty(ACTION).evaluateAttributeExpressions(flowFile).getValue().toUpperCase().trim();
            String instruction = context.getProperty(INSTRUCTION).evaluateAttributeExpressions(flowFile).getValue();
            String outputFormat = context.getProperty(OUTPUT_FORMAT).evaluateAttributeExpressions(flowFile).getValue().toUpperCase().trim();

            boolean success = true;
            byte[] finalContent = "{}".getBytes(); // Default to empty JSON bytes
            
            switch (action) {
                case "START_SESSION":
                    success = service.ensureSession(sessionId, instruction); // Instruction used as Start URL here
                    finalContent = ("{\"status\": \"Session Created\", \"sessionId\": \"" + sessionId + "\"}").getBytes();
                    break;
                case "NAVIGATE":
                    service.navigate(sessionId, instruction);
                    finalContent = ("{\"status\": \"Navigated\", \"url\": \"" + instruction + "\"}").getBytes();
                    break;
                case "PROMPT":
                    ExecutionResult result = service.executePrompt(sessionId, instruction);
                    if (!result.isSuccess()) success = false;
                    
                    if (outputFormat.equals("RAW_BYTES")) {
                         // PROMPT returns a screenshot too, usually.
                         // But PROMPT response is complex. If RAW_BYTES is requested for PROMPT, maybe we just return the screenshot?
                         // Or we stick to JSON for PROMPT because it has metadata.
                         // Let's assume RAW_BYTES primarily targets GET_SCREENSHOT, but if users want the screenshot from PROMPT:
                         if (result.getScreenshotBase64() != null) {
                             finalContent = java.util.Base64.getDecoder().decode(result.getScreenshotBase64());
                         }
                    } else {
                        // Unescaped JSON in description! POC only.
                         String json = String.format("{\"action\": \"%s\", \"description\": \"%s\", \"success\": %b, \"screenshot\": \"%s\"}", 
                            result.getAction(), result.getDescription(), result.isSuccess(), result.getScreenshotBase64());
                         finalContent = json.getBytes();
                    }
                    break;
                case "GET_SCREENSHOT":
                    String b64 = service.getScreenshot(sessionId);
                    if (outputFormat.equals("RAW_BYTES")) {
                        finalContent = java.util.Base64.getDecoder().decode(b64);
                    } else {
                        finalContent = String.format("{\"screenshot\": \"%s\"}", b64).getBytes();
                    }
                    break;
                case "CLOSE_SESSION":
                    service.closeSession(sessionId);
                    finalContent = "{\"status\": \"Session Closed\"}".getBytes();
                    break;
                default:
                    // If action is invalid or unknown
                     finalContent = ("{\"error\": \"Unknown action: " + action + "\"}").getBytes();
                     success = false;
            }

            // Write result to FlowFile
            final byte[] contentToWrite = finalContent;
            flowFile = session.write(flowFile, out -> out.write(contentToWrite));
            
            if (success) {
                session.transfer(flowFile, REL_SUCCESS);
            } else {
                session.transfer(flowFile, REL_FAILURE);
            }

        } catch (Exception e) {
            getLogger().error("ExecuteWebPawn failed", e);
            flowFile = session.write(flowFile, out -> out.write(("Error: " + e.getMessage()).getBytes()));
            session.transfer(flowFile, REL_FAILURE);
        }
    }
}
