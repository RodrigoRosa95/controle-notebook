# Controle Notebook (app Android)

App Android que **liga**, **desliga** e **apaga/acende a tela** de um notebook (ou PC Linux) pelo celular, mais um servidor leve que roda no próprio notebook. Feito sem Gradle (Java puro, WebView e `aapt`/`dx`), com APK de cerca de 100 KB.

## Como funciona
```
 Celular (app) ──Wake-on-LAN (UDP, broadcast)──► notebook desligado acorda
      │
      └─HTTP + cabeçalho X-Token (porta 5097)──► servidor/servidor.py no notebook
              GET  /estado            → online, carga, quem está assistindo no Jellyfin
              POST /monitor/desligar  → apaga a tela (xrandr, sem bloquear a sessão)
              POST /monitor/ligar     → acende a tela
              POST /desligar {"confirmar": true} → desliga em 5 s
```
- **Ligar** é Wake-on-LAN mandado direto pelo app. Com o notebook desligado o servidor não existe, então só funciona com o celular **na mesma rede** (broadcast).
- **Ligar de fora de casa:** o app pode pedir a um relé que esteja na rede de casa (por exemplo uma TV box ou Raspberry Pi) que mande o pacote: `POST /ligar` na porta 8791 do relé, com o mesmo `X-Token`. O relé não está incluído aqui, e o campo `box` do `config.json` é opcional.
- **Segurança:** toda chamada precisa de `X-Token` igual ao arquivo `~/.config/controle-notebook/token` (gerado no primeiro uso, permissão 600, comparação em tempo constante). O token vai **dentro do APK**: não compartilhe o APK gerado.

## Instalação do servidor (no notebook)
1. Habilite o Wake-on-LAN na BIOS e na placa de rede (`sudo ethtool -s <placa> wol g`).
2. Permita o desligamento sem senha só para esse comando, em `/etc/sudoers.d/notebook-shutdown`:
   ```
   SEU_USUARIO ALL=(root) NOPASSWD: /sbin/shutdown -h now
   ```
3. Rode o servidor (ele gera o token na primeira vez):
   ```bash
   JELLYFIN_API_KEY=... SAIDA_TELA=eDP-1 python3 servidor/servidor.py    # porta 5097
   ```
   Para deixar sempre ligado use `pm2`, `systemd` ou equivalente. `SAIDA_TELA` é o nome da saída de vídeo (`xrandr`). `JELLYFIN_API_KEY` é opcional (mostra quem está assistindo).
4. Libere a porta 5097 só para a sua rede (ou para o Tailscale).

## Compilar o app
Requer `javac`, build-tools do Android (`aapt`, `dx`, `zipalign`, `apksigner`), `android.jar` (API 33) e um keystore seu.
```bash
export MAC=AA:BB:CC:DD:EE:FF            # MAC da placa de rede do notebook
export HOSTS=192.168.1.50,100.x.y.z     # IPs do notebook (casa e Tailscale)
export BROADCAST=192.168.1.255
./compilar.sh                            # gera ControleNotebook.apk
```
O script monta o `config.json` com o token e o apaga ao terminar (veja `app/assets/config.exemplo.json`).

## O que ficou de fora
O APK compilado (contém o seu token e é assinado com a sua chave) e dados de rede.

## Licença
MIT
