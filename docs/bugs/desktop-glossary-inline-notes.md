# Corrigir importação de notas do glossário TXT no Android

**Estado:** pedido de correção, sem implementação. Confirmado por leitura do código da v1.1.2 e da main em `fd089a7f`, com exemplos sintéticos. Não foi executado no Android nem enviado texto a um provedor.

## Problema

O [parser do desktop](https://github.com/playertwo1/bookconverter/blob/555753c8c0ef8590f22de5cbb0cb46a54458b598/book_maker/glossary.py#L123-L143) separa a anotação iniciada por `#` antes de procurar o delimitador. No Android, [Glossary.parseDesktopTxt](../../core/src/main/kotlin/io/github/playertwo1/bookconverter/core/translate/Glossary.kt) ignora comentários que ocupam a linha toda, mas não as notas na mesma linha. Além disso, procura `→` na linha inteira antes de tentar `->`. O [importador de série](../../app/src/main/java/io/github/playertwo1/bookconverter/ui/series/SeriesViewModel.kt) grava o destino retornado pelo parser e já mapeia `term.notes` para a entrada persistida. O [prompt](../../core/src/main/kotlin/io/github/playertwo1/bookconverter/core/translate/Prompt.kt) instrui o modelo a usar o destino exatamente como foi salvo.

| Linha fictícia num TXT UTF-8 | Resultado atual no Android | Resultado esperado |
| --- | --- | --- |
| `North Gate → Portão Norte # nome próprio` | Destino `Portão Norte # nome próprio` | Destino `Portão Norte`, nota `nome próprio` separada |
| `North Gate -> Portão Norte # redação → revisão` | Origem `North Gate -> Portão Norte # redação`, destino `revisão` | Origem `North Gate`, destino `Portão Norte`, nota `redação → revisão` separada |
| `North Gate → Portão Norte` | Destino `Portão Norte` | Sem mudança |

## Trabalho para o Codex

1. Comece com testes Kotlin que falhem para os dois casos anotados. Separe o comentário antes de identificar o primeiro delimitador válido no texto restante. Preserve espaços internos, acentos, as duas setas aceitas, linhas em branco, comentários de linha inteira e o comportamento atual de linhas inválidas e conflitos do Android. Compare conflitos pelo termo e pela tradução limpos, sem tratar variação de nota como mudança de tradução.
2. Verifique o caminho de importação da série até a entrada persistida e o bloco “Glossário obrigatório”, com dados fictícios e sem IA real. A tradução obrigatória deve ser exatamente `Portão Norte`, sem a nota, e a origem deve continuar encontrando ocorrências de `North Gate`.
3. Revise quem usa o parser antes de mudar sua semântica. `parseSnapshot` também o chama. Garanta que snapshots existentes (inclusive trabalhos em andamento) sejam legíveis e que valores internos legítimos com `#` não sejam truncados silenciosamente. Separe o parser de importação externa do formato interno se necessário. Se o exportador público passar a preservar notas, teste ida e volta com o desktop sem mudar o formato de snapshot por acidente.
4. Não reescreva entradas antigas automaticamente: `#` pode fazer parte de um valor legítimo e o importador faz upsert. Se uma entrada contaminada já existir, deixe instruções de revisão/correção manual e teste o reimport em uma série descartável.
5. Registre comandos e resultados dos testes, e o que não foi validado em dispositivo. Sem chamadas reais a provedores, merge ou release. O #5 trata da varredura da memória de série e não faz parte deste PR.
