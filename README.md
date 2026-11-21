# Bookconverter Android

Tradutor literário **EN → pt-BR** nativo para Android (Kotlin, Jetpack Compose, Room, WorkManager e `libmobi` via NDK), irmão do [bookconverter](https://github.com/playertwo1/bookconverter) desktop. Uso pessoal, distribuído via APK assinado.

---

## O que o aplicativo faz

- **Biblioteca e Importação (EPUB, MOBI e AZW3 sem DRM)**
  - Importação via seletor de documentos (SAF) com cópia privada isolada em app storage (`SHA-256`).
  - Suporte nativo a **EPUB 2/3** (`jsoup` + ZIP com proteção contra Zip-Slip e Zip-Bomb) e **MOBI6 / AZW3 (KF8)** sem DRM via **`libmobi` compilada no NDK** (`arm64-v8a` e `x86_64`) convertida internamente para EPUB.
  - Recusa explícita de arquivos com **DRM** (Adobe, Apple, Amazon Kindle) sem contorno e sem gravar cópia.
  - Tela de **Verificação** mostrando capítulos, índice (NCX/NAV), blocos estáveis e cobertura de texto.

- **Provedores de IA e Controle de Gasto**
  - Suporte a **OpenAI** (Responses API), **ChatGPT pelo plano** (login oficial *Sign in with ChatGPT* via PKCE), **Anthropic** (Claude), **Google Gemini** e **OpenRouter / Genérico** (qualquer endpoint compatível com `/v1/chat/completions`, incluindo DeepSeek, Groq, Ollama e gateway Cloudflare via URL base opcional).
  - Chaves de API e tokens OAuth cifrados com **Android Keystore (AES-GCM)** — nunca salvos em SQLite nem exibidos em logs.
  - **Prévia real de amostra** + estimativa de tokens de entrada/saída do livro inteiro + **teto de orçamento em tokens**.

- **Tradução Resiliente em Segundo Plano + Fila Serial**
  - Máquina de estados e checkpoint transacional por bloco no **Room** (`block_done = COUNT(COMPLETED)`), com reconciliação automática após queda de processo ou reinício.
  - **WorkManager** + **Foreground Service `dataSync`** com notificação de progresso e ação **Pausar**.
  - Pausar, retomar, cancelar e fila serial de múltiplos livros (FIFO), além de esperas automáticas para falta de rede (`WAITING_NETWORK`), HTTP 429 (`WAITING_RATE_LIMIT`) e teto de orçamento (`PAUSED_BUDGET`).
  - **Modo econômico (Batch API)** assíncrono para OpenAI, Anthropic e Gemini.

- **Séries, Glossário, Estilo e Memória por IA**
  - Criação de séries, vínculo com livros e notas de estilo.
  - Glossário manual + importação/exportação no formato `.txt` do desktop (`termo → tradução` e `->`), injetando no prompt **apenas os termos presentes em cada bloco**.
  - **Memória por IA**: extrai propostas de **Termos**, **Personagens** e **Pares EN↔PT** de volumes anteriores (EN, PT ou traduzidos no PC), sempre mostrando o trecho original e sinalizando conflitos para aprovação manual.
  - **RAG semântico local** (TF-IDF + trigramas) para selecionar os pares EN↔PT mais relevantes de cada trecho.

- **Revisão, Tradução Rápida e Exportação**
  - **Revisão lado a lado** (Original vs. Tradução pt-BR), edição manual por bloco, retradução de bloco isolado e exportação limpa **só em pt-BR** (sem tocar no original) em **EPUB** (validado estruturalmente), **DOCX** e **PDF**, além de compartilhamento via Intent (`FileProvider`).
  - Janela **Traduzir texto**: cole qualquer texto avulso, escolha uma série opcional, traduza e exporte em **TXT**, **DOC (.docx)**, **PDF** ou **Markdown (.md)**.

- **Backup, Acessibilidade e Tema**
  - **Backup e restauração manual** em `.zip` (banco SQLite com `wal_checkpoint`, cópias privadas dos livros e glossários `.txt` — sem incluir chaves de API).
  - Tema **AMOLED Escuro**, **Claro** e **Sistema**, com suporte a rotação (`rememberSaveable`), fonte ampliada e TalkBack.

---

## Módulos

- `core/` — Motor puro Kotlin/JVM (`Epub`, `MobiEpubPackager`, `EpubExporter`, `TextExporter`, `Providers`, `BatchProviders`, `Catalog`, `TranslationEngine`, `Glossary`, `SeriesMemory`, `SeriesRag`), testado no PC.
- `app/` — Aplicativo Android (Jetpack Compose, Room v5 com migrações `1→5`, WorkManager + FGS `dataSync`, Keystore, `PdfDocument`, NDK/CMake + `libmobi`).

---

## Como compilar e testar

```bash
# Testes unitários (JVM)
./gradlew :core:test :app:testDebugUnitTest

# Build Debug (compila NDK libmobi para arm64-v8a e x86_64)
./gradlew :app:assembleDebug

# Testes instrumentados no aparelho/emulador conectado
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.playertwo1.bookconverter.test/androidx.test.runner.AndroidJUnitRunner
```

> Os testes de integração reais com provedores em `:core` rodam apenas se as variáveis `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` e `GEMINI_API_KEY` (mais `<PROVEDOR>_MODEL`) estiverem definidas no ambiente.

---

## Documentação e Especificação

- Especificação de produto, arquitetura, design e critérios de aceite: [`docs/spec/`](docs/spec/README_COMECE_AQUI.md)
- Precedência entre documentos: [`docs/README.md`](docs/README.md)
- Decisões técnicas (D1–D14): [`docs/DECISOES.md`](docs/DECISOES.md)
- Roadmap e marcos de saída: [`ROADMAP.md`](ROADMAP.md)
