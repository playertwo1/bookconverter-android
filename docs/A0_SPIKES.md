# A0: relatório dos spikes técnicos

Atualizado em 2026-11-15. Regra do pacote: sem evidência, o status é NOT_RUN, nunca PASS.

| Spike | Pergunta | Status |
|---|---|---|
| 1. EPUB em Kotlin | Dá para ler e regravar EPUB real sem perder estrutura? | **PASS (PC/JVM)** |
| 2. Segundo plano | Um job de 2h sobrevive a tela apagada, morte do processo e reboot no S25? | Emulador: **PASS** (kill, pausa, cold boot) · **S25: NOT_RUN** |
| 3. Provedores | OpenAI, Anthropic e Gemini respondem, listam modelos e aceitam esforço? | **NOT_RUN** (faltam chaves) |

## Spike 1: EPUB (PASS)

Código: `core/src/main/kotlin/.../epub/Epub.kt`. Teste: `core/src/test/kotlin/.../EpubSpikeTest.kt` (`./gradlew :core:test`).

**Decisão de biblioteca (fecha o item em aberto do roadmap):** sem lib de EPUB pronta. Usamos ZIP do Java + **jsoup 1.18.3** em modo XML, que funciona igual no JVM e no Android e tolera XHTML imperfeito. São cerca de 200 linhas próprias.

Resultado com os 4 EPUBs do repo desktop:

| Livro | Idioma | Capítulos | Blocos | Caracteres | Cobertura |
|---|---|---|---|---|---|
| Liber_Esther | la | 4 | 388 | 67.819 | 99,4% |
| animal_farm | en | 20 | 393 | 202.726 | 100% |
| animal_farm_bilingual | pt-br | 21 | 409 | 208.192 | 100% |
| lemo | ja | 3 | 43 | 5.582 | 99,3% |

Cobertura é a fração do texto do `<body>` que cai dentro de algum bloco; é ela que detecta texto que seria pulado sem aviso. O primeiro teste deu 74,9% no Liber_Esther, porque a licença do Gutenberg vem só em `<div>`. O `div` folha passou a contar como bloco.

Verificado pelos testes:
- Regravar sem mudar nada mantém a spine, o texto de todos os blocos e a ordem das entradas. Tudo o que não é capítulo (imagens, CSS, OPF, NCX) sai byte a byte idêntico. `mimetype` é a primeira entrada e vai sem compressão.
- Substituir cada bloco por "[PT] …" cai no lugar certo, com a mesma quantidade de blocos.
- Recusa DRM Adobe (`encryption.xml` com AES, `rights.xml`) e Apple (`sinf.xml`); aceita só a ofuscação de fontes do IDPF/Adobe.
- Recusa ZIP com `../` (zip-slip), mais de 10.000 entradas ou mais de 512 MB descompactados.

Limites conhecidos (marcados no código como `shortcut:`): só XHTML em UTF-8; `ZipInputStream` não lê entrada STORED com data descriptor (trocar por `ZipFile` na cópia privada, se um livro real cair nisso). Ainda não foi medido no aparelho: tempo e RAM com um livro de 500+ páginas (fica para o A2).

## Spike 3: Provedores (NOT_RUN)

Código pronto: `core/src/main/kotlin/.../provider/Providers.kt`.
- OpenAI: Responses API (`/v1/responses`), esforço em `reasoning.effort`.
- Anthropic: SDK oficial `anthropic-java` 3.3.0, esforço em `output_config.effort` (`low`…`max`). Nos modelos atuais o thinking é adaptativo e sempre ligado; `budget_tokens` dá 400.
- Gemini: `generateContent`, esforço em `generationConfig.thinkingConfig.thinkingLevel`.
- Os três aceitam URL base (gateway Cloudflare); lista de modelos vem da API.

O teste traduz um parágrafo real do *animal_farm* com HTML inline. Ele mostra se o esforço foi aceito (se não, repete sem esforço), os tokens e se as tags HTML chegaram intactas. Para rodar (custa centavos):

```bash
OPENAI_API_KEY=... OPENAI_MODEL=... ANTHROPIC_API_KEY=... ANTHROPIC_MODEL=... GEMINI_API_KEY=... GEMINI_MODEL=... ./gradlew :core:test --tests '*ProviderSpikeTest*' --rerun-tasks
```

## Spike 2: Segundo plano

App de teste: `app/` (pacote `io.github.playertwo1.bookconverter.spike`). Um `CoroutineWorker` do WorkManager com serviço em primeiro plano `dataSync` "traduz" 360 blocos falsos de 20 s (2h no total). Cada bloco só vira COMPLETED depois da "chamada", num UPDATE do Room. A tela mostra "Salvos: X / N" e o log de eventos (`APP_START`, `START attempt=N`, `FGS_OK`/`FGS_FAIL`, `SAVED`, `STOP reason=…`, `DONE`).

### Emulador (PASS parcial, 2026-11-15, AVD API 36 headless)
- **Início:** `START` seguido de `FGS_OK`. O serviço em primeiro plano `dataSync` subiu e salvou 3 blocos em 70 s.
- **Morte do processo:** `am kill` não mata o processo enquanto o serviço em primeiro plano está ativo; foi preciso `kill -9`. O WorkManager subiu um processo novo cerca de 1 s depois, sozinho (`START attempt=1`, `FGS_OK`), e seguiu de 3 para 9 blocos sem que o app fosse aberto.
- **Pausar/Retomar:** `STOP reason=1` (cancelado pelo app); a contagem parou em 10. Ao retomar, continuou de 10 para 12, sem voltar a 0.
- **Cold boot do emulador:** o `adb reboot` derrubou o emulador, então foi um cold boot, não um reboot de verdade. Os 13 blocos e o log ficaram intactos. Sem abrir o app, o WorkManager retomou sozinho (`START attempt=1`, `FGS_OK`) e seguiu crescendo.
- **Nenhum bloco salvo foi perdido**, e não houve crash.
- **Corrigido depois do teste:** o texto da tela saía preto sobre fundo preto; e tocar "Iniciar novo" duas vezes zerava os blocos com o worker ainda rodando (agora o job é cancelado antes).
- **O emulador não responde:** o limite de 6h/24h do `dataSync`, o efeito da economia de bateria da Samsung, a remoção do app dos recentes e o reboot real. Isso só no S25.

### Protocolo no S25 Ultra (NOT_RUN)
1. Instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (ou copiar o APK para o celular).
2. Abrir, permitir notificações, tocar **Iniciar novo**.
3. Apagar a tela e usar o celular normalmente por 2h. No meio: abrir outros apps pesados; deslizar o app para fora dos recentes; ativar a economia de bateria por 15 min; ficar 15 min sem rede (modo avião), o que vale como teste de resiliência, já que o job falso não usa rede.
4. Reiniciar o celular uma vez, por volta de 1h, e **não** abrir o app depois.
5. No fim, abrir o app e tirar um print do log.

**Passa se:** chegar a 360/360 (ou continuar de onde parou), sem nenhum bloco salvo perdido; houver um `START` novo depois do reboot sem abrir o app; e cada interrupção aparecer como `STOP reason=…`. Anotar qualquer `FGS_FAIL`: no Android 14+, iniciar serviço em primeiro plano a partir do segundo plano pode ser bloqueado, e é isso que este spike quer descobrir.

Se falhar: o Batch API (D3 do roadmap) sobe para antes do P2, como previsto nas premissas.
