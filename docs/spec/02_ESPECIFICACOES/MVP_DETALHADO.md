# MVP DETALHADO — Bookconverter Android
**Escopo proposto | 15/11/2026 | Sem implementação neste pacote**

## Resultado que o MVP precisa demonstrar
Um livro EPUB, MOBI ou AZW3 **sem DRM** é importado no Android, dividido em capítulos/blocos e traduzido de inglês para português brasileiro usando OpenAI, Anthropic/Claude ou Google/Gemini via API autorizada. O usuário pode usar uma memória aprovada de série, pausar e retomar o trabalho, acompanhar estado mesmo com interrupções, revisar a tradução e exportar um EPUB verificado. O checkpoint de um bloco só aparece como concluído após gravação local confirmada.

## EPIC 01 — Biblioteca e importação
**US-001:** Como leitor, quero importar EPUB/MOBI/AZW3 pelo seletor Android, para não depender de conversão externa manual.
**US-002:** Como leitor, quero ver o número de capítulos, formato, tamanho e aviso DRM, para confiar no arquivo antes de traduzir.
**Regras:** detectar por assinatura e conteúdo, não só extensão; manter uma cópia local; EPUB com ZIP/OPF/nav; formatos Kindle sem DRM exigem prova em aparelho; arquivo protegido é recusado sem bypass.
**Aceite:** AC01–AC04. Não criar job se extração/índice falharem de maneira crítica.

## EPIC 02 — Escolher IA, modelo e esforço
**US-003:** Escolher fornecedor (OpenAI, Claude ou Gemini) e um modelo efetivamente permitido.
**US-004:** Ajustar esforço de raciocínio, quando o modelo permitir, ou ver explicação de indisponibilidade.
**US-005:** Testar uma amostra e ver previsão de uso/custo aproximado antes de traduzir todo o livro.
**Regras:** adapters com `capabilities`, `translateChunk`, `estimate`, `normalizeError`. Evitar strings fixas de modelos como autoridade; atualizar catálogo/capabilities de forma controlada.
**Aceite:** tradução real com os três adaptadores; chave inválida ou quota não apagam progresso.

## EPIC 03 — Fila, segundo plano e checkpoints
**US-006:** Pausar a tradução e voltar depois, inclusive após reiniciar o aparelho.
**US-007:** Controlar a fila de mais de um livro, com execução serial inicialmente.
**US-008:** Receber notificação e status coerente quando Android limitar execução ou rede cair.
**Regras:** cada bloco tem um hash, estado, sequência, versão de prompt/glossário e saída validada; confirmação única via transação Room; WorkManager reconciliando jobs; FGS somente quando elegível; retry/backoff com limite; orçamento e consentimento.
**Aceite:** AC07–AC11 e 0 perda de blocos com `COMPLETED` em testes físicos de caos.

## EPIC 04 — Série e memória editorial
**US-009:** Criar uma série e associar livros anteriores como original EN, tradução PT ou par EN↔PT.
**US-010:** Aprovar termos sugeridos, manter nomes, lugares, poderes e personalidade de narração consistentes.
**US-011:** Revisar por que uma referência foi usada e alterar tradução fixa sem sobregravar silenciosamente trabalhos concluídos.
**Regras:** memória por série isolada; glossário versionado com proveniência; dados de referência retidos. No MVP não é necessário RAG vetorial complexo: consulta simples com glossário e trechos relevantes pode cumprir o objetivo se passar testes.
**Aceite:** 20 termos sintéticos aprovados aplicados, conflitos com alerta. Correspondência ambígua não vira fato.

## EPIC 05 — Revisão e exportação
**US-012:** Comparar original e português, corrigir trecho e retornar à lista de capítulos.
**US-013:** Exportar EPUB pt-BR para leitor externo, preservando índice e organização.
**US-014:** Manter o original e a memória intactos; compartilhar a exportação com consentimento.
**Regras:** usuário controla termos obrigatórios; QA estrutural. DOCX/PDF como novas fases, não prometer ambos antes de testes reais.
**Aceite:** EPUB abre em dois leitores independentes e tem capítulos sem omissões/duplicações silenciosas.

## Telas do MVP
Boas-vindas, Biblioteca, Importar, Verificar, Nova Tradução, Regras, Séries, Memória, Glossário, Fila, Progresso, Pausado/Retomar, Erro, Revisão, Pendências, Exportar, Concluídos, Ajustes, Provedores e Backup. Telas mais aprofundadas (Personagens e Pares alinhados) devem existir como consulta/editor minimalista no MVP ou podem entrar após o primeiro gate de memória, desde que a memória seja funcional.

## Não entra no MVP
Remoção de DRM, leitor de e-books completo, leitura offline por LLM local, escaneamento/OCR de PDF, exportação DOCX/PDF de alta fidelidade, download de livros, pagamentos, sincronização multi-dispositivo, animações decorativas pesadas e personalização extensa de temas.

## Critério final de conclusão
Não há lançamento só com screenshots ou testes sintéticos. O gate exige evidência no S25 Ultra, políticas de custo/privacidade acessíveis, logs sem texto sensível, compatibilidade dos três formatos, recuperação de falhas, consistência de termos, e EPUB final validado. Gate não executado = NOT_RUN.
