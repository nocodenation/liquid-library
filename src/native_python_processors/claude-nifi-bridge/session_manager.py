"""
Session management for Claude NiFi Bridge.

Manages Claude Agent SDK sessions with:
- Dynamic session keying
- Tool mode configuration
- Session persistence (optional)
- Automatic cleanup of expired sessions
"""

import json
import os
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

from models import SessionInfo


class ManagedSession:
    """
    Represents a managed Claude session.

    Tracks session state, configuration, and metrics.
    """

    def __init__(
        self,
        session_key: str,
        tool_mode: str,
        working_directory: str,
        system_prompt_append: Optional[str] = None,
    ):
        self.session_key = session_key
        self.session_id: Optional[str] = None  # Set after first prompt
        self.tool_mode = tool_mode
        self.working_directory = working_directory
        self.system_prompt_append = system_prompt_append
        self.created_at = datetime.utcnow()
        self.last_active_at = datetime.utcnow()
        self.prompt_count = 0
        self.state = "ACTIVE"

    def touch(self) -> None:
        """Update last active timestamp."""
        self.last_active_at = datetime.utcnow()
        self.state = "ACTIVE"

    def to_info(self) -> SessionInfo:
        """Convert to SessionInfo model."""
        return SessionInfo(
            session_key=self.session_key,
            session_id=self.session_id,
            tool_mode=self.tool_mode,
            working_directory=self.working_directory,
            created_at=self.created_at,
            last_active_at=self.last_active_at,
            prompt_count=self.prompt_count,
            state=self.state,
        )

    def to_dict(self) -> Dict[str, Any]:
        """Serialize for persistence."""
        return {
            "session_key": self.session_key,
            "session_id": self.session_id,
            "tool_mode": self.tool_mode,
            "working_directory": self.working_directory,
            "system_prompt_append": self.system_prompt_append,
            "created_at": self.created_at.isoformat(),
            "last_active_at": self.last_active_at.isoformat(),
            "prompt_count": self.prompt_count,
            "state": self.state,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ManagedSession":
        """Deserialize from persistence."""
        session = cls(
            session_key=data["session_key"],
            tool_mode=data["tool_mode"],
            working_directory=data["working_directory"],
            system_prompt_append=data.get("system_prompt_append"),
        )
        session.session_id = data.get("session_id")
        session.created_at = datetime.fromisoformat(data["created_at"])
        session.last_active_at = datetime.fromisoformat(data["last_active_at"])
        session.prompt_count = data.get("prompt_count", 0)
        session.state = data.get("state", "IDLE")
        return session


class SessionManager:
    """
    Manages Claude sessions with support for:
    - Dynamic session creation and lookup
    - Tool mode configuration
    - Session timeout and cleanup
    - Optional persistence
    """

    def __init__(
        self,
        base_workspace: str,
        default_tool_mode: str = "READ_ONLY",
        session_timeout_minutes: int = 30,
        max_sessions: int = 50,
        persistence_type: str = "NONE",
        persistence_path: Optional[str] = None,
    ):
        self.sessions: Dict[str, ManagedSession] = {}
        self.base_workspace = base_workspace
        self.default_tool_mode = default_tool_mode
        self.session_timeout_minutes = session_timeout_minutes
        self.max_sessions = max_sessions
        self.persistence_type = persistence_type
        self.persistence_path = persistence_path

        # Metrics
        self.total_prompts = 0
        self.total_response_time_ms = 0

        # Ensure base workspace exists
        os.makedirs(base_workspace, exist_ok=True)

        # Load persisted sessions if configured
        if persistence_type != "NONE":
            self._load_persisted_sessions()

    def get_or_create_session(
        self,
        session_key: str,
        tool_mode: Optional[str] = None,
        working_directory: Optional[str] = None,
        system_prompt_append: Optional[str] = None,
    ) -> ManagedSession:
        """
        Get existing session or create new one.

        Args:
            session_key: Unique identifier for the session
            tool_mode: Optional tool mode override
            working_directory: Optional working directory override
            system_prompt_append: Optional system prompt addition

        Returns:
            ManagedSession instance
        """
        if session_key in self.sessions:
            session = self.sessions[session_key]
            session.touch()
            return session

        # Check max sessions limit
        if len(self.sessions) >= self.max_sessions:
            self._cleanup_oldest_session()

        # Determine working directory
        work_dir = working_directory or os.path.join(self.base_workspace, session_key)
        os.makedirs(work_dir, exist_ok=True)

        # Create new session
        session = ManagedSession(
            session_key=session_key,
            tool_mode=tool_mode or self.default_tool_mode,
            working_directory=work_dir,
            system_prompt_append=system_prompt_append,
        )
        self.sessions[session_key] = session
        self._persist_sessions()

        return session

    def get_session(self, session_key: str) -> Optional[ManagedSession]:
        """Get session by key, or None if not found."""
        return self.sessions.get(session_key)

    def close_session(self, session_key: str) -> bool:
        """
        Close and remove a session.

        Args:
            session_key: Session to close

        Returns:
            True if session existed and was closed
        """
        if session_key in self.sessions:
            del self.sessions[session_key]
            self._persist_sessions()
            return True
        return False

    def list_sessions(self) -> List[SessionInfo]:
        """List all active sessions."""
        return [s.to_info() for s in self.sessions.values()]

    def cleanup_expired_sessions(self) -> int:
        """
        Remove sessions that have exceeded timeout.

        Returns:
            Number of sessions cleaned up
        """
        now = datetime.utcnow()
        timeout = timedelta(minutes=self.session_timeout_minutes)
        expired_keys = [
            key
            for key, session in self.sessions.items()
            if now - session.last_active_at > timeout
        ]

        for key in expired_keys:
            self.sessions[key].state = "EXPIRED"
            del self.sessions[key]

        if expired_keys:
            self._persist_sessions()

        return len(expired_keys)

    def record_prompt_metrics(self, duration_ms: int) -> None:
        """Record metrics for a completed prompt."""
        self.total_prompts += 1
        self.total_response_time_ms += duration_ms

    def get_average_response_time(self) -> float:
        """Get average response time in milliseconds."""
        if self.total_prompts == 0:
            return 0.0
        return self.total_response_time_ms / self.total_prompts

    def _cleanup_oldest_session(self) -> None:
        """Remove the oldest (least recently active) session."""
        if not self.sessions:
            return
        oldest = min(self.sessions.values(), key=lambda s: s.last_active_at)
        del self.sessions[oldest.session_key]

    def _persist_sessions(self) -> None:
        """Persist session mappings to storage."""
        if self.persistence_type == "NONE" or not self.persistence_path:
            return

        if self.persistence_type == "FILE":
            data = {key: session.to_dict() for key, session in self.sessions.items()}
            Path(self.persistence_path).parent.mkdir(parents=True, exist_ok=True)
            with open(self.persistence_path, "w") as f:
                json.dump(data, f, indent=2)

        # TODO: Implement Redis persistence if needed

    def _load_persisted_sessions(self) -> None:
        """Load session mappings from storage."""
        if self.persistence_type == "NONE" or not self.persistence_path:
            return

        if self.persistence_type == "FILE":
            if not os.path.exists(self.persistence_path):
                return
            try:
                with open(self.persistence_path, "r") as f:
                    data = json.load(f)
                for key, session_data in data.items():
                    self.sessions[key] = ManagedSession.from_dict(session_data)
            except (json.JSONDecodeError, KeyError) as e:
                # Log error but continue with empty sessions
                print(f"Warning: Failed to load persisted sessions: {e}")
