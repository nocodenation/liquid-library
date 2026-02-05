# Claude NiFi Bridge
# FastAPI service for integrating Claude Code with Apache NiFi

FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY *.py .

# Create data directories
RUN mkdir -p /data/claude-workspaces \
             /data/claude-skills \
             /data/claude-sessions

# Create non-root user for security
RUN useradd -m -u 1000 claude && \
    chown -R claude:claude /app /data
USER claude

# Environment variables with defaults
ENV CLAUDE_WORKSPACE=/data/claude-workspaces \
    CLAUDE_SKILLS_DIR=/data/claude-skills \
    CLAUDE_DEFAULT_TOOL_MODE=READ_ONLY \
    CLAUDE_SESSION_TIMEOUT=30 \
    CLAUDE_MAX_SESSIONS=50 \
    CLAUDE_PERSISTENCE=NONE \
    CLAUDE_BRIDGE_PORT=8099

# Expose port
EXPOSE 8099

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8099/health || exit 1

# Start the bridge
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8099"]
