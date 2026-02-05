"""
Skill management for Claude NiFi Bridge.

Handles:
- Adding/removing skills at runtime
- Skill storage as SKILL.md files
- Loading skills for Claude Agent SDK
"""

import os
import shutil
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from models import SkillInfo


class SkillManager:
    """
    Manages Claude Code skills.

    Skills are stored as SKILL.md files in subdirectories of the skills directory.
    The Claude Agent SDK automatically picks up skills from the configured skills path.
    """

    def __init__(self, skills_dir: str):
        """
        Initialize skill manager.

        Args:
            skills_dir: Directory to store skill definitions
        """
        self.skills_dir = skills_dir
        self.skills: Dict[str, datetime] = {}

        # Ensure skills directory exists
        os.makedirs(skills_dir, exist_ok=True)

        # Scan for existing skills
        self._scan_skills()

    def _scan_skills(self) -> None:
        """Scan skills directory for existing skills."""
        self.skills.clear()
        if not os.path.exists(self.skills_dir):
            return

        for name in os.listdir(self.skills_dir):
            skill_path = os.path.join(self.skills_dir, name)
            if os.path.isdir(skill_path):
                skill_file = os.path.join(skill_path, "SKILL.md")
                if os.path.exists(skill_file):
                    self.skills[name] = datetime.fromtimestamp(
                        os.path.getmtime(skill_file)
                    )

    def add_skill(self, name: str, content: str) -> None:
        """
        Add or update a skill.

        Args:
            name: Skill name (will be used as directory name)
            content: Skill markdown content (SKILL.md format)
        """
        # Sanitize name to be filesystem-safe
        safe_name = self._sanitize_name(name)

        # Create skill directory
        skill_dir = os.path.join(self.skills_dir, safe_name)
        os.makedirs(skill_dir, exist_ok=True)

        # Write SKILL.md
        skill_file = os.path.join(skill_dir, "SKILL.md")
        with open(skill_file, "w", encoding="utf-8") as f:
            f.write(content)

        # Update tracking
        self.skills[safe_name] = datetime.utcnow()

    def remove_skill(self, name: str) -> bool:
        """
        Remove a skill.

        Args:
            name: Skill name to remove

        Returns:
            True if skill existed and was removed
        """
        safe_name = self._sanitize_name(name)

        if safe_name not in self.skills:
            return False

        skill_dir = os.path.join(self.skills_dir, safe_name)
        if os.path.exists(skill_dir):
            shutil.rmtree(skill_dir, ignore_errors=True)

        del self.skills[safe_name]
        return True

    def list_skills(self) -> List[SkillInfo]:
        """
        List all available skills.

        Returns:
            List of SkillInfo objects
        """
        result = []
        for name, added_at in self.skills.items():
            skill_file = os.path.join(self.skills_dir, name, "SKILL.md")
            description = self._extract_description(skill_file)
            result.append(
                SkillInfo(name=name, description=description, added_at=added_at)
            )
        return result

    def get_skill_content(self, name: str) -> Optional[str]:
        """
        Get the content of a skill.

        Args:
            name: Skill name

        Returns:
            Skill content or None if not found
        """
        safe_name = self._sanitize_name(name)
        skill_file = os.path.join(self.skills_dir, safe_name, "SKILL.md")

        if os.path.exists(skill_file):
            with open(skill_file, "r", encoding="utf-8") as f:
                return f.read()
        return None

    def skill_exists(self, name: str) -> bool:
        """Check if a skill exists."""
        return self._sanitize_name(name) in self.skills

    def _extract_description(self, skill_file: str) -> Optional[str]:
        """
        Extract description from skill file.

        Looks for:
        1. First line starting with '>' (blockquote - used for description)
        2. First heading (# Title)
        """
        if not os.path.exists(skill_file):
            return None

        try:
            with open(skill_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    # Look for blockquote description
                    if line.startswith(">"):
                        return line.lstrip("> ").strip()
                    # Fall back to first heading
                    if line.startswith("#"):
                        return line.lstrip("#").strip()
        except Exception:
            pass

        return None

    @staticmethod
    def _sanitize_name(name: str) -> str:
        """
        Sanitize skill name for filesystem safety.

        Args:
            name: Original skill name

        Returns:
            Filesystem-safe name
        """
        # Replace problematic characters
        safe = name.lower()
        safe = safe.replace(" ", "-")
        safe = safe.replace("/", "-")
        safe = safe.replace("\\", "-")
        safe = safe.replace(".", "-")

        # Remove any remaining non-alphanumeric characters except hyphens
        safe = "".join(c for c in safe if c.isalnum() or c == "-")

        # Collapse multiple hyphens
        while "--" in safe:
            safe = safe.replace("--", "-")

        return safe.strip("-")
