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
