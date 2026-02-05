# NiFi Claude Bundle

Apache NiFi bundle for Claude Code integration.

## Overview

This bundle provides a Controller Service and processors for integrating Claude Code with Apache NiFi. It communicates with the Claude NiFi Bridge (Python) via HTTP.

## Modules

| Module | Description |
|--------|-------------|
| `nifi-claude-api` | Controller Service interface and data models |
| `nifi-claude-service` | Controller Service implementation |
| `nifi-claude-processors` | NiFi processors (PromptClaude, etc.) |
| `nifi-claude-nar` | NAR packaging for deployment |

## Building

```bash
cd nifi-claude-bundle
mvn clean install
```

The NAR file will be generated at:
```
nifi-claude-nar/target/nifi-claude-nar-1.0.0-SNAPSHOT.nar
```

## Installation

Copy the NAR file to NiFi's lib directory:
```bash
cp nifi-claude-nar/target/nifi-claude-nar-*.nar $NIFI_HOME/lib/
```

Restart NiFi to load the new components.

## Components

### Controller Service: ClaudeCodeService

Configure connection to the Claude NiFi Bridge.

| Property | Description | Default |
|----------|-------------|---------|
| Bridge URL | URL of Claude Bridge service | `http://localhost:8099` |
| Default Tool Mode | READ_ONLY, FILE_ACCESS, or FULL | READ_ONLY |
| Allow Tool Override | Allow per-session tool mode changes | true |
| Session Timeout | Inactive session timeout (minutes) | 30 |
| Max Sessions | Maximum concurrent sessions | 50 |

### Processors

| Processor | Description |
|-----------|-------------|
| PromptClaude | Execute prompts in Claude sessions |
| CreateClaudeSession | Explicitly create a session |
| CloseClaudeSession | Close a session |
| ListClaudeSessions | List active sessions |
| AddClaudeSkill | Add a skill |
| RemoveClaudeSkill | Remove a skill |
| ListClaudeSkills | List available skills |
| UpdateClaudeConfig | Update runtime configuration |

## Usage Example

1. Add `ClaudeCodeService` Controller Service
2. Configure Bridge URL to point to Claude NiFi Bridge
3. Enable the service
4. Add `PromptClaude` processor to your flow
5. Configure session key and prompt source
6. Connect to downstream processors

## Requirements

- Apache NiFi 1.24.0+
- Java 11+
- Claude NiFi Bridge running and accessible

## License

Proprietary - Liquid.MX
