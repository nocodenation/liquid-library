"""
Claude NiFi Bridge - FastAPI Application

HTTP API for integrating Claude Code with Apache NiFi.
Provides session management, skill handling, and prompt execution.
"""

import os
import time
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from models import (
    AddSkillRequest,
    CreateSessionRequest,
    ErrorResponse,
    HealthResponse,
    Metrics,
    PromptRequest,
    PromptResponse,
    SessionInfo,
    SkillContent,
    SkillInfo,
    UpdateConfigRequest,
)
from session_manager import SessionManager
from skill_manager import SkillManager

# ═══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════════════

# Tool mode definitions
TOOL_MODES = {
    "READ_ONLY": ["Read", "Glob", "Grep"],
    "FILE_ACCESS": ["Read", "Glob", "Grep", "Edit", "Write"],
    "FULL": [
        "Read",
        "Glob",
        "Grep",
        "Edit",
        "Write",
        "Bash",
        "WebSearch",
        "WebFetch",
        "Task",
    ],
}


def get_allowed_tools(
    tool_mode: str, custom_tools: Optional[List[str]] = None
) -> List[str]:
    """Resolve allowed tools based on mode or custom list."""
    if custom_tools:
        return custom_tools
    return TOOL_MODES.get(tool_mode, TOOL_MODES["READ_ONLY"])


# ═══════════════════════════════════════════════════════════════════════════════
# GLOBAL STATE
# ═══════════════════════════════════════════════════════════════════════════════

session_manager: Optional[SessionManager] = None
skill_manager: Optional[SkillManager] = None
config: Dict[str, str] = {}
start_time: datetime = datetime.utcnow()


