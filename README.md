# FloatBrowser

Navegador Android com janelas flutuantes (GeckoView). Cada janela tem cookies isolados e seu próprio
proxy (lista gratuita, manual ou IP real), com troca automática quando o proxy cai, ferramentas de
página (seletor 🎯, gravador, CSV, monitor) e scripts de automação.

## Teclas da janela
Cada janela tem duas fileiras de teclas.
Linha 1: ◀ voltar · ▶ avançar · ⟳ recarregar · 🔀 trocar proxy (novo IP, sem gastar o atual: se não houver outro livre, ele continua) · 🌐 mostrar o IP de saída num aviso (é medido pelo app; para testar o que o navegador usa de verdade, digite `meuip` na barra de endereço).
Linha 2 (ferramentas, agem na página da própria janela): 🎯 seletor CSS · ⏺ gravador de passos (toque de novo para parar e salvar) · 📊 extrair lista para CSV · 👁 monitor (verde = ligado) · 🛠 liga/desliga as ferramentas (azul = ligadas; recarrega a página).
O IP real continua só no toque longo do ícone da barra, para evitar toque acidental.

**Monitor:** toque em 🎯 e escolha o elemento (preço, texto) → ajuste os segundos (mín. 30) e, se quiser, o texto a procurar no painel → toque em 👁. A janela recarrega a página no intervalo e avisa por notificação quando o valor muda (ou quando o texto aparece). Vale enquanto a janela estiver aberta.

## Perfis
Tela inicial → **Perfis**: crie um perfil com nome, site inicial e proxy (`auto`, `direto` ou `ip:porta`).
Cada perfil tem seus **próprios cookies** (guardados pelo nome; não renomeie) e abre numa janela com a
barra mostrando o nome. Um perfil só pode estar aberto uma vez. Com proxy fixo, se ele cair a janela troca
para outro proxy da lista (nunca cai no IP real sozinha).

## Agendamentos
Tela inicial → **Agendamentos**: escolha um perfil e o horário (`08:30` = todo dia, `30m` ou `2h` = a cada tanto).
Na hora, o app abre a janela do perfil (site, proxy e cookies próprios) ou, se já estiver aberta, recarrega a página.
Para o script rodar sozinho, deixe `INICIAR_AUTOMATICO = true` nele. Com agendamentos ativos o app mantém o serviço
ligado (notificação fixa). **Bateria:** com janela aberta e "Manter ativo com tela desligada: SIM", o aparelho fica acordado
e os agendamentos rodam na hora. **Sem nenhuma janela aberta o aparelho pode dormir**: o agendamento então roda quando ele
acordar (ao ligar a tela, por exemplo) e pode atrasar. O agendador dorme até o próximo horário em vez de acordar a cada 30 s.
Se você tocar em **Parar** na notificação, o agendador só volta quando abrir o app de novo. Não reinicia sozinho após reiniciar o celular.

## Perfis com monitor
Se você liga o monitor 👁 numa janela de **perfil**, ele fica salvo no perfil (aparece 👁 na lista). Ao abrir o perfil
de novo, o monitor volta sozinho. Desligar o monitor na janela remove do perfil. Editar o perfil não apaga o monitor.

## Assinatura fixa (atualizar sem desinstalar)
No Termux, dentro da pasta do repositório: `bash tools/gerar-chave.sh`. Ele cria uma chave própria (uma vez) e guarda
nos *secrets* do GitHub (`FB_KEYSTORE_B64`, `FB_KEYSTORE_PASSWORD`, `FB_KEY_ALIAS`, `FB_KEY_PASSWORD`).
O release do GitHub Actions passa a sair sempre com a mesma assinatura.
**Guarde o arquivo `~/floatbrowser.jks` e a senha `~/floatbrowser.pass` em local seguro**: sem eles não dá para atualizar por cima.
Na primeira vez, desinstale a versão antiga (assinada com outra chave) antes de instalar.

## Compilar
```
gradle wrapper --gradle-version 8.7   # uma vez; gera ./gradlew (commite os arquivos gerados)
./gradlew testDebugUnitTest           # testes unitários
./gradlew assembleDebug               # APK debug
./gradlew assembleRelease             # APK release (assinado se FB_KEYSTORE estiver definido, senão chave debug)
```
Assinatura de release (variáveis de ambiente): `FB_KEYSTORE`, `FB_KEYSTORE_PASSWORD`, `FB_KEY_ALIAS`, `FB_KEY_PASSWORD`.
No GitHub Actions, crie os secrets `FB_KEYSTORE_B64` (keystore em base64), `FB_KEYSTORE_PASSWORD`,
`FB_KEY_ALIAS`, `FB_KEY_PASSWORD`.

## Fixar a versão do GeckoView
`gradle.properties` traz `geckoVersion=[128.0,134.0)` (faixa original). Depois de um build, o resumo do
GitHub Actions mostra a versão resolvida: copie-a para `geckoVersion=` para builds reproduzíveis.
Atualize a versão periodicamente (correções de segurança do motor).

## Segurança
- Proxys gratuitos podem ler/alterar tráfego sem HTTPS. Não faça login em contas importantes por eles.
- Senhas escritas em scripts de automação ficam em texto puro no arquivo.
