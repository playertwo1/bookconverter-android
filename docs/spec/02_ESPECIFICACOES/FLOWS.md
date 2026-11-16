# Fluxos principais de interação — estados e regras

## Fluxo A — livro avulso
Biblioteca → Importar → Validar extensão/conteúdo/DRM → Prévia de capítulos → Provedor / modelo / esforço disponível → Regras de tradução → Prévia/custo estimado → Iniciar → Fila/Progresso → Revisão → Exportar EPUB → Livro concluído.

## Fluxo B — série
Séries → Criar série → Importar volume anterior (EN, PT ou par) → Analisar por etapas com custo estimado e consentimento → Revisar glossário / personagens / estilo → Marcar fontes confiáveis → Importar volume a traduzir → Ativar memória → Iniciar → Auditoria de termos → Exportar.

## Fluxo C — pausa e retomada
Durante bloco N, usuário solicita pausa. Serviço entra PAUSE_REQUESTED; se uma requisição está concluindo, grava antes de parar; se cancelada no meio, deixa N PENDING. Exibir 'Pausado · confirmado até N-1'. Botão Retomar só cria um processo ativo por job; revalida hashes e limites e continua.

## Fluxo D — erros
- Sem internet → WAITING_NETWORK, backoff, notificação não alarmista.
- Credencial inválida → FAILED_RETRYABLE até correção, sem nova chamada automática repetida.
- HTTP 429 → WAITING_QUOTA com retry_after se houver, limite de tentativas, nenhum loop agressivo.
- Quota de custo atingida → WAITING_BUDGET, ação explícita para continuar.
- Modelo descontinuado → PAUSED com proposta de substituição e alerta de mudança de estilo.
- Force-stop usuário → sistema pode bloquear jobs até reabertura; ao reabrir, recuperar estado sem perda.
- Arquivo DRM → FAILED_PERMANENT com explicação e retorno à biblioteca.

## Fluxo E — comparação e correção
Capítulo selecionado → ver original e tradução → selecionar termo (marca-texto âmbar) → aplicar correção local → 'Adicionar ao glossário' opcional com aprovação → revalidar apenas trechos associados → preservar história de revisão.

## Fluxo F — exportação
Só após montagem validada; usuário escolhe destino via Storage Access Framework. EPUB como saída padrão. Não alterar/sobrescrever fonte. Guardar histórico e SHA-256 da exportação; usuário pode compartilhar por intent Android.
