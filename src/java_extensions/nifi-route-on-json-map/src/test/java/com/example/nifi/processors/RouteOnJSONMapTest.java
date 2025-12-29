package com.example.nifi.processors;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RouteOnJSONMapTest {

    private TestRunner runner;

    @BeforeEach
    public void init() {
        runner = TestRunners.newTestRunner(RouteOnJSONMap.class);
    }

    @Test
    public void testRoutingWithJSONProperty() {
        String jsonRules = "{" +
                "\"route_1\": \"${http.uri:equals('/start')}\"," +
                "\"route_2\": \"${http.uri:equals('/end')}\"," +
                "\"route_always\": \"true\"" +
                "}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.uri", "/start");

        runner.enqueue("data", attributes);
        runner.run();

        runner.assertTransferCount("route_1", 1);
        runner.assertTransferCount("route_always", 1);
        runner.assertTransferCount("route_2", 0);
        runner.assertTransferCount(RouteOnJSONMap.REL_UNMATCHED, 0);
    }

    @Test
    public void testRoutingUnmatched() {
        String jsonRules = "{" +
                "\"route_1\": \"${http.uri:equals('/other')}\"" +
                "}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.uri", "/start");

        runner.enqueue("data", attributes);
        runner.run();

        runner.assertTransferCount("route_1", 0);
        runner.assertTransferCount(RouteOnJSONMap.REL_UNMATCHED, 1);
    }

    @Test
    public void testRoutingMultipleMatches() {
        String jsonRules = "{" +
                "\"route_A\": \"true\"," +
                "\"route_B\": \"true\"" +
                "}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        runner.enqueue("data");
        runner.run();

        runner.assertTransferCount("route_A", 1);
        runner.assertTransferCount("route_B", 1);
        runner.assertTransferCount(RouteOnJSONMap.REL_UNMATCHED, 0);
    }

    @Test
    public void testRoutingWithJSONFile() throws IOException {
        File tempFile = File.createTempFile("rules", ".json");
        tempFile.deleteOnExit();
        String jsonRules = "{" +
                "\"route_file_1\": \"${my_attr:equals('yes')}\"" +
                "}";
        Files.write(tempFile.toPath(), jsonRules.getBytes());

        runner.setProperty(RouteOnJSONMap.JSON_RULES_FILE, tempFile.getAbsolutePath());

        Map<String, String> attributes = new HashMap<>();
        attributes.put("my_attr", "yes");

        runner.enqueue("data", attributes);
        runner.run();

        runner.assertTransferCount("route_file_1", 1);
        runner.assertTransferCount(RouteOnJSONMap.REL_UNMATCHED, 0);
    }

    @Test
    public void testInvalidJSON() {
        runner.setProperty(RouteOnJSONMap.JSON_RULES, "{invalid-json");
        runner.assertNotValid();
    }

    @Test
    public void testProblematicJSON() {
        // This resembles the user's input with unescaped quotes inside
        String jsonRules = "{" +
                "\"route_1\": \"${http.uri:equals(\"/start\")}\"" +
                "}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        // With sanitization logic, this should NOW be valid
        runner.assertValid();

        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.uri", "/start");
        runner.enqueue("data", attributes);
        runner.run();

        runner.assertTransferCount("route_1", 1);
    }

    @Test
    public void testCorrectedJSONWithSingleQuotes() {
        // This uses single quotes inside the EL, which is valid JSON and supported by
        // NiFi EL
        String jsonRules = "{" +
                "\"route_1\": \"${http.uri:equals('/start')}\"" +
                "}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        runner.assertValid();

        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.uri", "/start");
        runner.enqueue("data", attributes);
        runner.run();

        runner.assertTransferCount("route_1", 1);
    }

    @Test
    public void testLenientSingleQuoteFields() {
        // This uses single quotes for fields/values (relaxed JSON) which usually isn't
        // standard
        // but allowed by lenient parser.
        String jsonRules = "{'route_A': '${literal(true)}'}";

        runner.setProperty(RouteOnJSONMap.JSON_RULES, jsonRules);

        runner.assertValid();
        runner.enqueue("data");
        runner.run();
        runner.assertTransferCount("route_A", 1);
    }
}
