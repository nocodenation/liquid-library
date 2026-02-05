# Claude NiFi Bridge

HTTP API service for integrating Claude Code with Apache NiFi.

## Overview

This service provides a REST API that wraps the Claude Agent SDK, enabling Apache NiFi to interact with Claude Code for AI-powered automation tasks.

### Key Features

- **Session Management**: Dynamic session keying with context preservation
- **Tool Mode Configuration**: READ_ONLY, FILE_ACCESS, or FULL access modes
- **Skill Management**: Add/remove skills at runtime
- **Streaming Support**: Server-Sent Events for long-running operations
- **Session Persistence**: Optional file-based or Redis persistence

## Quick Start

### Local Development

```bash
# Create virtual environment
python -m venv venv
source venv/bin/activate  # or `venv\Scripts\activate` on Windows

# Install dependencies
pip install -r requirements.txt

# Set environment variables
export ANTHROPIC_API_KEY=your-api-key
export CLAUDE_WORKSPACE=/tmp/claude-workspaces
export CLAUDE_SKILLS_DIR=/tmp/claude-skills

# Run the service
python main.py
```

### Docker

```bash
# Build image
docker build -t claude-nifi-bridge:latest .

# Run container
docker run -d \
  -p 8099:8099 \
  -e ANTHROPIC_API_KEY=your-api-key \
  -v claude-data:/data \
  claude-nifi-bridge:latest
```

## API Endpoints

### Health & Metrics

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/metrics` | GET | Service metrics |

### Prompts

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/prompt` | POST | Execute a prompt |
| `/prompt/stream` | POST | Execute with streaming |

### Sessions

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/sessions` | GET | List all sessions |
| `/sessions` | POST | Create a session |
| `/sessions/{key}` | GET | Get session info |
| `/sessions/{key}` | DELETE | Close a session |

### Skills

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/skills` | GET | List all skills |
| `/skills` | POST | Add a skill |
| `/skills/{name}` | GET | Get skill content |
| `/skills/{name}` | DELETE | Remove a skill |

### Configuration

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/config` | GET | Get configuration |
| `/config` | PUT | Update configuration |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | - | Anthropic API key (required) |
| `CLAUDE_WORKSPACE` | `/data/claude-workspaces` | Base workspace directory |
| `CLAUDE_SKILLS_DIR` | `/data/claude-skills` | Skills storage directory |
| `CLAUDE_DEFAULT_TOOL_MODE` | `READ_ONLY` | Default tool mode |
| `CLAUDE_SESSION_TIMEOUT` | `30` | Session timeout (minutes) |
| `CLAUDE_MAX_SESSIONS` | `50` | Maximum concurrent sessions |
| `CLAUDE_PERSISTENCE` | `NONE` | Persistence type (NONE/FILE/REDIS) |
| `CLAUDE_PERSISTENCE_PATH` | - | Path for persistence storage |
| `CLAUDE_BRIDGE_PORT` | `8099` | Service port |

## Tool Modes

| Mode | Tools Available |
|------|-----------------|
| `READ_ONLY` | Read, Glob, Grep |
| `FILE_ACCESS` | Read, Glob, Grep, Edit, Write |
| `FULL` | All tools including Bash |

## Example Usage

### Execute a Prompt

```bash
curl -X POST http://localhost:8099/prompt \
  -H "Content-Type: application/json" \
  -d '{
    "session_key": "project:my-api",
    "prompt": "Analyze the authentication module in auth.py",
    "tool_mode": "READ_ONLY"
  }'
```

### Add a Skill

```bash
curl -X POST http://localhost:8099/skills \
  -H "Content-Type: application/json" \
  -d '{
    "name": "security-reviewer",
    "content": "# Security Reviewer\n\n> Use for security audits\n\n## Guidelines\n..."
  }'
```

## Integration with NiFi

This service is designed to be used with the NiFi Claude Controller Service and processors. See the Java extension documentation for NiFi integration details.

## License

Proprietary - Liquid.MX
