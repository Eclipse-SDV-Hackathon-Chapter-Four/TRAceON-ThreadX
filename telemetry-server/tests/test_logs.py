# SPDX-License-Identifier: MIT
# Copyright (c) 2026 the TRAceON team
# Portions of this file were generated with AI assistance.

"""Unit tests for traceon_server.logs — ISO LogEntry parsing + ring buffer."""
import json

import pytest

from traceon_server.logs import (
    LogStore,
    _normalize_severity,
    context_id_of,
    parse_log,
)


# --- severity normalization -------------------------------------------------

@pytest.mark.parametrize("value,expected", [
    ("DLT_WARN", "DLT_WARN"),            # already DLT -> unchanged
    ("DLT_INFO", "DLT_INFO"),
    ("WARN", "DLT_WARN"),                # legacy bare -> DLT
    ("warning", "DLT_WARN"),             # case-insensitive + WARNING alias
    ("error", "DLT_ERROR"),
    ("VERBOSE", "DLT_VERBOSE"),
    ("", "DLT_INFO"),                    # empty -> default INFO
    (None, "DLT_INFO"),                  # missing -> default INFO
])
def test_normalize_severity(value, expected):
    assert _normalize_severity(value) == expected


def test_normalize_severity_unknown_passthrough():
    # An unknown non-empty value is passed through unchanged (not forced to INFO).
    assert _normalize_severity("CUSTOM") == "CUSTOM"


# --- parse_log --------------------------------------------------------------

def test_parse_log_full_iso_entry():
    payload = json.dumps({
        "timestamp": "2026-10-06T16:51:06Z",
        "context": {"type": "AUTOSAR_DLT", "application_id": "TRAC",
                    "context_id": "SensorTask"},
        "severity": "DLT_WARN",
        "msg": "Humidity sensor returned no data",
    })
    entry = parse_log(payload)
    assert entry["timestamp"] == "2026-10-06T16:51:06Z"
    assert entry["context"]["context_id"] == "SensorTask"
    assert entry["severity"] == "DLT_WARN"
    assert entry["msg"] == "Humidity sensor returned no data"


def test_parse_log_string_context_is_wrapped():
    # Legacy flat string context -> wrapped into an AUTOSAR_DLT object.
    payload = json.dumps({"timestamp": "t", "context": "Main",
                          "severity": "INFO", "msg": "hi"})
    entry = parse_log(payload)
    assert entry["context"] == {"type": "AUTOSAR_DLT", "context_id": "Main"}
    assert entry["severity"] == "DLT_INFO"      # legacy mapped


def test_parse_log_malformed_falls_back_to_raw_msg():
    entry = parse_log("not json at all")
    assert entry["severity"] == "DLT_INFO"
    assert entry["msg"] == "not json at all"
    assert entry["context"] == {"type": "AUTOSAR_DLT", "context_id": ""}


def test_parse_log_non_dict_json_falls_back():
    # Valid JSON but not an object -> fallback path.
    entry = parse_log("[1, 2, 3]")
    assert entry["msg"] == "[1, 2, 3]"
    assert entry["severity"] == "DLT_INFO"


# --- context_id_of ----------------------------------------------------------

def test_context_id_of_object():
    assert context_id_of({"context": {"context_id": "MQTT"}}) == "MQTT"


def test_context_id_of_string_context():
    assert context_id_of({"context": "Main"}) == "Main"


def test_context_id_of_missing():
    assert context_id_of({}) == ""


# --- LogStore ring buffer ---------------------------------------------------

def test_logstore_add_and_entries_order():
    s = LogStore(maxlen=10)
    for i in range(3):
        s.add({"msg": f"m{i}", "severity": "DLT_INFO"})
    msgs = [e["msg"] for e in s.entries()]
    assert msgs == ["m0", "m1", "m2"]      # oldest first


def test_logstore_evicts_oldest_but_count_is_total():
    s = LogStore(maxlen=2)
    for i in range(5):
        s.add({"msg": f"m{i}"})
    msgs = [e["msg"] for e in s.entries()]
    assert msgs == ["m3", "m4"]            # only last 2 retained
    assert s.count() == 5                  # count is cumulative, not buffer size


def test_logstore_snapshot_has_received_at():
    s = LogStore(maxlen=4)
    s.add({"msg": "x"})
    slot = s.snapshot()[0]
    assert "received_at" in slot and isinstance(slot["received_at"], float)
    assert slot["entry"]["msg"] == "x"     # ISO entry nested under 'entry'
