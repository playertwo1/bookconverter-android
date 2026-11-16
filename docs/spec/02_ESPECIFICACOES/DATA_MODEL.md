# Modelo de dados (proposta Room/SQLite)

## Entidades mínimas
- `Book(id, title, author, source_uri, private_copy_path, original_format, language, file_sha256, drm_status, import_status, created_at)`
- `Series(id, name, default_style_id, created_at)`
- `SeriesVolume(series_id, book_id, volume_number, role=TO_TRANSLATE|REFERENCE_ORIGINAL|REFERENCE_TRANSLATION, paired_with_book_id?)`
- `SeriesGlossaryTerm(id, series_id, source_term, target_term, scope, is_fixed, status=PROPOSED|APPROVED|REJECTED, source_citations, version)`
- `StyleProfile(id, series_id, tone, narrator, register, rules_json, revision)`
- `ReferencePassage(id, series_id, volume_id, source_chunk_id, target_chunk_id?, source_text, target_text?, verified, index_version)`
- `TranslationJob(id, book_id, series_id?, target_language, provider, model_id, effort_requested, effort_effective, prompt_version, glossary_revision, status, block_total, block_done, started_at, updated_at, byte_budget, token_budget, currency_budget)`
- `TranslationChunk(id, job_id, chapter_index, order_index, source_hash, source_text_path, output_text_path?, output_hash?, state, model_id, actual_effort, attempt_count, response_request_id?, output_quality_flags_json, cost_usd?, updated_at)`
- `JobEvent(id, job_id, event_type, safe_summary, created_at)`
- `ExportRecord(id, job_id, target_format, output_uri, checksum, validation_status, created_at)`

## Constraints
- `UNIQUE(job_id, chapter_index, order_index)`; estado de um bloco confirmado só muda por ação de retradução explícita com versão.
- `TranslationJob.block_done` derivado por SELECT COUNT dos blocos COMPLETED; não manipular o contador isoladamente.
- `source_hash`, `glossary_revision` e `prompt_version` registrados para reproduzibilidade.
- `ReferencePassage` e originais não são eliminados após exportação.
- Armazenamento privado e migrações com teste; não guardar chave de API em SQLite puro.

## Exemplo simplificado de um bloco
```json
{
  "job_id":"demo-job",
  "chapter_index":18,
  "order_index":175,
  "source_hash":"sha256:...",
  "provider":"anthropic",
  "model_id":"user-selected-model-id",
  "effort_requested":"high",
  "effort_effective":"supported-mapped-value",
  "glossary_revision":7,
  "status":"COMPLETED",
  "output_hash":"sha256:...",
  "attempt_count":1
}
```
Dados acima são ilustrativos, não ID de modelo oficial.
