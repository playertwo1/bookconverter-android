# Roadmap: Bookconverter Android, de spec a app usável no S25 Ultra

Atualizado em 2026-11-15 · Horizonte: até o MVP fechado (3 formatos) e as primeiras melhorias · Público: interno (uso pessoal)

Fontes: decisões D1–D11 em [`docs/DECISOES.md`](docs/DECISOES.md) e o pacote de especificação de 15/11/2026 (PRODUCT, ARCHITECTURE, DESIGN, ROADMAP F0–F8, PLANO_DE_TESTES AC01–AC16).

**Por que não há datas:** o ritmo depende de quando o usuário consegue revisar e testar no aparelho, e não há capacidade informada (D10). Por isso cada fase tem um **marco de saída verificado no Galaxy S25 Ultra**. Uma fase sem essa evidência fica NOT_RUN e não conta como pronta.

Coluna "Pacote" = fase equivalente no ROADMAP original (F0–F8).

---

## Agora — provar o terreno e erguer a base

| # | Resultado | Por quê | Depende de | Marco de saída (no S25) | Pacote | Confiança |
|---|---|---|---|---|---|---|
| A0 | **Spikes técnicos respondidos**: (1) ler e reescrever 3 EPUBs reais em Kotlin sem perder capítulos, índice e imagens; (2) um job falso de 2h roda com tela apagada usando WorkManager + serviço `dataSync`, sobrevive a matar o processo e a reiniciar; (3) chamada real a OpenAI, Anthropic e Gemini, com a lista de modelos vinda da API e o mapa de esforço suportado | Os três maiores riscos da V1 (EPUB, segundo plano, provedores) se resolvem antes de virar arquitetura | — | Relatório curto com o resultado de cada spike e a decisão de biblioteca EPUB registrada | F0 | **encerrado** (pendências em `docs/A0_SPIKES.md`: reboot no S25 antes do P2, provedores antes do P1) |
| A1 | **App esqueleto instalável**: repo `bookconverter-android` com o pacote em `docs/`, Compose com tema AMOLED índigo, 4 abas (Biblioteca, Séries, Atividade, Ajustes), Room com teste de migração, importar via seletor (SAF) com cópia privada, biblioteca listando livros | Toda fase seguinte pendura nisso; o tema é P0 no pacote, então entra desde o início | A0 | ~~APK release assinado no GitHub Releases~~ (adiado) APK instalado no S25; importa um EPUB e o livro continua na biblioteca depois de reiniciar | F1 | **encerrado**: APK debug no S25 **PASS** (2026-11-16: importou animal_farm e *The Devils* de 6,98 MB pelo seletor, com título, autor e idioma certos; após `adb reboot` os dois seguiram na biblioteca). Testes de importador e de migração verdes. **O APK release saiu do marco por decisão do usuário (2026-11-16)**; entra quando houver algo para usar fora do adb. Título serifado: Noto Serif Bold (OFL) embutida, porque a Samsung troca o `FontFamily.Serif` do sistema por uma fonte sem serifa; conferida no S25 |
| A2 | **EPUB importado vira livro verificado e fatiado**: detecção por assinatura, recusa de DRM (`encryption.xml`), proteção de ZIP (bomb, paths), `BookDocument` normalizado, divisão em blocos com IDs estáveis, tela de Verificação | Sem blocos estáveis não há checkpoint nem tradução | A1 | AC01 e AC04 PASS com livros reais; capítulos e blocos conferidos contra o original | F2 (só EPUB, D4) | **encerrado** (2026-11-16, S25): **AC01 PASS** — *The Devils* (EPUB 3, nav) 86 documentos / índice 86 / 7.319 blocos / cobertura 99,4%; *The First Law Trilogy* (EPUB 2, NCX) 183 / 180 / 16.556 blocos / 99,5%; números conferidos por um script Python independente sobre as cópias privadas puxadas do aparelho (spine e índice idênticos, 0 links quebrados, sha256 = nome do arquivo, imagens intactas). **AC04 PASS** — EPUB com DRM sintético (Adobe AES) recusado com "Livro com conteúdo criptografado (DRM)", sem cópia nem registro. No PC: IDs de bloco estáveis entre leituras, detecção por assinatura (PDF e MOBI/AZW3 com mensagem própria), recusa de livro sem texto |

