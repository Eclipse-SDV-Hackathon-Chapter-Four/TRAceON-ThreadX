"""Query helpers for the /history endpoints: ISO-8601 since/until parsing and
limit clamping. All time filtering is on the server-side receive time (epoch
seconds), per the agreed design.
"""
from __future__ import annotations

from datetime import datetime, timezone

MAX_LIMIT = 100


def clamp_limit(limit: int | None) -> int:
    """Clamp a requested limit to 1..MAX_LIMIT (default MAX_LIMIT)."""
    if limit is None:
        return MAX_LIMIT
    if limit < 1:
        return 1
    return min(limit, MAX_LIMIT)


def parse_iso8601(value: str | None) -> float | None:
    """Parse an ISO-8601 string to an epoch-seconds float. Returns None if the
    input is None. Raises ValueError on a malformed string."""
    if value is None:
        return None
    text = value.strip()
    # Accept trailing 'Z' (UTC) which fromisoformat historically didn't.
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp()


def in_time_range(received_at: float, since: float | None, until: float | None) -> bool:
    if since is not None and received_at < since:
        return False
    if until is not None and received_at > until:
        return False
    return True
