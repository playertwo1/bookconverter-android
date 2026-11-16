# Testes e critérios de aceite — Bookconverter Android

## Matriz P0 (bloqueia release)
| ID | Caso | Aprovação |
|---|---|---|
| AC01 | Importar EPUB simples e com imagens | capítulos/índice/metadados consistentes |
| AC02 | Importar MOBI6 DRM-free | texto/ordem/índice aceitáveis e reportados |
| AC03 | Importar AZW3 KF8 DRM-free | texto/ordem/índice aceitáveis e reportados |
| AC04 | Arquivo com DRM | app recusa com mensagem; não tenta contornar |
| AC05 | Chave inválida / modelo não disponível | mensagem clara, sem perda de trabalho |
| AC06 | Esforço indisponível | controle oculto ou estado indisponível; não forçar parâmetro |
| AC07 | Traduzir 200 blocos e pausar no 73 | 72+ blocos confirmados intactos, zero duplicações |
| AC08 | Reiniciar telefone durante resposta remota | ao voltar, duplicação zero no artefato; bloco em trânsito pode refazer |
| AC09 | Bloquear tela e abrir outros apps | estados/notificação corretos; retomada em restrição |
| AC10 | Remover rede por 15min | WAITING_NETWORK + checkpoint intacto e recuperação |
| AC11 | HTTP 429 e custos limite | retry controlado e pausa por orçamento |
| AC12 | Série com 20 termos aprovados | 20 termos na revisão; conflitos sinalizados, sem inventar |
| AC13 | Export EPUB | sem capítulos omitidos/duplicados; abre em 2 leitores |
| AC14 | Excluir job sem querer | exige confirmação e opção para manter fonte |
| AC15 | Rotação, fonte grande e TalkBack | navegação e ações acessíveis, sem truncamento de CTA |
| AC16 | Backup e recuperação | originais/checkpoints/memória preservados conforme política |

## Teste de caos mínimo
Executar 50 pausas/retomadas aleatórias, 20 reinícios do processo, 10 reinícios do aparelho, perda de rede/reconexão, falta de espaço e bloqueio de bateria. Verificar invariante `completed_count = count(COMPLETED)` e exportação idempotente. Sem evidence = NOT_RUN, nunca PASS.

## Pruebas de memória
Conjunto sintético de personagens e termos homônimos; revisar falso positivo, trecho sem par alinhado, referência contraditória e atualização de glossário após parte do livro traduzida. Verificar que o sistema mostra a fonte original e solicita revisão.

## Performance e recursos
Testar ingestão de livro >= 500 páginas, com fontes/imagens. Medir RAM, CPU, temperatura, bateria, tamanho dos dados e retomada fria. Definir metas depois de medir baseline real no S25 Ultra; não inventar valores de desempenho.
