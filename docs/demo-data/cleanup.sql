BEGIN;
DELETE FROM voice_insights.advice_packs WHERE run_id LIKE 'demo-r-%';
DELETE FROM voice_insights.recommendation_agent_results WHERE run_id LIKE 'demo-r-%';
DELETE FROM voice_insights.recommendation_runs WHERE run_id LIKE 'demo-r-%';
DELETE FROM voice_insights.customer_profiles WHERE id LIKE 'demo-p-%';
DELETE FROM voice_insights.users WHERE id LIKE 'demo-u-%' AND email LIKE '%@demo.aia.sg';
COMMIT;