# ═══════════════════════════════════════════════════════════════════════════════
# APPLICATION LIFECYCLE
# ═══════════════════════════════════════════════════════════════════════════════


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifecycle management."""
    global session_manager, skill_manager, config, start_time

    start_time = datetime.utcnow()

    # Load configuration from environment
    config = {
        "base_workspace": os.environ.get(
            "CLAUDE_WORKSPACE", "/data/claude-workspaces"
        ),
        "skills_dir": os.environ.get("CLAUDE_SKILLS_DIR", "/data/claude-skills"),
        "default_tool_mode": os.environ.get("CLAUDE_DEFAULT_TOOL_MODE", "READ_ONLY"),
        "session_timeout_minutes": os.environ.get("CLAUDE_SESSION_TIMEOUT", "30"),
        "max_sessions": os.environ.get("CLAUDE_MAX_SESSIONS", "50"),
        "persistence_type": os.environ.get("CLAUDE_PERSISTENCE", "NONE"),
        "persistence_path": os.environ.get("CLAUDE_PERSISTENCE_PATH", None),
        "allow_tool_override": os.environ.get("CLAUDE_ALLOW_TOOL_OVERRIDE", "true"),
    }

    # Initialize managers
    session_manager = SessionManager(
        base_workspace=config["base_workspace"],
        default_tool_mode=config["default_tool_mode"],
        session_timeout_minutes=int(config["session_timeout_minutes"]),
        max_sessions=int(config["max_sessions"]),
        persistence_type=config["persistence_type"],
        persistence_path=config["persistence_path"],
    )

    skill_manager = SkillManager(skills_dir=config["skills_dir"])

    print(f"Claude NiFi Bridge started")
    print(f"  Workspace: {config['base_workspace']}")
    print(f"  Skills: {config['skills_dir']}")
    print(f"  Default tool mode: {config['default_tool_mode']}")

    yield

    # Cleanup on shutdown
    print("Claude NiFi Bridge shutting down")


# Create FastAPI app
app = FastAPI(
    title="Claude NiFi Bridge",
    description="HTTP API for integrating Claude Code with Apache NiFi",
    version="1.0.0",
    lifespan=lifespan,
)


# ═══════════════════════════════════════════════════════════════════════════════
# HEALTH & METRICS ENDPOINTS
# ═══════════════════════════════════════════════════════════════════════════════


@app.get("/health", response_model=HealthResponse, tags=["Health"])
async def health():
    """Health check endpoint."""
    return HealthResponse(
        status="healthy",
        version="1.0.0",
        active_sessions=len(session_manager.sessions) if session_manager else 0,
        uptime_seconds=int((datetime.utcnow() - start_time).total_seconds()),
    )


@app.get("/metrics", response_model=Metrics, tags=["Health"])
async def get_metrics():
    """Get service metrics."""
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    return Metrics(
        active_session_count=len(session_manager.sessions),
        total_prompts_processed=session_manager.total_prompts,
        average_response_time_ms=session_manager.get_average_response_time(),
        prompts_per_session={
            s.session_key: s.prompt_count for s in session_manager.sessions.values()
        },
    )


# ═══════════════════════════════════════════════════════════════════════════════
# PROMPT ENDPOINTS
# ═══════════════════════════════════════════════════════════════════════════════


@app.post("/prompt", response_model=PromptResponse, tags=["Prompts"])
async def execute_prompt(request: PromptRequest):
    """
    Execute a prompt in a Claude session.

    Creates the session if it doesn't exist.
    Session context is preserved across prompts with the same session_key.
    """
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    start = time.time()

    # Get or create session
    session = session_manager.get_or_create_session(
        session_key=request.session_key,
        tool_mode=request.tool_mode,
        working_directory=request.working_directory,
        system_prompt_append=request.system_prompt_append,
    )

    # Determine tools
    allowed_tools = get_allowed_tools(
        request.tool_mode or session.tool_mode, request.allowed_tools
    )

    # TODO: Integrate with Claude Agent SDK
    # For now, return a placeholder response
    # This will be implemented in Phase 1 completion

    result = f"[PLACEHOLDER] Would execute prompt in session '{session.session_key}' with tools {allowed_tools}: {request.prompt[:100]}..."
    tools_used: List[Dict] = []
    turns = 0

    # Update session stats
    session.prompt_count += 1
    session.touch()

    duration_ms = int((time.time() - start) * 1000)
    session_manager.record_prompt_metrics(duration_ms)

    return PromptResponse(
        result=result,
        session_key=session.session_key,
        session_id=session.session_id or "pending",
        tools_used=tools_used,
        turns_used=turns,
        duration_ms=duration_ms,
        metadata=request.metadata or {},
    )


@app.post("/prompt/stream", tags=["Prompts"])
async def execute_prompt_streaming(request: PromptRequest):
    """
    Execute a prompt with streaming response (Server-Sent Events).

    Useful for long-running operations where progress updates are needed.
    """
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    async def event_generator():
        """Generate SSE events for streaming response."""
        session = session_manager.get_or_create_session(
            session_key=request.session_key,
            tool_mode=request.tool_mode,
            working_directory=request.working_directory,
        )

        # TODO: Implement actual streaming with Claude Agent SDK
        # For now, return placeholder events

        import json

        yield f"data: {json.dumps({'type': 'init', 'session_key': session.session_key})}\n\n"
        yield f"data: {json.dumps({'type': 'text', 'content': '[Streaming placeholder]'})}\n\n"
        yield f"data: {json.dumps({'type': 'result', 'content': 'Streaming response placeholder'})}\n\n"
        yield 'data: {"type": "done"}\n\n'

    return StreamingResponse(event_generator(), media_type="text/event-stream")


# ═══════════════════════════════════════════════════════════════════════════════
# SESSION ENDPOINTS
# ═══════════════════════════════════════════════════════════════════════════════


@app.get("/sessions", response_model=List[SessionInfo], tags=["Sessions"])
async def list_sessions():
    """List all active sessions."""
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    return session_manager.list_sessions()


@app.post("/sessions", response_model=SessionInfo, status_code=201, tags=["Sessions"])
async def create_session(request: CreateSessionRequest):
    """
    Explicitly create a session with specific configuration.

    Useful for pre-configuring before the first prompt.
    """
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    session = session_manager.get_or_create_session(
        session_key=request.session_key,
        tool_mode=request.tool_mode,
        working_directory=request.working_directory,
        system_prompt_append=request.system_prompt_append,
    )

    return session.to_info()


@app.get("/sessions/{session_key}", response_model=SessionInfo, tags=["Sessions"])
async def get_session(session_key: str):
    """Get information about a specific session."""
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    session = session_manager.get_session(session_key)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    return session.to_info()


@app.delete("/sessions/{session_key}", status_code=204, tags=["Sessions"])
async def close_session(session_key: str):
    """Close a session, freeing resources."""
    if not session_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    session_manager.close_session(session_key)


# ═══════════════════════════════════════════════════════════════════════════════
# SKILL ENDPOINTS
# ═══════════════════════════════════════════════════════════════════════════════


@app.get("/skills", response_model=List[SkillInfo], tags=["Skills"])
async def list_skills():
    """List all available skills."""
    if not skill_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    return skill_manager.list_skills()


@app.post("/skills", status_code=201, tags=["Skills"])
async def add_skill(request: AddSkillRequest):
    """Add a new skill."""
    if not skill_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    skill_manager.add_skill(request.name, request.content)
    return {"status": "created", "name": request.name}


@app.get("/skills/{skill_name}", response_model=SkillContent, tags=["Skills"])
async def get_skill(skill_name: str):
    """Get the content of a skill."""
    if not skill_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    content = skill_manager.get_skill_content(skill_name)
    if content is None:
        raise HTTPException(status_code=404, detail="Skill not found")

    return SkillContent(name=skill_name, content=content)


@app.delete("/skills/{skill_name}", status_code=204, tags=["Skills"])
async def remove_skill(skill_name: str):
    """Remove a skill."""
    if not skill_manager:
        raise HTTPException(status_code=503, detail="Service not initialized")

    if not skill_manager.remove_skill(skill_name):
        raise HTTPException(status_code=404, detail="Skill not found")


# ═══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION ENDPOINTS
# ═══════════════════════════════════════════════════════════════════════════════


@app.get("/config", tags=["Configuration"])
async def get_config():
    """Get current runtime configuration."""
    return config


@app.put("/config", tags=["Configuration"])
async def update_config(request: UpdateConfigRequest):
    """Update runtime configuration."""
    config.update(request.updates)
    return config


# ═══════════════════════════════════════════════════════════════════════════════
# ENTRY POINT
# ═══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("CLAUDE_BRIDGE_PORT", "8099"))
    uvicorn.run(app, host="0.0.0.0", port=port)
