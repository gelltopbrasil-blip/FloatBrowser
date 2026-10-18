# FloatBrowser

Navegador Android com janelas flutuantes (GeckoView). Cada janela tem cookies isolados e seu próprio
proxy (lista gratuita, manual ou IP real), com troca automática quando o proxy cai, ferramentas de
página (seletor 🎯, gravador, CSV, monitor) e scripts de automação.

## Teclas da janela
Cada janela tem uma fileira de teclas: ◀ voltar · ▶ avançar · ⟳ recarregar · 🔀 trocar proxy (novo IP) · 🌐 mostrar o IP de saída · 🛠 ligar/desligar as ferramentas das páginas.
O IP real continua só no toque longo do ícone da barra, para evitar toque acidental.

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
