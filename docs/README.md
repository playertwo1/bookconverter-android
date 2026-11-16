# Documentação: o que vale

| Arquivo | O que é |
|---|---|
| [`../ROADMAP.md`](../ROADMAP.md) | Ordem de trabalho vigente (Agora/Próximo/Depois) |
| [`DECISOES.md`](DECISOES.md) | Decisões D1–D11 do questionário de 2026-11-15 |
| [`A0_SPIKES.md`](A0_SPIKES.md) | Resultado dos spikes técnicos e protocolo de teste no S25 |
| [`spec/`](spec/README_COMECE_AQUI.md) | Pacote de especificação original (produto, design, arquitetura, 23 telas, testes). Não editar: o `MANIFEST.json` guarda os hashes |

## Precedência

Onde `DECISOES.md` e `ROADMAP.md` divergem da spec, **eles vencem**. A spec continua valendo em tudo o que eles não mencionam (design, tokens, telas, modelo de dados, fluxos, plano de testes AC01–AC16).

| Tema | Spec original | Vale agora |
|---|---|---|
| Primeira versão usável | EPUB, MOBI e AZW3 juntos | Só EPUB; MOBI/AZW3 numa fase própria, que fecha o MVP (D4) |
| Roadmap | Fases F0–F8 | Agora/Próximo/Depois, sem datas, com marcos no S25 (D10). O mapa para as fases F está no `ROADMAP.md` |
| Batch API | Pós-MVP, "sem avaliação" | Modo econômico depois da V1, sobre os mesmos checkpoints (D5) |
| Provedores | Endpoints oficiais | Os mesmos três, com URL base opcional para gateway (D6) |
| Glossário | Interno ao app | Importa e exporta o `.txt` do desktop (`termo → tradução`) (D8) |
| Série na V1 | Glossário, perfil, pares e extração por IA | Glossário e estilo; extração por IA e pares depois (D9) |
| Saída | EPUB | Só EPUB pt-BR, sem bilíngue (D7) |
| Distribuição | Não definida | APK próprio via GitHub Releases, minSdk 34 (D2) |
| Backup manual | MVP | Fora da V1, vai para "Depois" (D11) |
| Motor | Kotlin nativo (sugestão) | Kotlin nativo (decisão) (D1) |
