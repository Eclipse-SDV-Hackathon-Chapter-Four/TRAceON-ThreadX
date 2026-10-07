# SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
# SPDX-License-Identifier: MIT
# Portions of this file were generated with AI assistance.

"""Run the server:  python -m traceon_server"""
from __future__ import annotations

import uvicorn

from .config import settings


def main() -> None:
    uvicorn.run(
        "traceon_server.app:app",
        host=settings.http_host,
        port=settings.http_port,
        log_level="info",
    )


if __name__ == "__main__":
    main()
