"""Unit tests for traceon_server.envelope and traceon_server.query."""
import re

import pytest

from traceon_server.envelope import event_envelope
from traceon_server import query


# --- EventEnvelope ----------------------------------------------------------

def test_event_envelope_success_shape():
    env = event_envelope({"msg": "hi"})
    assert set(env.keys()) == {"timestamp", "payload", "error"}
    assert env["payload"] == {"msg": "hi"}
    assert env["error"] is None
    # ISO-8601 UTC 'Z' timestamp
    assert re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", env["timestamp"])


def test_event_envelope_with_error():
    env = event_envelope(None, error="boom")
    assert env["error"] == "boom"
    assert env["payload"] is None


# --- clamp_limit ------------------------------------------------------------

@pytest.mark.parametrize("value,expected", [
    (None, query.MAX_LIMIT),   # default
    (0, 1),                    # below min -> 1
    (-5, 1),
    (1, 1),
    (50, 50),
    (999, query.MAX_LIMIT),    # above cap -> MAX_LIMIT
])
def test_clamp_limit(value, expected):
    assert query.clamp_limit(value) == expected


# --- parse_iso8601 ----------------------------------------------------------

def test_parse_iso8601_none_returns_none():
    assert query.parse_iso8601(None) is None


def test_parse_iso8601_accepts_trailing_z():
    # 'Z' must be accepted and treated as UTC.
    ts = query.parse_iso8601("2026-10-06T17:00:00Z")
    assert isinstance(ts, float)


def test_parse_iso8601_naive_treated_as_utc():
    naive = query.parse_iso8601("2026-10-06T17:00:00")
    aware = query.parse_iso8601("2026-10-06T17:00:00Z")
    assert naive == aware     # naive assumed UTC


def test_parse_iso8601_malformed_raises():
    with pytest.raises(ValueError):
        query.parse_iso8601("not-a-date")


# --- in_time_range ----------------------------------------------------------

def test_in_time_range_no_bounds_always_true():
    assert query.in_time_range(100.0, None, None) is True


def test_in_time_range_since_only():
    assert query.in_time_range(100.0, 50.0, None) is True
    assert query.in_time_range(40.0, 50.0, None) is False


def test_in_time_range_until_only():
    assert query.in_time_range(100.0, None, 150.0) is True
    assert query.in_time_range(200.0, None, 150.0) is False


def test_in_time_range_both_bounds_inclusive():
    assert query.in_time_range(50.0, 50.0, 150.0) is True   # lower inclusive
    assert query.in_time_range(150.0, 50.0, 150.0) is True  # upper inclusive
    assert query.in_time_range(49.9, 50.0, 150.0) is False
