# FloatBrowser

Navegador Android com janelas flutuantes (GeckoView). Cada janela tem cookies isolados e seu próprio
proxy (lista gratuita, manual ou IP real), com troca automática quando o proxy cai, ferramentas de
página (seletor 🎯, gravador, CSV, monitor) e scripts de automação.

## Teclas da janela
Cada janela tem uma fileira de teclas: ◀ voltar · ▶ avançar · ⟳ recarregar · 🔀 trocar proxy (novo IP) · 🌐 mostrar o IP de saída · 🛠 ligar/desligar as ferramentas das páginas · 👁 monitor de página (fica verde quando ligado; toque para parar).

**Monitor:** toque em 🛠 → 🎯 Seletor → escolha o elemento (preço, texto) → ajuste os segundos (mín. 30) e, se quiser, o texto a procurar → 👁 Monitorar. A janela recarrega a página no intervalo e avisa por notificação quando o valor muda (ou quando o texto aparece). Vale enquanto a janela estiver aberta.
O IP real continua só no toque longo do ícone da barra, para evitar toque acidental.

## Perfis
Tela inicial → **Perfis**: crie um perfil com nome, site inicial e proxy (`auto`, `direto` ou `ip:porta`).
Cada perfil tem seus **próprios cookies** (guardados pelo nome; não renomeie) e abre numa janela com a
barra mostrando o nome. Um perfil só pode estar aberto uma vez. Com proxy fixo, se ele cair a janela troca
para outro proxy da lista (nunca cai no IP real sozinha).

## Agendamentos
Tela inicial → **Agendamentos**: escolha um perfil e o horário (`08:30` = todo dia, `30m` ou `2h` = a cada tanto).
Na hora, o app abre a janela do perfil (site, proxy e cookies próprios) ou, se já estiver aberta, recarrega a página.
Para o script rodar sozinho, deixe `INICIAR_AUTOMATICO = true` nele. Com agendamentos ativos o app mantém o serviço
ligado (notificação fixa) e, com "Manter ativo com tela desligada: SIM", o aparelho não dorme. Se você tocar em
**Parar** na notificação, o agendador só volta quando abrir o app de novo. Não reinicia sozinho após reiniciar o celular.

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