## Próximo — a V1 usável (só EPUB)

| # | Resultado | Por quê | Depende de | Marco de saída (no S25) | Pacote | Confiança |
|---|---|---|---|---|---|---|
| P1 | **Escolher a IA e ver o custo antes de gastar**: adaptadores OpenAI/Anthropic/Gemini com URL base opcional (gateway Cloudflare, D6), chave no Android Keystore, catálogo de modelos e esforço só quando suportado, consentimento de envio, **prévia de amostra + custo estimado + teto de orçamento** (D11) | O usuário decide modelo e gasto com informação real | A2 | AC05 e AC06 PASS; ~~prévia real nos 3 provedores~~ prévia real pelo ChatGPT (D14; as chaves dos outros 3 ficam para depois, por decisão do usuário em 2026-11-16); chave fora de logs e do SQLite | F3 | **encerrado** (2026-11-16, S25; repositório tornado público para o login oficial, D14): **ChatGPT pelo plano (D14) PASS** — login oficial "Sign in with ChatGPT", 8 modelos listados, prévia real do *The Egg* (`gpt-6.1-sol`, amostra 1.189 + 1.057 tokens, livro ≈ 6.696 tokens em 6 chamadas) e de novo com esforço `low` aceito (`gpt-6-luna`). **AC05 parcial PASS** — chave falsa contra a API real da OpenAI → "Chave de API inválida", nada perdido; modelo indisponível só offline. **AC06** — recusa de esforço testada offline nos 4 adaptadores (cai sem o esforço e marca o nível indisponível); recusa real NOT_RUN. **Chave fora de logs e do SQLite PASS** (teste instrumentado + logcat). **Prévia com chave OpenAI, Anthropic e Gemini: NOT_RUN, adiada pelo usuário** — o código e os testes offline existem; roda quando ele colar as chaves. Achado: o S25 corta a rede de apps em segundo plano, por isso o login troca o código só com o app na frente; o P2 precisa do serviço em primeiro plano |
| P2 | **Tradução que não perde trabalho**: máquina de estados, checkpoint transacional por bloco, pausar/retomar/cancelar, WorkManager + serviço em primeiro plano com notificação, esperas por rede, 429 e orçamento, **fila de vários livros, execução serial** (D11) | É a promessa central do produto (P0) | P1 | AC07–AC11 PASS; teste de caos (50 pausas, 20 mortes de processo, 10 reboots) com invariante `block_done = COUNT(COMPLETED)` | F4 | **encerrado** (2026-11-19, S25 Ultra `SM-S938B`, build `CP2A.260605.016.S938BXXSDDZJ1`): suíte instrumentada no aparelho real **OK (11/11 tests em 3,855s)** — `MigrationTest` 1→2 (2/2), `ProviderSettingsTest` (3/3) e `TranslationChaosTest` (6/6: AC07 pausa 73/200, AC08 crash/reabertura `IN_PROGRESS`, AC10 sem rede, AC11 429 + teto de tokens, D11 fila serial multi-livro, AC14 excluir job mantendo `Book`, e caos 200 chunks com 50 pausas, 20 crashes e 10 reaberturas reais de SQLite em disco mantendo `block_done = COUNT(COMPLETED)`). APK instalado e aberto no S25 sem erros (`bookconverter.db` migrado, 0 crashes no logcat). *Nota:* reboot físico do aparelho durante tradução ao vivo e 15 min em modo avião real ficam para o teste de uso real (não reiniciamos o telefone automaticamente) |
| P3 | **Série com glossário e estilo**: criar série, glossário manual + importar/exportar o `.txt` do desktop (`termo → tradução`, D8), só os termos presentes no bloco vão ao prompt, notas de estilo, glossário versionado por job | Mantém nomes e termos consistentes entre volumes desde a V1 (D9) | P1 (prompt) e A2 | AC12 com 20 termos sintéticos aplicados; glossário do desktop importado sem perda | F5 (parte) | alta |
| P4 | **Revisar e exportar o EPUB pt-BR**: original e tradução lado a lado, edição à mão, retradução de um bloco, exportação **só pt-BR** (D7) sem tocar o original, validação estrutural, compartilhar via intent | Fecha o ciclo: importar → traduzir → livro legível | P2 e P3 | AC13 e AC14 PASS; EPUB abre em 2 leitores (ex.: Moon+ e ReadEra) sem capítulo omitido ou duplicado | F6 | média |

