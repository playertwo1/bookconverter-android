# AGENTS.md — execução de implementação para Bookconverter Android

## Missão
Criar app Android de tradução literária, fundamentado em `PRODUCT.md`, `DESIGN.md`, `ARCHITECTURE.md` e `ROADMAP.md`, sem perder texto traduzido confirmado. Este pacote é planejamento; nenhum agente deve declarar features DONE sem execução real dos testes.

## Leitura obrigatória antes de codificar
1. `../00_BRIEF/REQUISITOS_USUARIO_E_DECISOES.md`
2. `../02_ESPECIFICACOES/PRODUCT.md`
3. `../02_ESPECIFICACOES/DESIGN.md`
4. `../02_ESPECIFICACOES/ARCHITECTURE.md`
5. `../03_ROADMAP/ROADMAP.md`
6. `../05_CRITERIOS_DE_ACEITE/PLANO_DE_TESTES.md`

## Regras
- Respeitar Kotlin/Compose Android e APIs oficiais. Não portar indiscriminadamente Python/Tkinter do fork Windows.
- Documentar cada parser, licença e caso DRM-free. Sem DRM bypass.
- `P0`: forma+fila+checkpoint; não fazer animação antes do fluxo real.
- Motor calcula e persiste; IA traduz e sugere; usuário confirma termos controversos.
- Modelos e efforts são configuráveis e capability-based, não listas estáticas fictícias.
- Nunca guardar chaves nem conteúdo de livro em log sem proteção. Não enviar conteúdo remoto sem consentimento.
- Originais e referências nunca excluídos automaticamente. Versionar glossário e prompts.
- Todo gate exige evidência objetiva: comando, saída, versão, screenshot no dispositivo e resultado dos cenários.
- Não alegar segundo plano contínuo garantido no Android.

## Ciclo de trabalho
`SPEC → DESIGN/PLANO → IMPLEMENTAÇÃO MÍNIMA → TESTES UNITÁRIOS → TESTES DE INTEGRAÇÃO → TESTE DISPOSITIVO → AUDITORIA → DECISÃO`.

## Autoridade
Usuário aprova alterações de produto, modelo de custos e remoções; Builder não fecha os próprios gates de qualidade sem revisão independente.
