# ARCHITECTURE — referência Android

## Stack sugerida, sujeita a POC
- Android Kotlin; Jetpack Compose para UI; Navigation Compose, ViewModel, Coroutines/Flow.
- Room/SQLite para estado transacional; arquivos originais em armazenamento privado do app (cópia com Storage Access Framework), backup/export explícitos.
- WorkManager para trabalhos persistentes e reconciliação de fila; foreground service/Worker foreground quando realmente necessário e permitido, com notificação e tratamento de limite/timeout. Distinguir persistir trabalho de garantir execução contínua.
- Provedores via adaptadores por API oficial; configuração de esforço guiada por capacidades reais; segredos em Android Keystore com armazenamento criptografado apropriado.
- Parser EPUB nativo; adaptadores MOBI/AZW3 drm-free com prova de viabilidade e testes reais; representação intermediária `BookDocument` independente do formato.

## Camadas
UI Compose -> ViewModel/use cases -> JobCoordinator -> (FormatAdapter + ReferenceMemory + ProviderAdapter + CheckpointStore + ExportService)

## Máquina de estados
`IMPORTED → READY → RUNNING → PAUSE_REQUESTED → PAUSED → RUNNING → COMPLETED → EXPORTED`
Estados laterais: `WAITING_NETWORK`, `WAITING_POWER`, `WAITING_QUOTA`, `FAILED_RETRYABLE`, `FAILED_PERMANENT`, `CANCELLED`, `REVIEW_REQUIRED`. Toda transição persistida.

## Regra atômica de checkpoint
1. Normalizar documento preservando ordem de capítulos/blocos e `source_hash`.
2. Criar bloco `PENDING` com IDs estáveis `(book_id, chapter_id, chunk_index, text_hash)`.
3. Adquirir lease sobre 1 bloco, enviar prompt com glossário versão X e contexto limitado.
4. Receber saída completa, validar estrutura, referência terminológica e limites de tamanho.
5. Numa transação SQLite gravar saída, hash, custos/tokens medidos, modelo, esforço efetivo, `COMPLETED`, timestamp e `glossary_revision`.
6. Só depois emitir progresso e avançar. Resposta em trânsito sem commit pode ser reexecutada após queda.
7. Ao pausar: não iniciar novos blocos; terminar/cancelar com segurança o atual, persistir PAUSED. Ao cancelar: confirmação e escolha explícita de manter arquivos.

## Idempotência e custos
Idempotência local impede duplicação no EPUB. Não garante eliminação de cobrança dupla se o processo cair após a API responder e antes do commit. Registrar request IDs quando disponíveis; retriar com backoff exponencial e jitter; preservar hash de chunk e variante de prompt; teto de tentativas e orçamento.

## Background: verdade operacional
Android pode suspender ou matar o processo, bloquear execução em background, limitar Worker longo e FGS. WorkManager garante agendamento e persistência de trabalho, não execução 24h sem interrupções. Service/Worker em primeiro plano exige tipo, permissão e notificações conforme API level; para certos tipos Android 15+ aplica limite de 6h por 24h. Tratar estado 'aguardando permissão/recursos' sem perda de checkpoint. Testar com economia de bateria e app fechado no S25 Ultra.

## Memória de série
Série -> Volumes -> Fontes (original EN, tradução PT, pares) -> Entidades e glossário versionados -> Trechos indexados e aprovados -> Padrão editorial; recuperar no máximo top-k trechos relevantes e acrescentar só entradas relacionadas ao bloco. Evitar spoilers futuros e vazamentos entre séries.

## Importação/DRM
EPUB (ZIP + HTML) passa por validação segura; proteção por DRM impede extração e requer mensagem de impossibilidade. MOBI/AZW3 exigem parser KF8/MOBI6 eficaz ou conversor distribuível legalmente para Android; nunca planejar engenharia em cima de `ebook-convert` desktop executando no celular sem prova. Verificar ZIP bombs, arquivos maliciosos, paths, limites de recursos.

## Exportação
Gerar EPUB traduzido sem sobrescrever original; conservar manifesto, TOC, imagens, CSS e metadados na medida suportada. Validação: reabrir EPUB exportado, número/sequência de capítulos, blocos presentes, links internos, parágrafos, checksum e erros reportados. Edição do usuário nunca pode ser perdida na recompilação.

## Segurança
Chaves só em cofres do Android; API direta do celular pode expor riscos de custo/segurança — usar orçamento e limites. Confirmação de envio remoto de texto e referências para modelo; não logar texto de livros/chaves; arquivos locais nunca excluídos por limpeza automática; recursos da nuvem off por padrão.
