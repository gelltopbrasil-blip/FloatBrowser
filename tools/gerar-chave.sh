#!/data/data/com.termux/files/usr/bin/bash
# Gera uma chave de assinatura fixa e guarda nos secrets do repositório. Rode dentro da pasta do repo.
set -e
JKS="$HOME/floatbrowser.jks"; PASSF="$HOME/floatbrowser.pass"; ALIAS="floatbrowser"

command -v keytool >/dev/null || pkg install -y openjdk-17
command -v gh >/dev/null || pkg install -y gh
gh auth status >/dev/null 2>&1 || { echo "Faça login antes: gh auth login -h github.com -p https -w"; exit 1; }

if [ -f "$JKS" ] && [ -f "$PASSF" ]; then
  echo "Já existe $JKS: vou reutilizar a mesma chave."
  PASS=$(cat "$PASSF")
else
  PASS=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  keytool -genkeypair -keystore "$JKS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=FloatBrowser"
  echo "$PASS" > "$PASSF"
  chmod 600 "$JKS" "$PASSF"
fi

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
echo "Guardando os secrets em $REPO ..."
base64 -w0 "$JKS" | gh secret set FB_KEYSTORE_B64 --repo "$REPO"
printf %s "$PASS" | gh secret set FB_KEYSTORE_PASSWORD --repo "$REPO"
printf %s "$ALIAS" | gh secret set FB_KEY_ALIAS --repo "$REPO"
printf %s "$PASS" | gh secret set FB_KEY_PASSWORD --repo "$REPO"

echo
echo "Pronto. FAÇA UMA CÓPIA de $JKS e $PASSF (ex.: copie para ~/storage/downloads)."
echo "Sem esses dois arquivos não dá para atualizar o app por cima no futuro."
