#!/bin/bash
# Compila ControleNotebook.apk sem Gradle (aapt + javac + dx + apksigner).
# Pré-requisitos: build-tools do Android (aapt, dx, zipalign, apksigner), android.jar (API 33) e um keystore.
# Variáveis: SDK_BT (pasta build-tools), ANDROID_JAR, KEYSTORE, KEYSTORE_PASS_FILE
set -e
cd "$(dirname "$0")"
BT=${SDK_BT:-/usr/lib/android-sdk/build-tools/29.0.3}
JAR=${ANDROID_JAR:-$HOME/.local/share/android-33/android.jar}
CHAVE=${KEYSTORE:-$HOME/.config/controle-notebook/assinatura.jks}
SENHA_ARQ=${KEYSTORE_PASS_FILE:-$HOME/.config/controle-notebook/senha}
TOKEN_ARQ=${TOKEN_FILE:-$HOME/.config/controle-notebook/token}
[ -f "$TOKEN_ARQ" ] || { echo "Falta o token: inicie servidor/servidor.py uma vez (ele gera $TOKEN_ARQ)."; exit 1; }
[ -n "$MAC" ] && [ -n "$HOSTS" ] || { echo "Defina MAC (do notebook), HOSTS (IPs separados por vírgula) e BROADCAST."; exit 1; }

# config.json com o token DENTRO do APK: não compartilhe o APK gerado
python3 - "$TOKEN_ARQ" > app/assets/config.json <<'PY'
import json, os, sys
print(json.dumps({"token": open(sys.argv[1]).read().strip(), "mac": os.environ["MAC"],
                  "broadcast": os.environ.get("BROADCAST", "192.168.1.255"),
                  "hosts": os.environ["HOSTS"].split(","), "box": [h for h in os.environ.get("BOX", "").split(",") if h]}))
PY
chmod 600 app/assets/config.json
OBRA=$(mktemp -d); mkdir -p "$OBRA/classes"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d "$OBRA/classes" $(find app/src -name '*.java')
"$BT/dx" --dex --min-sdk-version=26 --output="$OBRA/classes.dex" "$OBRA/classes"
"$BT/aapt" package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$JAR" -F "$OBRA/sem-codigo.apk"
(cd "$OBRA" && cp sem-codigo.apk sem-assinatura.apk && "$BT/aapt" add sem-assinatura.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 "$OBRA/sem-assinatura.apk" "$OBRA/alinhado.apk"
"$BT/apksigner" sign --ks "$CHAVE" --ks-pass "file:$SENHA_ARQ" --out ControleNotebook.apk "$OBRA/alinhado.apk"
rm -rf "$OBRA" ControleNotebook.apk.idsig app/assets/config.json
echo "✔ ControleNotebook.apk ($(du -h ControleNotebook.apk | cut -f1))"
