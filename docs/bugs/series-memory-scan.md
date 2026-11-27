# Corrigir a análise da memória de série

**Estado:** diagnóstico e critérios de aceite; correção ainda não implementada. Observado na v1.1.2 e em `main` no commit `fd089a7f`. Não é o problema de orçamento do modo Batch resolvido no #4.

## Diagnóstico

- [SeriesMemory.kt](../../core/src/main/kotlin/io/github/playertwo1/bookconverter/core/translate/SeriesMemory.kt), `sampleBlocks`: um primeiro bloco maior que 3.000 caracteres entra inteiro. O truncamento está dentro de uma condição que exige simultaneamente um buffer não vazio. No caminho curto, a soma não inclui os separadores. Na simulação da lógica com texto fictício, um primeiro bloco de 8.000 caracteres produziu uma amostra de 8.000, e dois blocos de 1.500 separados por duas quebras produziram 3.002.
- No mesmo arquivo, `extractFromEpub` trata a exceção do provedor como resultado nulo e avança o progresso. Em [SeriesViewModel.kt](../../app/src/main/java/io/github/playertwo1/bookconverter/ui/series/SeriesViewModel.kt), o resultado sem propostas acaba na mensagem “Nenhuma proposta”, inclusive quando nenhum trecho foi analisado com sucesso. Falhas parciais também ficam ocultas.
- Verificação inicial: revisão de código e simulação da amostragem. Sem teste Kotlin/Android, livro real ou chamada à IA.

## Instruções para o Codex

Comece pelo teste de regressão em [SeriesMemoryTest.kt](../../core/src/test/kotlin/io/github/playertwo1/bookconverter/core/SeriesMemoryTest.kt). Use EPUB sintético e provedor falso; não use credenciais, livros reais nem faça chamadas externas. Mantenha a mudança restrita à extração de memória de série e à apresentação do resultado.

1. Limite o **trecho** a 3.000 caracteres, incluindo separadores, inclusive com o primeiro parágrafo maior que o limite. Esse teto não inclui o texto do prompt e não é um teto de tokens. Preserve a amostragem de até quatro trechos.
2. Separe tentativas de análises válidas. Dê a mensagem “Nenhuma proposta” apenas para uma análise bem-sucedida sem termos (por exemplo, JSON válido `[]`). Resposta inválida, EPUB ilegível ou falha em todos os trechos devem dar uma mensagem de erro, não um resultado vazio de sucesso.
3. Se parte falhar, informe que a análise foi parcial e preserve as propostas válidas para revisão. Faça o cancelamento interromper a análise sem ser contado como falha do provedor. Não altere glossários existentes, revisão de propostas ou Batch.
4. Adicione casos para parágrafo de 8.000 caracteres, separadores na fronteira, resposta vazia válida, parcial com proposta preservada, todas as chamadas falhando, resposta inválida e cancelamento. Rode os testes de `core` e de `app` pertinentes, e registre no PR o que passou e o que não foi executado. Não faça merge nem publique release nessa etapa.
