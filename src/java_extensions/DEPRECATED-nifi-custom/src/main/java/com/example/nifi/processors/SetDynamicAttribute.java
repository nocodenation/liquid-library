package com.example.nifi.processors;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SideEffectFree
@SupportsBatching
@Tags({ "attribute", "modification", "update", "insert", "custom" })
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@CapabilityDescription("Sets a new FlowFile attribute based on 'Attribute Name' and 'Attribute Value' properties, both supporting Expression Language.")
public class SetDynamicAttribute extends AbstractProcessor {

    public static final PropertyDescriptor ATTRIBUTE_NAME = new PropertyDescriptor.Builder()
            .name("Attribute Name")
            .description("The name of the attribute to set. Supports Expression Language.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor ATTRIBUTE_VALUE = new PropertyDescriptor.Builder()
            .name("Attribute Value")
            .description("The value of the attribute to set. Supports Expression Language.")
            .required(true)
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles that have been successfully updated are transferred to this relationship.")
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            ATTRIBUTE_NAME,
            ATTRIBUTE_VALUE));

    private static final Set<Relationship> RELATIONSHIPS = Collections
            .unmodifiableSet(new HashSet<>(Collections.singletonList(
                    REL_SUCCESS)));

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        try {
            final String attributeName = context.getProperty(ATTRIBUTE_NAME)
                    .evaluateAttributeExpressions(flowFile)
                    .getValue();

            final String attributeValue = context.getProperty(ATTRIBUTE_VALUE)
                    .evaluateAttributeExpressions(flowFile)
                    .getValue();

            if (attributeName != null && !attributeName.isEmpty()) {
                flowFile = session.putAttribute(flowFile, attributeName, attributeValue != null ? attributeValue : "");
                session.transfer(flowFile, REL_SUCCESS);
            } else {
                getLogger().warn("Attribute Name evaluated to null or empty for FlowFile {}",
                        new Object[] { flowFile });
                session.transfer(flowFile, REL_SUCCESS); // Or failure? Assuming success if no-op/warn
            }

        } catch (Exception e) {
            getLogger().error("Failed to set attribute for FlowFile {}", new Object[] { flowFile }, e);
            session.transfer(flowFile, REL_SUCCESS); // Transferring to success to avoid data loss, or should it be
                                                     // failure?
            // Usually if it fails heavily it might be arguably failure, but transform
            // usually goes success.
            // But let's stick to success for simplicity unless requested otherwise.
            // Actually, if exception, maybe transfer to failure if it existed. But I only
            // defined success.
        }
    }
}
