# Briefing e decisões recebidas — fonte de verdade do produto
**Data:** 15/11/2026. Registra o que foi solicitado ao longo da conversa, separando afirmações do usuário das decisões técnicas sugeridas.

## Produto e plataforma
- O repositório fonte é https://github.com/playertwo1/bookconverter, atualmente descrito no README como fork Windows em Python/Tkinter do `yihong0618/bilingual_book_maker`.
- Novo produto: aplicativo Android para traduzir livros **do inglês para português brasileiro**, com seleção **ChatGPT/OpenAI, Claude/Anthropic ou Gemini/Google**, modelo e esforço de raciocínio **quando suportado**.
- A tradução deve funcionar em segundo plano na medida permitida pelo Android, com **pausar e retomar sem perder os blocos confirmados**, inclusive ao fechar o app, ficar offline e reiniciar o celular.
- Formatos de entrada exigidos: **EPUB, MOBI e AZW3**. Suporte sem contorno de DRM. Compatibilidade real deve ser demonstrada antes da aprovação da fase.
- Livros de uma mesma série: importar volumes anteriores em inglês, português ou pares inglês+português, usar glossário e exemplos anteriores para manter personagens, nomes, lugares, termos e estilo de tradução.
- Aplicativo de uso principal no celular Android; inspiração visual de apps iOS/Apple. GPUI Kit pode servir como referência, mas não como dependência móvel obrigatória.
- Criar imagens das telas, manual DOCX, MVP, roadmap, especificações, ZIP e cópia no Google Drive.

## Direção visual explícita aprovada pelo usuário
- Fundo principal AMOLED: `#000000`.
- Cartões/superfícies: `#161618`.
- Bordas: branco `#FFFFFF` com 10% de opacidade.
- **Accent principal `#6366F1` (índigo)** para botões, progresso e ícones ativos.
- Marcação de seleção no texto: `#F59E0B` com 20% de opacidade.
- Texto principal: `#F5F5F7`; texto secundário/tradução: `#98989D`; terciário/autores: `#636366`.
- Título de biblioteca: fonte editorial serifada estilo *New York*, `32 pt`, negrito, entrelinha curta.
- Texto de leitura inglês/português: serifada estilo *New York/Charter*, `19 pt`, entrelinha extra de `7 pt` e espaço de parágrafo de `16 pt`.
- UI/botões/seções: sem serifa estilo *SF Pro*, seção `15 pt semibold`, livro em grade `13 pt medium`, autores `11 pt regular`.
- Barra inferior em cápsula flutuante, altura `64 pt`, lateral `24 pt`, margem inferior `8 pt` sobre a área segura; controles de tradução em cápsula `56 pt`.
- Cartão 'Continue lendo': `16 pt` de raio, padding interno `12 pt`; capas do grid: raio `8 pt`, colunas espaçadas `12 pt`.
- Material de barra: equivalente visual a `.ultraThinMaterial` com cobertura escura `black 65%`; sombra preta suave. No Android usar equivalente em Compose e evitar blur pesado com problemas de desempenho.
- Código SwiftUI fornecido pelo usuário, como **referência de comportamento visual**, não para executar no Android:

```swift
HStack {
    // Seus botões aqui
}
.padding(.horizontal, 24)
.frame(height: 64)
.background(.ultraThinMaterial)
.background(Color.black.opacity(0.6))
.clipShape(Capsule())
.overlay(Capsule().stroke(Color.white.opacity(0.12), lineWidth: 1))
.shadow(color: .black.opacity(0.6), radius: 16, x: 0, y: 8)
.padding(.horizontal, 24)
```

## Princípios funcionais explícitos
- O usuário não quer perder trabalho por bloqueio, troca de app, pausa, queda da rede ou encerramento.
- A referência do livro anterior não deve ser apagada automaticamente.
- Progresso que aparece deve representar blocos confirmados localmente; distinguir 'processando' de 'salvo'.
- A interface deve mostrar claramente o provedor/modelo/esforço escolhido, controles de fila e exportação.

## Sugestões de engenharia (a validar no desenvolvimento)
- Android nativo Kotlin + Jetpack Compose; Room/SQLite, WorkManager e serviço em primeiro plano conforme necessidade e regras da versão Android.
- Etapa de parsing/normalização sem DRM de MOBI/AZW3 por biblioteca compatível ou engine convertida para Android, nunca assumir Calibre de desktop embutido no Android.
- Memória de série fase inicial com pares e glossário versionado; expansão RAG semântico após testes de utilidade, custo e privacidade.
- Exporte prioritariamente EPUB; PDF e DOCX como evolução ou sob confirmação de aceitação.
