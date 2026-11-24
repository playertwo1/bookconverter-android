# Separar edições com ISBNs diferentes na busca de metadados

## Problema confirmado no código da v1.1.0

Em `BookMetadataAggregator.isSameBook`, a igualdade entre ISBNs normalizados é um critério de correspondência, mas a divergência entre dois ISBNs informados não impede o agrupamento. Registros de fontes diferentes ainda podem ser reunidos por identificadores da obra ou por título e autor. Depois, o agregador escolhe cada campo separadamente. Com dados incompletos, a sugestão pode mostrar o ISBN de uma edição com a editora ou a data de outra.

Este problema é distinto do vínculo de séries homônimas corrigido no PR #1. Foi confirmado pela leitura da regra e por uma checagem sintética da lógica, não em Android nem com uma resposta real de provedor.

## Trabalho para o agente

1. Reproduza com um teste no módulo `core`: duas fontes, mesmo título e autor, ISBNs de edições diferentes. Deixe a editora ausente em uma delas e demonstre que o código atual pode sugerir a combinação errada.
2. Preserve a distinção entre obra e edição. Quando houver ISBNs válidos que identificam edições distintas, não permita que título, autor ou um identificador da obra justifiquem misturar campos da edição. Verifique também agrupamento indireto via um terceiro resultado sem ISBN. Se a fonte sem ISBN for ambígua, ela não deve preencher dados específicos de uma edição como se estivesse identificada.
3. Cubra ISBN-10 e ISBN-13 equivalentes, o mesmo ISBN vindo de várias fontes, busca sem ISBN e duas edições distintas. Em uma busca por ISBN, confirme que a edição exata é identificável e não ganha editora ou data incompatível. Preserve o enriquecimento útil de nível de obra quando ele for seguro e claramente separado.
4. Use apenas fixtures fictícias e provedores simulados. Registre testes executados, resultados e lacunas. Não faça merge, deploy ou release.

## Verificação

- O teste de regressão falhou no código anterior porque a edição consultada recebeu a editora da outra edição.
- Com a correção, os 20 testes do pacote de metadados passaram com `gradlew :core:test --tests "*core.metadata.*"`. Os casos novos usam registros fictícios e cobrem ISBNs distintos, o registro sem ISBN que corresponde a duas edições, ISBN-10/ISBN-13 equivalentes e a prioridade da edição consultada.
- A verificação foi feita no módulo JVM, com provedores simulados. A interface Android e as respostas de provedores reais ficaram fora da validação.
