"""
Synthetic beneficiary data seed for local SRSE development.

Generates Jan-Aadhaar-shaped rows and writes them as an Iceberg table via Presto.
Schema matches FieldResolver column names + age_band for ExecutionService.breakdown().
"""
import os
import random
import sys

import prestodb

PRESTO_URL = os.environ.get("PRESTO_URL", "jdbc:presto://presto:8080/iceberg/srse")
ROWS = int(os.environ.get("ROWS", "200000"))
BATCH_SIZE = 1000

DISTRICTS = ["Jaipur", "Jodhpur", "Udaipur", "Kota", "Ajmer", "Bikaner", "Alwar"]
COMMUNITIES = ["GENERAL", "SAHARIYA", "KATHODI", "KHAIRWA"]
RATION = ["NONE", "BPL", "ANTYODAYA"]
CENSUS_CATEGORIES = ["APL", "BPL", "EWS"]
TSP_VALUES = ["TSP", "NON_TSP"]
MARITAL = ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED"]
RELATIONSHIP_TO_HOF = [
    "SELF", "SON", "DAUGHTER", "FATHER", "MOTHER", "SPOUSE",
    "GRANDSON", "GRANDDAUGHTER", "BROTHER", "SISTER", "OTHER",
]
FATHER_NAMES = ["Ramesh", "Suresh", "Mahesh", "Rajesh", "Dinesh", "Ganesh", "Naresh", "Mukesh"]
MOTHER_NAMES = ["Sunita", "Kavita", "Anita", "Rekha", "Meena", "Sita", "Geeta", "Lata"]
CLASS_PASSED = ["NURSERY", "KG"] + [str(n) for n in range(1, 13)] + ["GRADUATE"]


def _typo(name: str) -> str:
    """Injects a single-character edit — simulates the cross-source spelling
    variation (birth/marriage registration, ration card, etc.) that fuzzy
    matching is meant to be tolerant of."""
    if len(name) < 3:
        return name
    i = random.randint(1, len(name) - 2)
    kind = random.choice(["swap", "drop", "dup"])
    if kind == "swap":
        chars = list(name)
        chars[i], chars[i + 1] = chars[i + 1], chars[i]
        return "".join(chars)
    if kind == "drop":
        return name[:i] + name[i + 1:]
    return name[:i] + name[i] + name[i:]


def _name_with_variation(pool):
    name = random.choice(pool)
    return _typo(name) if random.random() < 0.18 else name

# Target Iceberg table matching the initial field catalogue (design doc §7.1).
CREATE_TABLE = """
CREATE TABLE IF NOT EXISTS beneficiary (
    id                   BIGINT,
    age_years            INTEGER,
    gender               VARCHAR,
    district             VARCHAR,
    annual_income_total  DECIMAL(12,2),
    marital_status       VARCHAR,
    is_domicile_holder   BOOLEAN,
    ration_card_category VARCHAR,
    census_category      VARCHAR,
    community            VARCHAR,
    disability_pct       INTEGER,
    tsp_classification   VARCHAR,
    class_passed         VARCHAR,
    is_girl_child_of_hof  BOOLEAN,
    has_vehicle          BOOLEAN,
    land_holding_sqyd    DECIMAL(10,2),
    relationship_to_hof  VARCHAR,
    father_name          VARCHAR,
    mother_name          VARCHAR,
    annual_income_fy2627 DECIMAL(12,2),
    annual_income_fy2526 DECIMAL(12,2),
    annual_income_fy2425 DECIMAL(12,2),
    annual_income_fy2324 DECIMAL(12,2),
    annual_income_fy2223 DECIMAL(12,2),
    annual_income_fy2122 DECIMAL(12,2),
    annual_income_fy2021 DECIMAL(12,2),
    annual_income_fy1920 DECIMAL(12,2),
    annual_income_fy1819 DECIMAL(12,2),
    annual_income_fy1718 DECIMAL(12,2),
    age_band             VARCHAR,
    last_refreshed_at    VARCHAR
)
"""

FY_INCOME_COLUMNS = [
    "annual_income_fy2627",
    "annual_income_fy2526",
    "annual_income_fy2425",
    "annual_income_fy2324",
    "annual_income_fy2223",
    "annual_income_fy2122",
    "annual_income_fy2021",
    "annual_income_fy1920",
    "annual_income_fy1819",
    "annual_income_fy1718",
]

COLUMNS = [
    "id",
    "age_years",
    "gender",
    "district",
    "annual_income_total",
    "marital_status",
    "is_domicile_holder",
    "ration_card_category",
    "census_category",
    "community",
    "disability_pct",
    "tsp_classification",
    "class_passed",
    "is_girl_child_of_hof",
    "has_vehicle",
    "land_holding_sqyd",
    "relationship_to_hof",
    "father_name",
    "mother_name",
    *FY_INCOME_COLUMNS,
    "age_band",
    "last_refreshed_at",
]


