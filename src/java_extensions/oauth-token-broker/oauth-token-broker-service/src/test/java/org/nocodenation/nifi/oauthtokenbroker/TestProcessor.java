package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Test processor that uses the OAuth2AccessTokenProvider service
 */
public class TestProcessor extends AbstractProcessor {

    public static final PropertyDescriptor OAUTH2_SERVICE = new PropertyDescriptor.Builder()
            .name("OAuth2 Token Provider")
            .description("Provides OAuth2 token management capabilities")
            .identifiesControllerService(OAuth2AccessTokenProvider.class)
            .required(true)
            .build();

    private static final List<PropertyDescriptor> properties;
    private static final Set<Relationship> relationships;

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(OAUTH2_SERVICE);
        properties = Collections.unmodifiableList(props);
        
        relationships = Collections.emptySet();
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
        // This is just a test processor, so it doesn't need to do anything
    }
}
