"""FastAPI 路由集成测试（TR-9.1~9.9）。用 TestClient + temp_registry，CRUD 不污染真实文件。"""
import pytest


def test_list_factors(client):
    # TR-9.1
    r = client.get("/python/v1/factors")
    assert r.status_code == 200
    assert r.json()["total"] == 54


def test_get_factor(client):
    # TR-9.2
    r = client.get("/python/v1/factors/MA")
    assert r.status_code == 200
    assert r.json()["data"]["factorKey"] == "MA"


def test_get_factor_not_found(client):
    # TR-9.3
    r = client.get("/python/v1/factors/NOT_EXIST")
    assert r.status_code == 404
    assert r.json()["errorCode"] == "FACTOR_NOT_FOUND"


def test_create_and_duplicate(client):
    # TR-9.4 + AC-16
    payload = {
        "factorKey": "API_NEW", "displayName": "接口新增", "category": "MOMENTUM",
        "source": "AKQUANT", "akquantFunc": "MA", "inputs": ["close"],
        "params": [{"name": "timeperiod", "displayName": "周期", "type": "INT",
                    "defaultValue": 5, "min": 1, "max": 500, "step": 1}],
    }
    r = client.post("/python/v1/factors", json=payload)
    assert r.status_code == 201
    # 随后可查到
    assert client.get("/python/v1/factors/API_NEW").status_code == 200
    # 重复新增 → 400 FACTOR_ALREADY_EXISTS
    dup = client.post("/python/v1/factors", json=payload)
    assert dup.status_code == 400
    assert dup.json()["errorCode"] == "FACTOR_ALREADY_EXISTS"


def test_update_factor(client):
    # TR-9.5
    r = client.put("/python/v1/factors/MA", json={"displayName": "MA 改名"})
    assert r.status_code == 200
    assert client.get("/python/v1/factors/MA").json()["data"]["displayName"] == "MA 改名"


def test_delete_factor(client):
    # TR-9.6
    client.post("/python/v1/factors", json={
        "factorKey": "API_DEL", "displayName": "x", "category": "PRICE",
        "source": "RAW", "inputs": ["close"], "params": [],
    })
    r = client.delete("/python/v1/factors/API_DEL")
    assert r.status_code == 200
    assert client.get("/python/v1/factors/API_DEL").status_code == 404


def test_compute_ok(client, ohlcv_data):
    # TR-9.7
    r = client.post("/python/v1/factors/compute", json={
        "data": ohlcv_data, "factors": [{"factorKey": "MA", "params": {"timeperiod": 5}}]
    })
    assert r.status_code == 200
    assert "MA" in r.json()["data"]
    assert len(r.json()["data"]["MA"]) == len(ohlcv_data)


def test_compute_unknown(client, ohlcv_data):
    # TR-9.8
    r = client.post("/python/v1/factors/compute", json={
        "data": ohlcv_data, "factors": [{"factorKey": "NOPE"}]
    })
    assert r.status_code == 400
    assert r.json()["errorCode"] == "UNKNOWN_FACTOR"


def test_compute_not_computable(client, ohlcv_data):
    r = client.post("/python/v1/factors/compute", json={
        "data": ohlcv_data, "factors": [{"factorKey": "PE_TTM"}]
    })
    assert r.status_code == 400
    assert r.json()["errorCode"] == "FACTOR_NOT_COMPUTABLE"


def test_batch_compute(client, ohlcv_data):
    r = client.post("/python/v1/factors/batch-compute", json={
        "data": {"600519": ohlcv_data, "000001": ohlcv_data},
        "factors": [{"factorKey": "CLOSE"}, {"factorKey": "RSI", "params": {"timeperiod": 14}}],
    })
    assert r.status_code == 200
    data = r.json()["data"]
    assert set(data) == {"600519", "000001"}
    assert set(data["600519"]) == {"CLOSE", "RSI"}
