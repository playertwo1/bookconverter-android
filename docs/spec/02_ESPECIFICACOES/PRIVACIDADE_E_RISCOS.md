# Privacidade, copyright, custo e riscos

- O app é um tradutor local-first na organização de arquivos e checkpoints, **não** um tradutor offline: provedores de IA exigem envio de trechos ao serviço remoto escolhido.
- Antes do primeiro envio e quando usar referências anteriores, mostrar o tipo de conteúdo, provedor, política de retenção/link e volume estimado de dados transmitidos. Exigir autorização.
- Chaves API não equivalem automaticamente a assinatura ChatGPT Plus/Claude/Gemini; credenciais e cobrança precisam ser tratadas separadamente. Não usar CLIs assinadas para contornar termos de serviço em app Android.
- Não quebrar DRM. Converter apenas arquivos sem proteção e que o usuário tenha direito de processar. Direitos autorais e regras de compartilhamento/distribuição de traduções devem ser respeitados.
- Preservar originais e arquivos de referência; sem exclusão silenciosa nem cópia automática para Drive.
- Memória de série não pode cruzar coleções ou enviar trechos amplos sem necessidade. Redigir política de limpeza de cache explícita, sem atingir fontes e checkpoints de jobs abertos.
- Modelos podem alucinar, omitir frases ou alterar nomes; não prometer infalibilidade. Detectar anomalias por QA estrutural e revisão amostral humana.
- Exibir consumo de tokens/custo real somente quando provedor retornar dados; estimativas com margem e informação 'estimado'.
- Proteção de upload: tamanho e contagem de arquivos, ZIP bomb, paths maliciosos, XHTML não confiável, links externos e fontes perigosas.
- Limites do Android no segundo plano: escalar job conforme estado e interromper graciosamente com checkpoints seguros.
