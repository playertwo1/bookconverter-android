# ROADMAP — Bookconverter Android (proposto)
**Data:** 15/11/2026 | Não é evidência de implementação | **P0 = bloqueador**, P1 = MVP, P2 = melhoria

## Regras de execução
1. Implementação somente em projeto Android separado; não alterar o fork Windows sem necessidade explicitamente aprovada.
2. Fase só vira DONE com testes, artefatos e validação observável no Galaxy S25 Ultra.
3. POC de MOBI/AZW3 DRM-free vem ANTES de prometer suporte total. Se biblioteca/teste falhar, não declarar formato suportado.
4. Imagens e textos são referência visual e de produto; screenshots não são evidência funcional.

| Fase | Entrega | Dependência | Gate de saída |
|---|---|---|---|
| F0 — Brief e prova técnica | Contratos de formato, Android, licenças, provedor, custo e UX | — | 3 formatos lidos com arquivos reais e casos inválidos; decisão de parser documentada |
| F1 — Fundação Android | Compose, navegação, tokens, SAF, Room, testes, biblioteca | F0 | biblioteca estável, importação/cópia e migração de DB testadas |
| F2 — Importação e normalização | EPUB/MOBI/AZW3 sem DRM, TOC, metadados, blocos | F1 | capítulos/índice/estrutura válidos em fixtures; DRM rejeitado claramente |
| F3 — Motor IA configurável | OpenAI, Claude, Gemini, catálogo de modelos e esforço compatível | F2 | conexão real e 3 traduções testes, custo/limites, sem exposição de chave |
| F4 — Fila resiliente P0 | Checkpoints, pausar/retomar, background, notificações, retry | F3 | reboot/crash/offline/pausa sem perda de bloco confirmado |
| F5 — Memória de séries MVP | Séries, fontes EN/PT, glossário aprovado, perfil editorial | F2+F3 | glossário presente no prompt e QA de consistência; nenhuma fonte apagada |
| F6 — Revisão e exportação | Comparador, revisão simples, EPUB válido, compartilhamento | F4+F5 | saída EPUB passa validação estrutural e leitura em 2 leitores |
| F7 — Fechamento MVP | Testes ponta a ponta, acessibilidade, custo, segurança e backup manual | F0–F6 | checklist P0 100% PASS em celular real, 0 blocker |
| F8 — Pós-MVP | RAG semântico completo, DOCX/PDF, leitor, backup/sync, Batch APIs | F7 | gates específicos por recurso |

## Subfases por trilha (execução recomendada)
- F0.1 Inventariar upstream e separar licenças/arquitetura; F0.2 validar formatos (MOBI6/KF8/AZW3 e casos edge); F0.3 spikes background Android 15–17; F0.4 matriz de APIs/esforço real; F0.5 validar UI acessível.
- F1.1 App skeleton; F1.2 tema/rotas; F1.3 SAF e storage; F1.4 DB/DAOs; F1.5 testes; F1.6 import UI.
- F2.1 detector mime/header; F2.2 parsers; F2.3 representação BookDocument; F2.4 chunker; F2.5 ZIP/XML safety; F2.6 reports de compatibilidade.
- F3.1 interface ProviderAdapter; F3.2 OpenAI; F3.3 Anthropic; F3.4 Gemini; F3.5 capabilities e erro; F3.6 estimativa de custo e orçamento; F3.7 prévia.
- F4.1 estados e lease; F4.2 checkpoint atomic; F4.3 pause/resume; F4.4 WorkManager; F4.5 notificações; F4.6 rede/quota/budget; F4.7 reboot e caos; F4.8 reconcile de estado.
- F5.1 entidades de série; F5.2 importar fontes; F5.3 extrair glossário; F5.4 aprovação; F5.5 perfil de estilo; F5.6 resolver contexto; F5.7 versionar alterações; F5.8 QA de série.
- F6.1 viewer comparativo; F6.2 editor por bloco; F6.3 auditoria de omissions; F6.4 export EPUB; F6.5 validação de leitura; F6.6 share/export location.
- F7.1 e2e livro; F7.2 S25 background; F7.3 TalkBack; F7.4 performance e bateria; F7.5 proteção de credenciais; F7.6 release candidato.

## Dependências que NÃO podem ser puladas
MOBI/AZW3 não são “só mais duas extensões”. Prever parser adequado e validar antes do MVP. GPT/Claude/Gemini não oferecem necessariamente os mesmos níveis de esforço: esconder opção incompatível e registrar `effort_effective`. A memória de série sem alinhamento confiável deve trabalhar com **propostas** e fontes, não com inferências declaradas como fatos.

## Riscos prioritários
| Risco | Probabilidade | Impacto | Mitigação |
|---|---|---|---|
| Parser Android MOBI/AZW3 incompleto | Alta | Bloqueia P0 | POC F0+fixtures variados e fallback legal |
| Timeout/suspensão em background | Alta | Alto | blocos pequenos + WorkManager + foreground elegível + checkpoint |
| Custo e taxa API | Média | Alto | orçamento, estimativas, backoff e auditoria por bloco |
| Glossário desatualizado | Média | Alto | versionamento e revisão com fontes |
| Perda de origem pela limpeza | Média | Crítico | proteção de arquivos originais e política de exclusão explícita |
| Texto omitido ou duplicado | Média | Crítico | QA estrutural, ids/ordem e export validator |
| Problemas legais com DRM | Média | Alto | recusa de DRM, sem bypass |

## Corte de escopo
Se o prazo apertar, corte leitor completo, DOCX/PDF e RAG vetorial sofisticado. **Não corte** suporte real aos 3 formatos, checkpoints confiáveis, fluxo de tradução, glossário mínimo, revisão e EPUB válido.
