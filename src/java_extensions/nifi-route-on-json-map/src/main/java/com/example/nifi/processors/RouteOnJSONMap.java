package com.example.nifi.processors;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.nifi.annotation.behavior.DynamicRelationship;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.attribute.expression.language.Query;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@SideEffectFree
@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({ "json", "route", "attribute", "rules", "map" })
@CapabilityDescription("Routes FlowFiles based on rules defined in a JSON map. " +
        "The map keys define the relationship names, and the values are Expression Language expressions " +
        "that return a boolean value, or literal 'true'/'false'.")
@DynamicRelationship(name = "Relationship Name", description = "Relationship defined in the JSON map.")
public class RouteOnJSONMap extends AbstractProcessor {

    public static final PropertyDescriptor JSON_RULES = new PropertyDescriptor.Builder()
            .name("JSON Rules")
            .description(
                    "A JSON map defining the routing rules. Keys are route names, values are boolean expressions. " +
                            "Example: {\"route_1\": \"${http.uri:equals('/start')}\", \"route_always\": \"true\"}")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR) // Custom validation in customValidate
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    public static final PropertyDescriptor JSON_RULES_FILE = new PropertyDescriptor.Builder()
            .name("JSON Rules File")
            .description("Path to a file containing the JSON rules map. Overrides 'JSON Rules' if provided.")
            .required(false)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final Relationship REL_UNMATCHED = new Relationship.Builder()
            .name("unmatched")
            .description("FlowFiles that do not match any of the rules in the JSON map are routed here.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("FlowFiles that cause an error during processing (e.g., malformed JSON) are routed here.")
            .build();

    private List<PropertyDescriptor> properties;
    private final AtomicReference<Set<Relationship>> relationships = new AtomicReference<>(new HashSet<>());
    private final AtomicReference<Map<String, String>> cachedRules = new AtomicReference<>(new HashMap<>());
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_YAML_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
            .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(JSON_RULES);
        properties.add(JSON_RULES_FILE);
        this.properties = Collections.unmodifiableList(properties);

        relationships.set(new HashSet<>());
        addRelationship(REL_UNMATCHED);
        addRelationship(REL_FAILURE);
    }

    private void addRelationship(Relationship rel) {
        Set<Relationship> rels = new HashSet<>(relationships.get());
        rels.add(rel);
        relationships.set(Collections.unmodifiableSet(rels));
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships.get();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    public void onPropertyModified(final PropertyDescriptor descriptor, final String oldValue, final String newValue) {
        if (descriptor.equals(JSON_RULES) || descriptor.equals(JSON_RULES_FILE)) {
            // We can attempt to parse and update relationships here if feasible for UI
            // feedback,
            // but for File property, the content might not be available or might change
            // outside.
            // Ideally, we do this update whenever possible.
            try {
                if (descriptor.equals(JSON_RULES) && newValue != null) {
                    parseAndRegisterRelationships(newValue);
                }
                // For file, we might not want to read IO on every property modification in the
                // UI
                // but it helps to populate relationships.
                // We will defer actual heavy loading to customValidate or onScheduled,
                // but here request a validation trigger?
            } catch (Exception e) {
                getLogger().warn("Failed to update relationships on property modification: " + e.getMessage());
            }
        }
    }

    private void parseAndRegisterRelationships(String jsonContent) throws IOException {
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            return;
        }

        // Sanitize JSON to handle common user error of unescaped quotes in EL
        String cleanedJson = sanitizeJson(jsonContent);

        Map<String, String> rules;
        try {
            rules = objectMapper.readValue(cleanedJson, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            // Fallback to original content just in case sanitization broke something else,
            // though unlikely. If sanitization was needed, original will fail too.
            // But let's log the attempt or just let the original fail with the user's
            // error.
            if (!cleanedJson.equals(jsonContent)) {
                try {
                    rules = objectMapper.readValue(jsonContent, new TypeReference<Map<String, String>>() {
                    });
                } catch (IOException originalEx) {
                    // Throw the error from the sanitized version if it seems more plausible?
                    // Or unexpected. stick to the sanitized error if the original failed too.
                    throw e;
                }
            } else {
                throw e;
            }
        }

        updateRelationships(rules.keySet());
        cachedRules.set(rules);
    }

    private String sanitizeJson(String json) {
        StringBuilder sb = new StringBuilder();
        boolean inEL = false;
        // Simple state machine to track if we are inside ${...}
        // This is heuristic and assumes EL expressions are not nested with {} inside
        // quotes etc.
        // It converts " to ' ONLY when inside ${...}

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            // Check for start of EL
            if (!inEL && c == '$' && i + 1 < json.length() && json.charAt(i + 1) == '{') {
                inEL = true;
                sb.append(c);
                sb.append('{');
                i++; // Skip {
            } else if (inEL) {
                if (c == '}') {
                    inEL = false;
                    sb.append(c);
                } else if (c == '"') {
                    // Replace double quote with single quote inside EL
                    sb.append('\'');
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void updateRelationships(Set<String> ruleNames) {
        Set<Relationship> newRels = new HashSet<>();
        newRels.add(REL_UNMATCHED);
        newRels.add(REL_FAILURE);

        for (String name : ruleNames) {
            newRels.add(new Relationship.Builder().name(name).build());
        }
        relationships.set(Collections.unmodifiableSet(newRels));
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        final Collection<ValidationResult> results = new ArrayList<>();

        String jsonContent = null;
        String rulesFile = validationContext.getProperty(JSON_RULES_FILE).evaluateAttributeExpressions().getValue();
        String rulesInline = validationContext.getProperty(JSON_RULES).getValue();

        if (rulesFile != null && !rulesFile.isEmpty()) {
            File file = new File(rulesFile);
            if (file.exists() && file.canRead()) {
                try {
                    jsonContent = new String(Files.readAllBytes(file.toPath()));
                } catch (IOException e) {
                    results.add(new ValidationResult.Builder()
                            .subject("JSON Rules File")
                            .valid(false)
                            .explanation("Unable to read rules file: " + e.getMessage())
                            .build());
                }
            } else {
                results.add(new ValidationResult.Builder()
                        .subject("JSON Rules File")
                        .valid(false)
                        .explanation("File does not exist or is not readable.")
                        .build());
            }
        } else if (rulesInline != null && !rulesInline.isEmpty()) {
            jsonContent = rulesInline;
        } else {
            results.add(new ValidationResult.Builder()
                    .subject("Configuration")
                    .valid(false)
                    .explanation("Either 'JSON Rules' or 'JSON Rules File' must be provided.")
                    .build());
        }

        if (jsonContent != null) {
            try {
                // Sanitize JSON validation as well
                String cleanedJson = sanitizeJson(jsonContent);
                // Validate JSON syntax and structure
                Map<String, String> rules = objectMapper.readValue(cleanedJson,
                        new TypeReference<Map<String, String>>() {
                        });
                // Validate values are non-null check done by map structure indirectly?
                // Check if values can be parsed? The requirement says they can be EL or
                // "true"/"false".
                // Just checking basic map structure is a good start.

                // IMPORTANT: Update relationships during validation to reflect in UI
                updateRelationships(rules.keySet());
                cachedRules.set(rules);

            } catch (IOException e) {
                results.add(new ValidationResult.Builder()
                        .subject("JSON Rules")
                        .valid(false)
                        .explanation("Invalid JSON content: " + e.getMessage())
                        .build());
            }
        }

        return results;
    }

    @OnScheduled
    public void onScheduled(ProcessContext context) throws IOException {
        // Reload rules one last time to be sure
        String jsonContent = null;
        String rulesFile = context.getProperty(JSON_RULES_FILE).evaluateAttributeExpressions().getValue();

        if (rulesFile != null && !rulesFile.isEmpty()) {
            jsonContent = new String(Files.readAllBytes(new File(rulesFile).toPath()));
        } else {
            jsonContent = context.getProperty(JSON_RULES).getValue();
        }

        parseAndRegisterRelationships(jsonContent);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        Map<String, String> rules = cachedRules.get();
        if (rules == null || rules.isEmpty()) {
            getLogger().error("No routing rules configured.");
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        Set<Relationship> logicMatchingRelationships = new HashSet<>();

        try {
            for (Map.Entry<String, String> entry : rules.entrySet()) {
                String routeName = entry.getKey();
                String logicExpression = entry.getValue();

                boolean matches = false;

                // Check for literal "true" (ignoring case)
                if ("true".equalsIgnoreCase(logicExpression)) {
                    matches = true;
                } else if ("false".equalsIgnoreCase(logicExpression)) {
                    matches = false;
                } else {
                    // Evaluate as Expression Language
                    matches = context.newPropertyValue(logicExpression)
                            .evaluateAttributeExpressions(flowFile)
                            .asBoolean();
                }

                if (matches) {
                    Relationship rel = relationships.get().stream()
                            .filter(r -> r.getName().equals(routeName))
                            .findFirst()
                            .orElse(null);
                    if (rel != null) {
                        logicMatchingRelationships.add(rel);
                    } else {
                        // This shouldn't match if onScheduled did its job, but just in case
                        getLogger().warn("Route matched but relationship '{}' not found.", new Object[] { routeName });
                    }
                }
            }

            if (logicMatchingRelationships.isEmpty()) {
                session.transfer(flowFile, REL_UNMATCHED);
            } else {
                // If multiple matches, we clone. If single, we just transfer.
                // Standard route pattern: transfer original to first, clones to others?
                // Or clone for all except one?
                // Better: if size == 1, transfer.
                // If size > 1, clone for size-1, transfer original to last.

                List<Relationship> targets = new ArrayList<>(logicMatchingRelationships);
                for (int i = 0; i < targets.size() - 1; i++) {
                    FlowFile clone = session.clone(flowFile);
                    session.transfer(clone, targets.get(i));
                }
                session.transfer(flowFile, targets.get(targets.size() - 1));
            }

        } catch (Exception e) {
            getLogger().error("Error processing routing rules: " + e.getMessage(), e);
            session.transfer(flowFile, REL_FAILURE);
        }
    }
}
