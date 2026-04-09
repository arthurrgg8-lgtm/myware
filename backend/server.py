#!/usr/bin/env python3
import logging
from http.server import ThreadingHTTPServer

from config import SETTINGS, validate_settings
from db import ensure_device_token_column, get_db, init_db
from routes import TrackerHandler


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    validate_settings()
    init_db()
    conn = get_db()
    try:
        ensure_device_token_column(conn)
    finally:
        conn.close()
    server = ThreadingHTTPServer((SETTINGS.host, SETTINGS.port), TrackerHandler)
    logging.getLogger("tracker.server").info(
        "Lost phone tracker running at http://%s:%s using db %s",
        SETTINGS.host,
        SETTINGS.port,
        SETTINGS.db_path,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
