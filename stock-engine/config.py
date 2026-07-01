from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # 服务配置
    host: str = "127.0.0.1"
    port: int = 8085
    
    # 日志配置
    log_level: str = "INFO"
    
    # 数据配置
    data_history_years: int = 5

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


# 全局配置实例
settings = Settings()
