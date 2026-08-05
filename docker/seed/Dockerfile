# Synthetic-data seed job (one-shot).
#
# BUILD TASK (design doc §6.3 / §7.1): generate ~200k Jan-Aadhaar-shaped
# synthetic beneficiary rows and write them as an Iceberg table `beneficiary`
# into the local Presto/MinIO stack, so the compiler + execution service can be
# tested end-to-end offline. Fields must match the initial field catalogue
# (age_years, gender, district, annual_income_total, marital_status,
# is_domicile_holder, ration_card_category, community, disability_pct,
# is_enrolled_in_school, is_girl_child_of_hof) with realistic distributions
# and the authentic scheme thresholds represented (Ekal Naari, Vridhjan, Palanhaar).
#
# Implementation approach: a small Python script using the Presto client to
# CREATE TABLE ... and batch INSERT synthetic rows. Placeholder below.

FROM python:3.12-slim
WORKDIR /seed
COPY seed.py .
RUN pip install --no-cache-dir presto-python-client
ENTRYPOINT ["python", "seed.py"]
