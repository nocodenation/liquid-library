/*
 * Skill Info Data Object
 */
package org.liquidmx.claude.api;

import java.time.Instant;

/**
 * Information about a Claude skill.
 */
public class SkillInfo {

    private final String name;
    private final String description;
    private final Instant addedAt;

    public SkillInfo(String name, String description, Instant addedAt) {
        this.name = name;
        this.description = description;
        this.addedAt = addedAt;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
