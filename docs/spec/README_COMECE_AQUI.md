# Bookconverter Android — Pacote de produto, UX e engenharia
**Edição de planejamento:** 15/11/2026 | **Estado:** proposta / especificação, NÃO aplicativo funcional

## O que contém
- `01_DOCUMENTOS/Bookconverter_Android_Manual_Produto_MVP.docx`: manual de produto, MVP, operação e exemplos de uso.
- `02_ESPECIFICACOES/`: contratos de produto, design, arquitetura, modelo de dados, fluxos e privacidade.
- `03_ROADMAP/ROADMAP.md`: fases, dependências, critérios de saída, riscos e prioridades.
- `04_DESIGN/screens/`: **23 mockups individuais PNG** (fluxos completos).
- `04_DESIGN/boards/`: **4 pranchas** com as 23 telas.
- `04_DESIGN/referencias_aprovadas/`: imagem de referência fornecida pelo usuário e conceitos anteriores; as pranchas novas seguem a paleta índigo.
- `05_CRITERIOS_DE_ACEITE/`: cenários de teste e bloqueios de liberação.
- `06_PROMPTS/`: AGENTS.md e instruções de execução para equipes de implementação/auditoria.
- `07_REFERENCIAS/`: fontes, compatibilidade, limites e atribuições.

## Autoridade de decisão
1. Requisitos explícitos do usuário e paleta AMOLED índigo: P0.
2. Restrições reais Android, políticas das APIs, DRM/licenças: não negociáveis.
3. Referência visual é diretriz de composição; não prova funcionalidade. Não usar textos de demonstração como dados reais.
4. O usuário aprova qualquer alteração material de escopo; nenhum trabalho deve ser iniciado só porque uma imagem o mostra.

## Limite desta entrega
O ZIP é uma especificação de construção e acervo de referência visual, não contém APK, código Android funcional ou tradução de livros. O repositório original Windows não foi modificado.

## Primeira ação recomendada
Abrir `03_ROADMAP/ROADMAP.md`, concluir o gate F0 e fazer o spike real de leitura EPUB/MOBI/AZW3 sem DRM no Android antes de comprometer a interface com uma arquitetura.
