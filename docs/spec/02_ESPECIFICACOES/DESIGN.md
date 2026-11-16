# DESIGN — Bookconverter Android / iOS-inspired

## Design North Star
“Leitura primeiro; tecnologia em segundo plano.” A interface deve parecer um app editorial contemporâneo com superfície AMOLED, respeitando gestos e ergonomia Android. Usar Apple HIG como **inspiração de hierarquia e tipografia**, não copiar código SwiftUI nem fingir ser um app iOS.

## Tokens aprovados
| Token | Valor | Uso |
|---|---|---|
| bg | `#000000` | tela AMOLED |
| surface | `#161618` | cartões/listas |
| surface_alt | `#202024` | campos, seletores |
| stroke | `rgba(255,255,255,0.10)` | contornos discretos |
| accent | `#6366F1` | CTA, item ativo, progresso, ações |
| accent_subtle | `rgba(99,102,241,0.18)` | seleção de listas |
| highlight | `rgba(245,158,11,0.20)` | marca-texto seleção de palavra |
| primary | `#F5F5F7` | cabeçalhos e corpo prioritário |
| secondary | `#98989D` | tradução secundária, legendas |
| tertiary | `#636366` | autores, labels |
| positive | `#35B77B` | conclusão real |
| danger | `#F36D7A` | erro/destrutivo |

## Tipografia
No protótipo foram usadas fontes abertas disponíveis no ambiente (serifada equivalente e sans-serif Lato/Inter). Para Android, escolher fontes distribuíveis ou tipografia do sistema licitamente. **Não incluir New York, Charter ou SF Pro no APK como arquivos extraídos do iOS.** Título principal editorial 32sp, corpo leitor ~19sp, entrelinha adicional equivalente a 7sp, parágrafo 16sp; UI títulos 15sp semibold; miniaturas 13sp, metadados 11sp. Escala do usuário nunca pode quebrar ações.

## Layout e interação
- Telas principais: Biblioteca, Séries, Atividade, Ajustes; barra flutuante capsule 64dp aproximadamente (ajustar com área segura e guidelines Android), 24dp margens horizontais, separação inferior segura.
- Tela de leitura/revisão: barra contextual flutuante 56dp, original forte e tradução cinza, realce âmbar sem roubar a atenção.
- Card 'Continue lendo' / 'Continuar traduzindo': raio 16dp, padding 12dp; capas grid raio 8dp e gap 12dp.
- Não usar um card em cada linha de texto longo. Priorizar listas editoriais e respiro.
- Botão principal único por tela; ação destrutiva distante de Pausar; confirmação para cancelar/excluir.
- Controles precisam atingir ao menos 48dp; leitores de tela e estados não dependem só da cor.
- A interface mostra claramente 'Processando', 'Salvo até o bloco N', 'Pausa solicitada', 'Pausado' e 'Em espera por rede'.

## Equivalência técnica Android
- SwiftUI `Capsule` → Compose `RoundedCornerShape(50)` / `CircleShape`.
- `.ultraThinMaterial` → superfície com alpha/blur moderado apenas na nav; usar fallback opaco para desempenho e acessibilidade.
- SwiftUI `.safeArea` → Compose `WindowInsets` e edge-to-edge; proteger navegação de gestos.
- Ícones nativos do sistema/plataforma, sem depender de emojis.
- Tema claro opcional pós-MVP; preto AMOLED obrigatório para V1.

## Telas e referência
Ver `04_DESIGN/CATALOGO_TELAS.json` e `04_DESIGN/screens/`. As imagens representam intenção visual, não implementação ou evidência de teste. 23 telas cobrem onboarding, biblioteca, importação, verificação, modelo/esforço, memória, glossário, fila, background, revisão, exportação e falhas. Favor não copiar textos ilustrativos como produtos/modelos realmente disponíveis.

## Não fazer
Não misturar laranja e roxo como cores principais; âmbar é exclusivamente marca-texto na seleção. Não usar blur intenso em todo cartão. Não exibir porcentagem de 'salvo' baseada em blocos ainda não comitados. Não prometer custo exato antes do processamento.
