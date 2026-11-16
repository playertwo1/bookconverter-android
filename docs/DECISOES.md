# Bookconverter Android
Status: concluído · Início: 2026-11-15
Objetivo: decidir o que o pacote de especificação (zip de 15/11/2026) deixou em aberto, para montar o roadmap da versão Android.

Base: o pacote já fixa produto (EN→pt-BR, EPUB/MOBI/AZW3 sem DRM, OpenAI/Claude/Gemini, pausar/retomar, memória de série), design (AMOLED índigo, 23 telas) e as fases F0–F8. Aqui ficam só as decisões novas.

## Decisões
### D1 - Arquitetura do motor
- Pergunta: como o motor de tradução roda no Android?
- Decisão: Kotlin nativo (Compose, Room, WorkManager). Do desktop reaproveitamos só prompts, formato de glossário e regras de checagem, como referência.
- Por quê: é o que funciona melhor com segundo plano e bateria no Android, e é mais fácil de manter no celular. O pacote já recomendava.
- Descartado: Python embutido via Chaquopy (APK pesado, dependências nativas arriscadas); celular só como controle remoto do PC (exige o PC ligado).
- Difícil de reverter: sim

### D2 - Público e distribuição
- Pergunta: quem usa e como é distribuído?
- Decisão: uso pessoal no Galaxy S25 Ultra; APK assinado publicado em GitHub Releases. minSdk alto (Android 14+, API 34).
- Por quê: tira do caminho a revisão da Play, a política de privacidade pública e a matriz de aparelhos.
- Descartado: amigos/teste fechado; Play Store pública.
- Difícil de reverter: não

### D3 - Repositório
- Pergunta: onde fica o código Android?
- Decisão: repositório novo e separado (ex.: playertwo1/bookconverter-android). O pacote de especificação vai para o docs/ desse repo.
- Por quê: o fork desktop continua sincronizando com o upstream sem conflito com Gradle.
- Descartado: pasta android/ dentro deste repo.
- Difícil de reverter: não

### D4 - Ordem dos formatos
- Pergunta: a primeira versão usável pode ser só EPUB?
- Decisão: sim. A V1 usável lê só EPUB. MOBI/AZW3 entram numa fase própria logo depois, após um spike com libmobi (C, LGPL) compilada com o NDK e convertida para EPUB internamente. O "MVP fechado" continua exigindo os 3 formatos.
- Por quê: tira o maior risco técnico do caminho crítico sem abandonar o requisito.
- Descartado: os 3 formatos desde a V1; cortar MOBI/AZW3 e converter no Calibre do PC.
- Difícil de reverter: não

### D5 - Modo de execução das chamadas
- Pergunta: síncrono bloco a bloco ou Batch API?
- Decisão: a V1 é síncrona, bloco a bloco. A Batch API (OpenAI/Anthropic/Gemini) entra numa fase posterior como modo "econômico", usando os mesmos checkpoints.
- Por quê: a prévia, a retradução de trechos e o progresso ao vivo dependem do síncrono; o Batch reaproveita a mesma tabela de blocos.
- Descartado: Batch como modo principal; só síncrono.
- Difícil de reverter: não

### D6 - Provedores e credenciais
- Pergunta: quais conexões de IA o app aceita?
- Decisão: OpenAI, Anthropic e Gemini com a chave do usuário (guardada com o Android Keystore), cada um com campo opcional de URL base, o que cobre o gateway Cloudflare.
- Por quê: atende o pedido original e cobre o gateway que o desktop já usa, quase sem código a mais.
- Descartado: endpoints fixos; adaptador OpenRouter/genérico (pode entrar depois). Assinaturas/CLIs (Codex, sessão Claude) não existem no Android.
- Difícil de reverter: não

### D7 - Formato de saída
- Pergunta: qual EPUB o app exporta?
- Decisão: só o EPUB em pt-BR (livro traduzido limpo), como o pacote descreve.
- Por quê: escolha do usuário; é o livro que ele quer ler.
- Descartado: bilíngue; ambos com escolha na exportação.
- Difícil de reverter: não

