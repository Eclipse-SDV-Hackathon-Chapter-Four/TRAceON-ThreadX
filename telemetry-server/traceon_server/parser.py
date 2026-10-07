# SPDX-License-Identifier: MIT
# Copyright (c) 2026 the TRAceON team
# Portions of this file were generated with AI assistance.

"""Parse the AZ3166's plain-text telemetry payload into structured fields.

The firmware publishes (see app/mqtt/telemetry.c -> get_sensor_data_buffer):

    Pressure: 1013.25
    Temperature: 23.40
    Humidity: 41.20
    Acceleration: 1.20, -0.30, 980.10
    Magnetic: 120.00, -45.00, 310.00

We parse this leniently (tolerate missing/extra lines) into a dict of
canonical, unit-suffixed field names suitable for JSON output.
"""
from __future__ import annotations

from typing import Any

# Maps the human label in the payload to (canonical_field_name, is_vector).
_SCALAR_FIELDS = {
    "Pressure": "pressure_hPa",
    "Temperature": "temperature_degC",
    "Humidity": "humidity_perc",
}
_VECTOR_FIELDS = {
    "Acceleration": "acceleration_mg",
    "Magnetic": "magnetic_mG",
}


def parse_payload(payload: str) -> dict[str, Any]:
    """Parse the plain-text telemetry into a dict. Unknown lines are ignored.

    Returns a dict with any of: pressure_hPa, temperature_degC, humidity_perc
    (floats) and acceleration_mg, magnetic_mG (3-element float lists).
    """
    result: dict[str, Any] = {}

    for raw_line in payload.splitlines():
        line = raw_line.strip()
        if not line or ":" not in line:
            continue
        label, _, value = line.partition(":")
        label = label.strip()
        value = value.strip()

        if label in _SCALAR_FIELDS:
            parsed = _to_float(value)
            if parsed is not None:
                result[_SCALAR_FIELDS[label]] = parsed
        elif label in _VECTOR_FIELDS:
            parts = [_to_float(p) for p in value.split(",")]
            vec = [p for p in parts if p is not None]
            if vec:
                result[_VECTOR_FIELDS[label]] = vec

    return result


def _to_float(text: str) -> float | None:
    try:
        return float(text.strip())
    except (ValueError, AttributeError):
        return None