**➜ Marco V1 usável:** um livro EPUB real em inglês, traduzido no S25 Ultra de ponta a ponta com glossário de série, pausas e um reboot no meio, exportado e lido num leitor externo. Junto: AC15 (rotação, fonte grande, TalkBack) nas telas existentes.

## Depois

- **D1 · MOBI/AZW3 sem DRM (fecha o MVP):** spike com libmobi compilada no NDK convertendo para EPUB internamente; AC02/AC03 com arquivos reais. **➜ Marco MVP fechado:** checklist P0 do pacote 100% PASS no aparelho (F2 restante + F7).
- **D2 · Memória de série por IA:** a IA lê volumes anteriores (EN, PT ou EPUBs traduzidos no PC) e propõe termos para aprovação; depois os pares EN↔PT como exemplos, e personagens (F5 restante).
- **D3 · Modo econômico com Batch API** nos 3 provedores, usando a mesma tabela de blocos (D5).
- **D4 · Backup e restauração manual** de banco, glossários e checkpoints (AC16).
- **D5 · Extras sob demanda:** adaptador OpenRouter/genérico, tema claro, DOCX/PDF, RAG semântico (F8).

## Fora do roadmap

- Remover DRM: proibido, por lei e pela spec.
- Play Store e uso por terceiros: o app é pessoal, distribuído por APK (D2).
- EPUB bilíngue: a saída é só pt-BR (D7).
- Sincronizar ou continuar jobs entre PC e celular: só o glossário é compartilhado (D8).
- Motor Python embutido (Chaquopy) e controle remoto do PC (D1).
- Assinaturas/CLIs (Codex, sessão Claude) como credencial: não existem no Android (D6).
- Leitor de e-book completo, OCR de PDF, tradução offline, compra/download de livros.

## Premissas e riscos

- **Segundo plano (mais frágil):** assume que WorkManager + serviço `dataSync` aguentam um livro inteiro dentro do limite de 6h/24h do Android 15+. Se o spike A0 falhar, o Batch API (D3) sobe para o Próximo, antes do P2.
- **EPUB em Kotlin:** assume que dá para ler e reescrever EPUB fiel com ZIP + parser XML/jsoup, sem lib pesada. Se não der, A2 e P4 crescem.
- **libmobi:** assume que compila no NDK e converte KF8 preservando o índice. Se falhar, MOBI/AZW3 voltam para "converter no Calibre do PC" e o MVP fechado é redefinido com o usuário.
- **Custo:** as estimativas da prévia são aproximadas; o teto de orçamento é a proteção real.
- **Qualidade da tradução no celular:** o prompt e o tamanho do bloco do desktop podem não servir no mobile; a prévia do P1 é onde isso se mede.

## Decisões em aberto

- Biblioteca de EPUB (própria vs pronta): decidir no A0, afeta A2 e P4.
- Tamanho do bloco e quanto contexto anterior vai no prompt: decidir na prévia do P1, afeta custo e P2.
- Licença LGPL da libmobi: confirmar no D1 (aceitável para uso pessoal).
- Ordem entre D2, D3 e D4: decidir quando a V1 estiver em uso real.

## Revisão

Revisar ao fim de cada fase, com a evidência do marco em mãos. Revisão antecipada se um spike do A0 falhar, se algum provedor mudar API ou preço de forma relevante, ou se o uso real da V1 mostrar que a memória por IA (D2) vale mais que MOBI/AZW3 (D1).
