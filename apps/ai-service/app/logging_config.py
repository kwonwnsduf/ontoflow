import logging.config


def configure_logging(level: str) -> None:
    logging.config.dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "json": {
                    "class": "pythonjsonlogger.json.JsonFormatter",
                    "format": "%(asctime)s %(levelname)s %(name)s %(message)s %(trace_id)s",
                    "rename_fields": {"asctime": "timestamp", "levelname": "level"},
                    "static_fields": {"service": "ai-service"},
                }
            },
            "handlers": {
                "default": {"class": "logging.StreamHandler", "formatter": "json"}
            },
            "root": {"handlers": ["default"], "level": level},
        }
    )