def _fy_income(base_income: float) -> float:
    """Independent year-over-year variance around the flat income figure —
    so FY-scoped ranges actually produce different counts, not a copy of
    annual_income_total five times over."""
    return round(base_income * random.uniform(0.7, 1.3), 2)


def make_row(i: int):
    age = random.randint(0, 90)
    gender = random.choice(["MALE", "FEMALE"])
    annual_income_total = round(random.uniform(0, 300000), 2)
    row = {
        "id": i,
        "age_years": age,
        "gender": gender,
        "district": random.choice(DISTRICTS),
        "annual_income_total": annual_income_total,
        "marital_status": random.choice(MARITAL),
        "is_domicile_holder": random.random() < 0.9,
        "ration_card_category": random.choice(RATION),
        "census_category": random.choice(CENSUS_CATEGORIES),
        "community": random.choice(COMMUNITIES),
        "disability_pct": random.choice([0, 0, 0, 40, 60, 80]),
        "tsp_classification": random.choice(TSP_VALUES),
        "class_passed": random.choice(CLASS_PASSED),
        "is_girl_child_of_hof": gender == "FEMALE" and age < 18 and random.random() < 0.3,
        "has_vehicle": random.random() < 0.35,
        "land_holding_sqyd": round(random.uniform(0, 500), 2),
        "relationship_to_hof": random.choice(RELATIONSHIP_TO_HOF),
        "father_name": _name_with_variation(FATHER_NAMES),
        "mother_name": _name_with_variation(MOTHER_NAMES),
        "age_band": _band(age),
        "last_refreshed_at": "2026-01-01",
    }
    for col in FY_INCOME_COLUMNS:
        row[col] = _fy_income(annual_income_total)
    return row


def _band(age: int) -> str:
    if age < 18: return "0-17"
    if age < 35: return "18-34"
    if age < 55: return "35-54"
    return "55+"


def parse_presto_url(url: str):
    """Parse jdbc:presto://host:port/catalog/schema into components."""
    prefix = "jdbc:presto://"
    if not url.startswith(prefix):
        raise ValueError(f"PRESTO_URL must start with {prefix!r}, got: {url!r}")
    rest = url[len(prefix):]
    # host:port/catalog/schema
    slash = rest.find("/")
    if slash < 0:
        raise ValueError(f"PRESTO_URL missing catalog/schema path: {url!r}")
    host_port = rest[:slash]
    path = rest[slash + 1:]
    parts = path.split("/")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        raise ValueError(f"PRESTO_URL must be jdbc:presto://host:port/catalog/schema, got: {url!r}")
    catalog, schema = parts
    if ":" in host_port:
        host, port_str = host_port.rsplit(":", 1)
        port = int(port_str)
    else:
        host = host_port
        port = 8080
    if not host:
        raise ValueError(f"PRESTO_URL missing host: {url!r}")
    return host, port, catalog, schema


def _sql_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return f"{value:.2f}"
    # VARCHAR — escape single quotes
    s = str(value).replace("'", "''")
    return f"'{s}'"


def _values_clause(row: dict) -> str:
    return "(" + ", ".join(_sql_literal(row[c]) for c in COLUMNS) + ")"


def _execute(cursor, sql: str):
    cursor.execute(sql)
    # Drain results so Presto's Python client actually completes the statement.
    cursor.fetchall()


def main():
    try:
        print(f"[seed] target={PRESTO_URL} rows={ROWS}")
        host, port, catalog, schema = parse_presto_url(PRESTO_URL)
        print(f"[seed] connecting host={host} port={port} catalog={catalog} schema={schema}")

        conn = prestodb.dbapi.connect(
            host=host,
            port=port,
            user=os.environ.get("PRESTO_USER", "seed"),
            catalog=catalog,
            schema=schema,
        )
        cursor = conn.cursor()

        _execute(cursor, f"CREATE SCHEMA IF NOT EXISTS {catalog}.{schema}")
        print(f"[seed] schema {catalog}.{schema} ready")

        _execute(cursor, CREATE_TABLE)
        print("[seed] table beneficiary ready")

        for start in range(0, ROWS, BATCH_SIZE):
            end = min(start + BATCH_SIZE, ROWS)
            rows = [make_row(i) for i in range(start, end)]
            values = ", ".join(_values_clause(r) for r in rows)
            _execute(cursor, f"INSERT INTO beneficiary VALUES {values}")
            print(f"[seed] inserted {end}/{ROWS} rows")

        print(f"[seed] done — {ROWS} rows inserted into {catalog}.{schema}.beneficiary")
        cursor.close()
        conn.close()
    except Exception as e:
        print(f"[seed] ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
