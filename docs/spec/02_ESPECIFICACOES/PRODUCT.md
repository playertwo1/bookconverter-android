# PRODUCT — Bookconverter Android
**Versão de proposta:** 1.0 / 15-11-2026

## Problema e diferenciação
Pessoas que leem séries em inglês precisam de traduções pt-BR consistentes entre volumes. Ferramentas comuns traduzem trechos desconectados, trocam terminologia, perdem a estrutura do EPUB e não toleram falhas ou pausas durante livros extensos. O Bookconverter será **um tradutor literário confiável e retomável**, não uma simples caixa de texto.

## Pessoa e contexto
Leitor que importa livros legais em EPUB/MOBI/AZW3 no Android, escolhe modelo OpenAI/Claude/Gemini, usa arquivos anteriores como referência, acompanha a tradução e exporta para seu leitor preferido. A operação precisa caber no celular e tolerar interrupções.

## Promessa testável
Para livro DRM-free suportado, importar > configurar > traduzir por blocos > pausar/retomar > revisar > exportar. Ao reiniciar o dispositivo, nenhum bloco **confirmado e gravado** deve desaparecer; um bloco em trânsito pode ser refeito.

## MVP — escopo fechado proposto
- Importar EPUB, MOBI e AZW3 **sem DRM**, com detecção de formato, integridade básica e relatório de falhas compreensível.
- Estrutura normalizada: capítulos, blocos, notas, negrito/itálico, índice, imagens e metadados na medida disponível no original.
- Tradução EN > pt-BR por APIs oficiais OpenAI/Anthropic/Gemini, chave trazida pelo usuário e modelo compatível selecionável. Esforço somente se exposto pela API/modelo, sem oferecer valor fictício.
- Prévia curta da tradução, aviso de custo estimado e orçamento/teto opcional antes de iniciar.
- Fila persistente e serial inicial, pausar, retomar, cancelar com confirmação, checkpoint transacional por bloco, tentativas com limites e modo de rede.
- Execução em segundo plano com notificação e recuperação quando o Android permitir. Em interrupção forçada, salvar e reagendar, sem promessa de execução permanente.
- Biblioteca local e status: aguardando, traduzindo, pausa solicitada, pausado, aguardando recurso, erro recuperável, concluído, cancelado.
- Séries: criar coleção, associar volume, importar referência anterior original ou traduzida; glossário de termos aprovados e amostras de pares EN-PT quando ambos existirem. Prompt recebe somente contexto relevante do volume.
- Revisão simples por bloco / capítulo com original e tradução lado a lado ou alternando, edição pontual e marcação de inconsistência.
- Exportar EPUB fluido pt-BR com índice/estrutura preservados; validação do arquivo e compartilhamento Android. PDF/DOCX são desejados pós-MVP; se antecipados, requerem teste específico.
- Armazenamento local sem apagar arquivos originais/referências; credenciais seguras e conteúdo enviado a provedor com consentimento.

## Fora do MVP
- DRM removal; sincronização automática entre dispositivos; tradução sem acesso à nuvem; login com assinatura ChatGPT Plus como substituto automático da API; leitor completo tipo Kindle; OCR robusto de PDF; biblioteca de compra/download; Batch API de provedores sem avaliação; processamento simultâneo de dezenas de livros; grafo mundial de personagens.

## Jornadas prioritárias
J1 Solo: importar EPUB > escolher Gemini > nível conforme suporte > prévia > iniciar > bloquear tela > reabrir > exportar.
J2 Saga: criar série > anexar dois volumes pt-BR e originais EN > aprovar 20 termos > importar terceiro > tradução contextual > verificar termos > EPUB.
J3 Falha: iniciar AZW3 > interromper internet > pausar > reiniciar > retomar sem duplicar bloco e sem perda.
J4 DRM: importar arquivo protegido > mostrar motivo e orientar a fornecer arquivo sem DRM adquirido licitamente > sem tentativa de remoção.

## Principais métricas
- 0 blocos concluídos perdidos no teste de crash/reboot; 0 duplicações silenciosas no EPUB final.
- ≥99% dos casos de retomada chegam a estado estável após reinício/reconexão nos cenários automatizados; 100% nos cenários P0 do gate físico.
- 0 seções/capítulos omitidos silenciosamente no conjunto de testes estruturais; divergência sinalizada para revisão.
- Glossário aprovado respeitado em 100% dos testes sintéticos com termos inequívocos, casos ambíguos sinalizados.
- Visibilidade do custo e da origem do conteúdo antes de enviar dados a provedores remotos.

## Regras de produto
- A IA sugere; o usuário aprova mudanças importantes de glossário e decisões editoriais.
- Não prometer 'tradução perfeita' ou tempo restante exato; mostrar estimativas com rótulo.
- Não apagar original automaticamente; exclua trabalho só por ação explícita com confirmação.
- Não enviar referências inteiras em cada trecho; limitar contexto e registrar versão do glossário.
