# Bookconverter Android

Tradutor literário EN → pt-BR para Android, irmão do [bookconverter](https://github.com/playertwo1/bookconverter) desktop. Uso pessoal, em Kotlin nativo.

- Especificação de produto, design e testes: [`docs/spec/`](docs/spec/README_COMECE_AQUI.md)
- Índice e precedência entre os documentos: [`docs/README.md`](docs/README.md)
- Decisões tomadas em cima do pacote: [`docs/DECISOES.md`](docs/DECISOES.md)
- Roadmap: [`ROADMAP.md`](ROADMAP.md)

## Módulos

- `core/`: Kotlin/JVM puro (EPUB e provedores de IA), testado no PC.
- `app/`: app Android. Na fase A0, é só o spike de segundo plano.

## Rodar

```bash
./gradlew :core:test
```

```bash
./gradlew :app:assembleDebug
```

Os testes de provedor só rodam se houver chaves no ambiente: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` e `GEMINI_API_KEY`, mais `<PROVEDOR>_MODEL` para traduzir uma amostra.
