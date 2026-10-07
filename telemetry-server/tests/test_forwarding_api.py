# SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
# SPDX-License-Identifier: MIT
# Portions of this file were generated with AI assistance.

"""Module tests for the forwarding control endpoints.

Uses FastAPI's TestClient WITHOUT entering the lifespan context (so MQTT is not
started / no broker connection). The real LogForwarder is used but we never
start a reachable sink — we only exercise the control API and its state machine,
and we stub the background worker so no real HTTP POST happens.
"""
import pytest
from fastapi.testclient import TestClient

from traceon_server.app import app
from traceon_server.forwarder import forwarder


@pytest.fixture
def client(monkeypatch):
    # Neutralize the background worker: start() should flip state without
    # spawning a thread that would try to POST anywhere.
    def fake_start():
        with forwarder._lock:  # noqa: SLF001 - test needs internal state
            if not forwarder._url:
                raise ValueError("No forward URL configured (set TRACEON_LOG_FORWARD_URL)")
            forwarder._enabled = True

    def fake_stop():
        forwarder._enabled = False

    monkeypatch.setattr(forwarder, "start", fake_start)
    monkeypatch.setattr(forwarder, "stop", fake_stop)
    # Ensure a clean starting state for each test.
    forwarder._enabled = False
    forwarder._url = ""
    return TestClient(app)


def test_status_initially_disabled(client):
    r = client.get("/logs/forwarding")
    assert r.status_code == 200
    body = r.json()
    assert body["enabled"] is False
    assert body["url"] is None


def test_start_without_url_returns_400(client):
    r = client.post("/logs/forwarding/start")
    assert r.status_code == 400
    assert "No forward URL" in r.json()["detail"]


def test_start_with_url_body_enables_and_sets_url(client):
    r = client.post("/logs/forwarding/start",
                    json={"url": "http://127.0.0.1:8080/internal/logs"})
    assert r.status_code == 200
    body = r.json()
    assert body["enabled"] is True
    assert body["url"] == "http://127.0.0.1:8080/internal/logs"


def test_stop_disables(client):
    client.post("/logs/forwarding/start",
                json={"url": "http://127.0.0.1:8080/logs"})
    r = client.post("/logs/forwarding/stop")
    assert r.status_code == 200
    assert r.json()["enabled"] is False
