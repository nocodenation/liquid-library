"""
Pydantic models for Claude NiFi Bridge API.
"""

from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ═══════════════════════════════════════════════════════════════════════════════
# REQUEST MODELS
# ═══════════════════════════════════════════════════════════════════════════════


class PromptRequest(BaseModel):
    """Request to execute a prompt in a Claude session."""

    session_key: str = Field(..., description="Unique key for the session")
    prompt: str = Field(..., description="The prompt to execute")
    tool_mode: Optional[str] = Field(
        None, description="Tool mode override: READ_ONLY|FILE_ACCESS|FULL"
    )
    allowed_tools: Optional[List[str]] = Field(
        None, description="Explicit tool list (overrides tool_mode)"
    )
    working_directory: Optional[str] = Field(
        None, description="Override working directory for this prompt"
    )
    system_prompt_append: Optional[str] = Field(
        None, description="Additional instructions to append to system prompt"
    )
    max_turns: Optional[int] = Field(
        None, description="Maximum agent turns before stopping"
    )
    metadata: Optional[Dict[str, str]] = Field(
        default_factory=dict, description="Pass-through metadata"
    )


class CreateSessionRequest(BaseModel):
    """Request to create a new Claude session."""

    session_key: str = Field(..., description="Unique key for the session")
    tool_mode: Optional[str] = Field("READ_ONLY", description="Tool mode for session")
    working_directory: Optional[str] = Field(
        None, description="Working directory for session"
    )
    system_prompt_append: Optional[str] = Field(
        None, description="Instructions to append to system prompt"
    )
    initial_context: Optional[Dict[str, str]] = Field(
        None, description="Initial context key-value pairs"
    )


class AddSkillRequest(BaseModel):
    """Request to add a skill."""

    name: str = Field(..., description="Skill name (identifier)")
    content: str = Field(..., description="Skill markdown content (SKILL.md format)")
    description: Optional[str] = Field(None, description="Short description of skill")


class UpdateConfigRequest(BaseModel):
    """Request to update runtime configuration."""

    updates: Dict[str, str] = Field(..., description="Configuration key-value updates")


# ═══════════════════════════════════════════════════════════════════════════════
# RESPONSE MODELS
# ═══════════════════════════════════════════════════════════════════════════════


class PromptResponse(BaseModel):
    """Response from a prompt execution."""

    result: str = Field(..., description="Claude's response text")
    session_key: str = Field(..., description="Session key used")
    session_id: str = Field(..., description="Internal Claude session ID")
    tools_used: List[Dict[str, Any]] = Field(
        default_factory=list, description="Tools invoked during execution"
    )
    turns_used: int = Field(0, description="Number of agent turns")
    duration_ms: int = Field(0, description="Processing time in milliseconds")
    metadata: Dict[str, str] = Field(
        default_factory=dict, description="Pass-through metadata"
    )


class SessionInfo(BaseModel):
    """Information about a Claude session."""

    session_key: str = Field(..., description="Unique session key")
    session_id: Optional[str] = Field(None, description="Internal Claude session ID")
    tool_mode: str = Field(..., description="Tool mode for this session")
    working_directory: str = Field(..., description="Working directory path")
    created_at: datetime = Field(..., description="When session was created")
    last_active_at: datetime = Field(..., description="Last activity timestamp")
    prompt_count: int = Field(0, description="Number of prompts in this session")
    state: str = Field("ACTIVE", description="Session state: ACTIVE|IDLE|EXPIRED")


class SkillInfo(BaseModel):
    """Information about a skill."""

    name: str = Field(..., description="Skill name")
    description: Optional[str] = Field(None, description="Skill description")
    added_at: datetime = Field(..., description="When skill was added")


class SkillContent(BaseModel):
    """Full skill content."""

    name: str = Field(..., description="Skill name")
    content: str = Field(..., description="Skill markdown content")


class HealthResponse(BaseModel):
    """Health check response."""

    status: str = Field(..., description="Service status")
    version: str = Field(..., description="Service version")
    active_sessions: int = Field(..., description="Number of active sessions")
    uptime_seconds: int = Field(..., description="Service uptime in seconds")


class Metrics(BaseModel):
    """Service metrics."""

    active_session_count: int = Field(..., description="Number of active sessions")
    total_prompts_processed: int = Field(..., description="Total prompts processed")
    average_response_time_ms: float = Field(..., description="Average response time")
    prompts_per_session: Dict[str, int] = Field(
        default_factory=dict, description="Prompt count per session"
    )


class ErrorResponse(BaseModel):
    """Error response."""

    error: str = Field(..., description="Error message")
    detail: Optional[str] = Field(None, description="Detailed error information")
