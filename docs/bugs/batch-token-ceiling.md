# Corrigir o teto de tokens no Modo econômico (Batch API)

## Problema

No Bookconverter Android v1.1.1, a tela apresenta o teto de tokens como um limite de parada. O valor é salvo no trabalho, inclusive quando vem como sugestão da prévia. No primeiro envio Batch, o worker cria requisições para todos os blocos pendentes e os envia ao provedor sem verificar o teto. Só contabiliza o consumo após receber os resultados. Um teto baixo não impede o envio do livro inteiro. Confirmado no código; não houve envio real nem verificação de cobrança.

- [Controle na tela](https://github.com/playertwo1/bookconverter-android/blob/680ebb40fe4a41067f3421dbf4e7fb3e53353404/app/src/main/java/io/github/playertwo1/bookconverter/ui/translate/NewTranslationScreen.kt#L667) e [salvamento do teto](https://github.com/playertwo1/bookconverter-android/blob/680ebb40fe4a41067f3421dbf4e7fb3e53353404/app/src/main/java/io/github/playertwo1/bookconverter/ui/translate/NewTranslationViewModel.kt#L298).
- [Envio do primeiro lote](https://github.com/playertwo1/bookconverter-android/blob/680ebb40fe4a41067f3421dbf4e7fb3e53353404/app/src/main/java/io/github/playertwo1/bookconverter/worker/TranslationWorker.kt#L396).
- [Checagem entre blocos no modo normal](https://github.com/playertwo1/bookconverter-android/blob/680ebb40fe4a41067f3421dbf4e7fb3e53353404/core/src/main/kotlin/io/github/playertwo1/bookconverter/core/translate/TranslationEngine.kt#L100). O último bloco iniciado ainda pode ultrapassar o teto; a interface não deve prometer um limite rígido nesse modo sem implementá-lo.

## Correção solicitada

Enquanto o app não conseguir aplicar o teto em Batch, impedir a combinação Batch + teto no fluxo de criação com uma explicação clara. Deixar a pessoa escolher o modo normal mantendo o teto ou confirmar expressamente um Batch sem teto. Não apagar o valor configurado, não trocar de modo e não enfileirar sem limite automaticamente. Tratar também o valor sugerido pela prévia: não exibi-lo como proteção efetiva no Batch.

Adicionar proteção no worker antes de enviar um lote novo (sem `remoteBatchId`). Um trabalho antigo ou criado fora da tela com teto que não pode ser respeitado precisa parar antes de chamar o provedor e indicar como prosseguir. Para um lote já enviado com ID remoto, não reenviar nem cancelar automaticamente; preservar consulta e recuperação dos resultados conforme o fluxo existente.

## Critérios de aceitação

- Com vários blocos e teto positivo, o fluxo de criação não permite enviar Batch como se o teto valesse. Cobrir teto digitado e sugerido pela prévia.
- Com provedor falso, trabalho Batch pendente com teto positivo e sem ID remoto faz zero chamadas de envio, inclusive após nova execução do worker. A UI mostra uma orientação acionável.
- Batch explicitamente confirmado sem teto ainda funciona nos provedores que o oferecem. Nenhum lote com ID remoto é reenviado no caminho de retomada.
- Modo normal mantém o comportamento entre blocos e a interface descreve o limite com precisão. Login ChatGPT não passa a oferecer Batch.
- Usar dados sintéticos e provedores falsos. Não chamar APIs pagas, publicar APK ou fazer merge como parte deste diagnóstico.