### D8 - Compatibilidade com o desktop
- Pergunta: o app Android conversa com o bookconverter do PC?
- Decisão: só o glossário. O app importa e exporta no mesmo .txt do desktop (`termo → tradução`, também aceita `->`) e manda ao prompt só os termos que aparecem no bloco, como o `book_maker/glossary.py`. EPUBs traduzidos no PC servem como referência de série. Sem sincronização.
- Por quê: reaproveita o glossário que o usuário já mantém, sem o custo de um formato de checkpoint comum.
- Descartado: apps independentes; continuar um job entre PC e celular.
- Difícil de reverter: não

### D9 - Memória de série na V1
- Pergunta: o que da memória de série entra na primeira versão usável?
- Decisão: na V1, série com glossário (manual ou importado do .txt) e notas de estilo no prompt. Na fase seguinte, a IA lê volumes anteriores e propõe termos para aprovação. Depois vêm os pares EN↔PT como exemplos; personagens ficam junto ou depois.
- Por quê: o glossário sozinho já mantém nomes consistentes; a extração por IA tem custo e precisa de UI de aprovação.
- Descartado: extração por IA já na V1; tudo do pacote de uma vez.
- Difícil de reverter: não

### D10 - Unidade de tempo do roadmap
- Pergunta: como o roadmap marca o tempo?
- Decisão: fases Agora / Próximo / Depois com marcos, sem datas. Cada fase tem um critério de saída testado no S25 Ultra.
- Por quê: o ritmo depende da disponibilidade do usuário para revisar e testar no aparelho; com datas, o roadmap fingiria uma precisão que não existe.
- Descartado: semanas estimadas; data-alvo fixa.
- Difícil de reverter: não

### D11 - Corte da V1 usável
- Pergunta: além de importar EPUB, traduzir com pausar/retomar, glossário e exportar, o que é obrigatório na V1?
- Decisão: entram a prévia com custo estimado e teto de orçamento, a revisão e edição lado a lado (com retradução de bloco) e a fila com vários livros (execução serial).
- Por quê: escolha do usuário. A prévia e o teto protegem o bolso; a revisão evita exportar erro; a fila permite deixar livros enfileirados.
- Descartado da V1 (vai para depois): backup/restauração manual.
- Difícil de reverter: não

### Assumido (do pacote, sem nova pergunta)
- EN → pt-BR fixo; DRM recusado, sem contorno.
- Tema AMOLED índigo (tokens do pacote) desde o primeiro build, porque o pacote o trata como P0. Polimento visual e animações só depois do fluxo real.
- Lista de modelos e níveis de esforço lida da API de cada provedor e das capacidades conhecidas, sem lista fixa inventada.
- Consentimento antes de enviar texto ao provedor; nada de texto de livro ou chave nos logs.
- Execução em segundo plano: WorkManager mais serviço em primeiro plano `dataSync` (limite de 6h a cada 24h no Android 15+), com checkpoint por bloco.

## Em aberto
- Biblioteca de EPUB em Kotlin: escrever o leitor e o escritor com ZIP + XmlPullParser/jsoup, ou usar uma lib pronta. Decidir no spike F0.
- libmobi via NDK: licença LGPL (aceitável para uso pessoal) e se a conversão KF8 → EPUB preserva o índice. Decidir no spike da fase de formatos.
- Tamanho do bloco e uso de contexto (blocos anteriores no prompt): medir na prévia com livros reais.
- Backup, Batch API, extração de termos por IA, pares EN↔PT, personagens e OpenRouter ficam para as fases seguintes, na ordem do roadmap.

## Próximos passos
- Montar o roadmap Android (Agora/Próximo/Depois) a partir destas decisões e do ROADMAP F0–F8 do pacote.
- Criar o repo `bookconverter-android` e copiar o pacote de especificação para `docs/`.
