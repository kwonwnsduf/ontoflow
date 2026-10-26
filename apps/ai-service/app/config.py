from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: Literal["local", "test", "production"] = "local"
    log_level: str = "INFO"
    internal_api_key: str = Field(min_length=32)


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]

