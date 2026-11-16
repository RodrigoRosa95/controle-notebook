#!/usr/bin/env python3
"""Controle do notebook pelo celular (07/11/2026) — porta 5097, usado pelo app "Controle Notebook".

Toda chamada precisa do cabeçalho X-Token igual a ~/.config/controle-notebook/token (gerado no 1º uso).
  GET  /estado              -> {online, monitor, ligado_ha, carga, assistindo[]}
  POST /monitor/desligar    -> desliga a saída de vídeo (xrandr --off, sem bloquear a sessão); mexer no mouse/teclado acende
  POST /monitor/ligar       -> acende a tela e volta ao "nunca apagar" do religar-tela.sh
  POST /desligar  {"confirmar": true} -> desliga em 5 s (sudo /sbin/shutdown -h now, regra notebook-shutdown;
                                         o liberar-4tb.service desliga a VM do HA e o HD de 4 TB com segurança)
Ligar o notebook é Wake-on-LAN, mandado direto pelo app (com o notebook desligado este servidor não existe).
Roda no pm2 como "controle-notebook".
"""
import hmac
import json
import os
import secrets
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORTA = 5097
PASTA = os.path.expanduser('~/.config/controle-notebook')
ARQ_TOKEN = os.path.join(PASTA, 'token')
X = {**os.environ, 'DISPLAY': ':0', 'XAUTHORITY': os.path.expanduser('~/.Xauthority')}


def token():
    if not os.path.exists(ARQ_TOKEN):
        os.makedirs(PASTA, mode=0o700, exist_ok=True)
        with open(os.open(ARQ_TOKEN, os.O_WRONLY | os.O_CREAT, 0o600), 'w') as f:
            f.write(secrets.token_urlsafe(32))
    return open(ARQ_TOKEN).read().strip()


TOKEN = token()


def env(nome):
    try:
        for linha in open(os.path.expanduser('~/.env'), encoding='utf-8'):
            if linha.startswith(nome + '='):
                return linha.split('=', 1)[1].strip().strip('"\'')
    except OSError:
        pass
    return ''


def xset(*args):
    return subprocess.run(['xset', *args], env=X, capture_output=True, text=True, timeout=10)


# 07/11/2026: apagar com "xset dpms force off" ativava o protetor de tela do X e o light-locker BLOQUEAVA a sessão
# (tela de login; pelo VNC/acesso remoto ficava tudo preto). Agora a saída de vídeo é desligada pelo xrandr --off
# (15/11/2026), que não mexe no protetor de tela nem no bloqueio; uma thread reacende com --auto quando alguém usa
# o mouse/teclado.
SAIDA_TELA = os.environ.get('SAIDA_TELA', 'eDP-1')
_tela = {'escura': False}


def _ocioso_ms():
    """Milissegundos sem uso de mouse/teclado no :0 (XScreenSaverQueryInfo), ou None."""
    import ctypes
    try:
        x = ctypes.cdll.LoadLibrary('libX11.so.6')
        xss = ctypes.cdll.LoadLibrary('libXss.so.1')

        class Info(ctypes.Structure):
            _fields_ = [('window', ctypes.c_ulong), ('state', ctypes.c_int), ('kind', ctypes.c_int),
                        ('til_or_since', ctypes.c_ulong), ('idle', ctypes.c_ulong), ('eventMask', ctypes.c_ulong)]
        x.XOpenDisplay.restype = ctypes.c_void_p
        x.XDefaultRootWindow.restype = ctypes.c_ulong
        xss.XScreenSaverAllocInfo.restype = ctypes.POINTER(Info)
        d = x.XOpenDisplay(b':0')
        if not d:
            return None
        try:
            info = xss.XScreenSaverAllocInfo()
            xss.XScreenSaverQueryInfo(ctypes.c_void_p(d), ctypes.c_ulong(x.XDefaultRootWindow(ctypes.c_void_p(d))), info)
            ms = info.contents.idle
            x.XFree(info)
            return ms
        finally:
            x.XCloseDisplay(ctypes.c_void_p(d))
    except OSError:
        return None


def saida(*args):
    return subprocess.run(['xrandr', '--output', SAIDA_TELA, *args], env=X,
                          capture_output=True, text=True, timeout=10)


