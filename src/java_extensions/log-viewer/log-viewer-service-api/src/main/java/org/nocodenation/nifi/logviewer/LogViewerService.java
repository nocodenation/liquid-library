package org.nocodenation.nifi.logviewer;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.ControllerService;

@Tags({"OAuth2", "Access", "Token", "Provider", "NoCodeNation"})
@CapabilityDescription("Log Viewer")
public interface LogViewerService extends ControllerService {
    // Interface marker

}
