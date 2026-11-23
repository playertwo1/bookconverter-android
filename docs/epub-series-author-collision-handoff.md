# Corrigir vínculo automático de EPUB a séries homônimas

## Problema confirmado no código da v1.0.2

`BookImporter.resolveOrCreateSeries` usa `SeriesDao.findByNameIgnoreCase`, que busca pelo nome normalizado e retorna uma linha sem verificar o autor. Se os dois autores forem conhecidos e diferentes, o importador ainda reaproveita o ID encontrado. A tela **Série e Glossário** preseleciona esse ID. Ao enfileirar, `NewTranslationViewModel` grava no job uma cópia do glossário e das notas de estilo da série selecionada.

## Trabalho para o agente

1. Escreva primeiro uma regressão em `BookImporterTest.kt`: dois EPUBs sintéticos com títulos e autores diferentes e o mesmo `calibre:series`. O segundo livro nunca deve receber o ID da série do primeiro autor. Cubra as duas entradas de importação que usam o helper, sem duplicar desnecessariamente as fixtures.
2. Faça a menor alteração que impeça vínculo automático em caso de conflito explícito. Se não for possível identificar com segurança a série correta, deixe o livro sem vínculo para o usuário escolher. A comparação não deve depender de a primeira linha encontrada ser a série correta.
3. Verifique que mesmo nome e mesmo autor continuam reutilizando a série correta, que a regra atual para preencher um autor em branco continua funcionando quando a correspondência é inequívoca e que EPUB sem metadado de série segue sem vínculo. Se o app comportar várias séries homônimas, cubra a seleção quando mais de uma linha existe.
4. Confirme que a tela permite escolher manualmente a série correta ou **Sem série** antes de traduzir. Não reescreva vínculos de livros existentes, glossários ou jobs já criados. Não inicie tradução nem use livros reais, chaves ou cotas de provedores.
5. Registre no PR os testes executados e o que não foi possível validar. Se houver emulador ou aparelho, confira a preseleção com as mesmas fixtures sem enfileirar. Sem merge, publicação ou release neste trabalho.

## Estado inicial

Este commit só registra o diagnóstico e a tarefa. A correção e os testes de regressão ainda não foram implementados. O fluxo foi rastreado no código; não foi executado em Android.
