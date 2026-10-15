# Enerzā em Java

Servidor Java (sem dependências, Java 8+) que exibe as páginas HTML e integra Spotify e Xiaomi.

## Rodar
1. Instale um JDK: `winget install EclipseAdoptium.Temurin.21.JDK`
2. Preencha o `config.properties`
3. Dê dois cliques em `executar.bat` — o navegador abre em http://127.0.0.1:8080/

## Estrutura
- `src/Servidor.java` – servidor HTTP, entrega HTML/CSS/JS/imagens
- `src/SpotifyService.java` – login no Spotify e play/pause/próxima (token fica no Java)
- `src/XiaomiService.java` + `MiioClient.java` – liga/desliga aparelhos Xiaomi pela rede local
- `script/script.js` – código da página de início (chama `/api/spotify/*` e `/api/xiaomi/*`)

## Spotify
1. https://developer.spotify.com/dashboard → Create app → marque **Web API**
2. Redirect URI: `http://127.0.0.1:8080/spotify/callback` (o Spotify não aceita mais `localhost`)
3. Em **User Management**, adicione o e-mail da conta Spotify que vai usar (apps em modo de desenvolvimento só aceitam usuários cadastrados)
4. Copie Client ID e Client Secret para o `config.properties`
5. Na página de início, clique no card de música para conectar

Controlar a música (play/pause/próxima) exige Spotify Premium; ver o que está tocando funciona em qualquer conta.

## Xiaomi Home oficial (ha_xiaomi_home) — recomendado
A integração oficial da Xiaomi (https://github.com/XiaoMi/ha_xiaomi_home) é um plugin do **Home Assistant**,
então o Enerzā conversa com o Home Assistant e ele conversa com a nuvem Xiaomi.

1. Instale o Home Assistant (no Windows: Home Assistant OS numa máquina virtual VirtualBox/Hyper-V —
   https://www.home-assistant.io/installation/windows — ou num Raspberry Pi).
2. Instale a integração Xiaomi Home: pelo HACS (procure "Xiaomi Home") ou, no terminal do Home Assistant:
   `cd /config && git clone https://github.com/XiaoMi/ha_xiaomi_home.git && cd ha_xiaomi_home && ./install.sh /config`
3. Reinicie o Home Assistant → Configurações → Dispositivos e serviços → Adicionar integração → **Xiaomi Home**
   → faça login com a conta do app Mi Home e escolha os aparelhos.
   O login da Xiaomi volta para `http://homeassistant.local:8123`. Se o PC não achar esse endereço, adicione em
   `C:\Windows\System32\drivers\etc\hosts` a linha `IP_DO_HOME_ASSISTANT homeassistant.local`.
4. No Home Assistant: clique no seu perfil → Segurança → **Tokens de acesso de longa duração** → Criar token.
5. No `config.properties`: `ha.url=http://homeassistant.local:8123` e `ha.token=` com o token criado.

Lâmpadas, tomadas, ventiladores, umidificadores, ar-condicionado, aspiradores etc. aparecem sozinhos no card
"Xiaomi Home" da página de início, com botão liga/desliga.

## Xiaomi direto pela rede local (alternativa sem Home Assistant)
Não existe API oficial para controlar o app Mi Home; esta opção fala direto com os aparelhos
pela rede local usando o protocolo miIO (o mesmo do Home Assistant). O PC precisa estar na mesma rede Wi-Fi.

Para cada aparelho você precisa de **IP** e **token**:
- Token: use o *Xiaomi Cloud Tokens Extractor* (https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor),
  entrando com a mesma conta do app Mi Home. Ele lista nome, IP, modelo e token de cada aparelho.
- `protocolo=miot` serve para a maioria dos aparelhos novos. Liga/desliga costuma ser `siid=2 piid=1`;
  confira o modelo em https://home.miot-spec.com se não funcionar.
- `protocolo=legado` para aparelhos antigos e lâmpadas Yeelight.

Aparelhos que só funcionam por Bluetooth/Zigbee (sem Wi-Fi próprio) não respondem pela rede local.