def escurecer():
    r = saida('--off')
    if r.returncode == 0 and not _tela['escura']:
        _tela['escura'] = True
        threading.Thread(target=_vigiar_uso, daemon=True).start()
    return r


def acender():
    _tela['escura'] = False
    r = saida('--auto', '--brightness', '1')
    xset('dpms', 'force', 'on')
    xset('-dpms')
    xset('s', 'reset')
    return r


def _vigiar_uso():
    time.sleep(3)   # o toque no app não conta
    while _tela['escura']:
        ms = _ocioso_ms()
        if ms is not None and ms < 1500:
            acender()
            print(time.strftime('[%Y-%m-%d %H:%M:%S]'), 'tela reacesa: mexeram no notebook', flush=True)
            return
        time.sleep(1)


def monitor():
    if _tela['escura']:
        return 'desligado'
    saida_x = subprocess.run(['xrandr', '--query'], env=X, capture_output=True, text=True, timeout=10).stdout
    for linha in saida_x.splitlines():
        if linha.startswith(SAIDA_TELA + ' '):
            if ' disconnected' in linha or linha.split()[1] == 'off':
                return 'desligado'
            return 'ligado'
    return 'desconhecido'


def assistindo():
    try:
        req = urllib.request.Request('http://127.0.0.1:8096/Sessions?activeWithinSeconds=300',
                                     headers={'X-Emby-Token': env('JELLYFIN_API_KEY')})
        lista = []
        for s in json.load(urllib.request.urlopen(req, timeout=5)):
            n = s.get('NowPlayingItem')
            if n:
                lista.append(f"{s.get('UserName')}: " + (f"{n['SeriesName']} — " if n.get('SeriesName') else '')
                             + (n.get('Name') or ''))
        return lista
    except Exception:
        return []


def desligar_depois():
    time.sleep(5)
    subprocess.run(['sudo', '-n', '/sbin/shutdown', '-h', 'now'])


class Tratador(BaseHTTPRequestHandler):
    server_version = 'ControleNotebook/1.0'

    def log_message(self, fmt, *args):
        print(time.strftime('[%Y-%m-%d %H:%M:%S]'), self.client_address[0], fmt % args, flush=True)

    def responder(self, codigo, dados):
        corpo = json.dumps(dados, ensure_ascii=False).encode()
        self.send_response(codigo)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(corpo)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(corpo)

    def autorizado(self):
        if hmac.compare_digest(self.headers.get('X-Token', ''), TOKEN):
            return True
        self.responder(401, {'erro': 'código do app errado'})
        return False

    def do_GET(self):
        if not self.autorizado():
            return
        if self.path == '/estado':
            with open('/proc/uptime') as f:
                ligado = int(float(f.read().split()[0]))
            return self.responder(200, {'online': True, 'monitor': monitor(), 'ligado_ha': ligado,
                                        'carga': round(os.getloadavg()[0], 1), 'assistindo': assistindo()})
        self.responder(404, {'erro': 'não existe'})

    def do_POST(self):
        if not self.autorizado():
            return
        tam = int(self.headers.get('Content-Length') or 0)
        try:
            corpo = json.loads(self.rfile.read(tam) or b'{}') if tam else {}
        except ValueError:
            corpo = {}
        if self.path == '/monitor/desligar':
            r = escurecer()
            return self.responder(200 if r.returncode == 0 else 500, {'ok': r.returncode == 0, 'monitor': 'desligado',
                                                                     'erro': r.stderr.strip()[:200]})
        if self.path == '/monitor/ligar':
            r = acender()
            return self.responder(200 if r.returncode == 0 else 500, {'ok': r.returncode == 0, 'monitor': 'ligado',
                                                                     'erro': r.stderr.strip()[:200]})
        if self.path == '/desligar':
            if corpo.get('confirmar') is not True:
                return self.responder(400, {'erro': 'faltou confirmar'})
            print(time.strftime('[%Y-%m-%d %H:%M:%S]'), 'DESLIGANDO a pedido do app', self.client_address[0], flush=True)
            threading.Thread(target=desligar_depois, daemon=True).start()
            return self.responder(200, {'ok': True, 'mensagem': 'desligando em 5 segundos'})
        self.responder(404, {'erro': 'não existe'})


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', PORTA), Tratador).serve_forever()
