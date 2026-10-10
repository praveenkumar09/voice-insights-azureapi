# Demo data for the admin dashboard

`seed_demo.py` generates realistic demo conversations, agent runs and advice packs as SQL.
Everything it creates is tagged so it can be removed again:

- profile ids `demo-p-NNNN`, run ids `demo-r-NNNN`
- advisor accounts `demo-u-N` with emails ending `@demo.aia.sg` (they cannot sign in)

## Create

```bash
cd docs/demo-data
python3 seed_demo.py seed.sql          # writes seed.sql and cleanup.sql
docker exec -i cobolapi_postgres psql -U admin -d ingestor -v ON_ERROR_STOP=1 -q < seed.sql
```

## Remove

```bash
docker exec -i cobolapi_postgres psql -U admin -d ingestor < cleanup.sql
```

The `ref_*.json` files are real agent outputs used as templates (product excerpts etc.).
Your real data is never touched by either script.
