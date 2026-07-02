from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# engine 项目根目录（config.py 位于根，data/ 在其下）
_ENGINE_ROOT = Path(__file__).resolve().parent


class Settings(BaseSettings):
    # 服务配置
    host: str = "127.0.0.1"
    port: int = 8085

    # 日志配置
    log_level: str = "INFO"

    # 数据配置
    data_history_years: int = 5

    # 因子库定义文件
    # 种子文件（入库）：标准因子的默认定义，缺失/损坏时据此恢复
    factors_seed_file: str = str(_ENGINE_ROOT / "data" / "factors.default.json")
    # 运行时文件（.gitignore）：CRUD 写入此处，engine 启动时加载
    factors_runtime_file: str = str(_ENGINE_ROOT / "data" / "factors.json")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


# 全局配置实例
settings = Settings()
