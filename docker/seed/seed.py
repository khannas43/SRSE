"""
Synthetic beneficiary data seed for local SRSE development.

BUILD TASK (Sprint 2, design doc §6.3/§7.1): flesh this out to generate
realistic Jan-Aadhaar-shaped rows and write them as an Iceberg table via Presto.
This skeleton establishes the shape and the target schema only.
"""
import os
import random

PRESTO_URL = os.environ.get("PRESTO_URL", "jdbc:presto://presto:8080/iceberg/srse")
ROWS = int(os.environ.get("ROWS", "200000"))

DISTRICTS = ["Jaipur", "Jodhpur", "Udaipur", "Kota", "Ajmer", "Bikaner", "Alwar"]
COMMUNITIES = ["GENERAL", "SAHARIYA", "KATHODI", "KHAIRWA"]
RATION = ["NONE", "BPL", "ANTYODAYA"]
MARITAL = ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED"]

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
    community            VARCHAR,
    disability_pct       INTEGER,
    is_enrolled_in_school BOOLEAN,
    is_girl_child_of_hof  BOOLEAN,
    age_band             VARCHAR,
    last_refreshed_at    VARCHAR
)
"""

def make_row(i: int):
    age = random.randint(0, 90)
    gender = random.choice(["MALE", "FEMALE"])
    return {
        "id": i,
        "age_years": age,
        "gender": gender,
        "district": random.choice(DISTRICTS),
        "annual_income_total": round(random.uniform(0, 300000), 2),
        "marital_status": random.choice(MARITAL),
        "is_domicile_holder": random.random() < 0.9,
        "ration_card_category": random.choice(RATION),
        "community": random.choice(COMMUNITIES),
        "disability_pct": random.choice([0, 0, 0, 40, 60, 80]),
        "is_enrolled_in_school": age < 18 and random.random() < 0.8,
        "is_girl_child_of_hof": gender == "FEMALE" and age < 18 and random.random() < 0.3,
        "age_band": _band(age),
        "last_refreshed_at": "2026-01-01",
    }

def _band(age: int) -> str:
    if age < 18: return "0-17"
    if age < 35: return "18-34"
    if age < 55: return "35-54"
    return "55+"

def main():
    print(f"[seed] target={PRESTO_URL} rows={ROWS}")
    print("[seed] TODO: connect via presto client, run CREATE_TABLE, batch-insert make_row().")
    print("[seed] Skeleton only — implement in Sprint 2.")
    # Example of the shape (not executed):
    _ = [make_row(i) for i in range(3)]

if __name__ == "__main__":
    main()
