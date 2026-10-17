# EXPO2026 - Enerza

Enerzā é um painel de casa inteligente feito para a EXPO2026. Um servidor Java entrega as páginas HTML e integra login/cadastro (com MySQL), Spotify e aparelhos Xiaomi, tudo em uma tela só.

## Funcionalidades

- **Cadastro e login de usuários**
  - Contas salvas no MySQL, com a senha protegida (PBKDF2 com sal, nunca em texto puro).
  - Sessão por cookie (`HttpOnly`, `SameSite=Lax`), também guardada no banco. Reiniciar o servidor não derruba o login.
  - Opção "Manter conectado": 30 dias. Sem ela, a sessão dura até fechar o navegador (máximo de 12 horas).
  - Botão de sair (⏻) na tela inicial.
- **Páginas protegidas:** a tela inicial e as APIs do Spotify e da Xiaomi só funcionam para quem está logado. Sem login, o usuário é levado para a tela de login.
- **Spotify:** mostra a música atual com capa e progresso, e permite play/pause, próxima e anterior. O token do Spotify fica só no servidor, o navegador nunca o vê.
- **Aparelhos Xiaomi (Mi Home):** lista o estado e liga/desliga lâmpadas, tomadas, ventiladores, umidificadores, ar-condicionado, aspiradores etc. Há duas formas de integrar:
  - pela integração oficial Xiaomi Home, via Home Assistant (recomendado);
  - direto pela rede local, com o protocolo miIO.
- **Tela inicial:** clima, principais gastos de energia, Wi-Fi, cômodos e câmera (parte visual).

## Requisitos

- **JDK 17 ou superior** (recomendado o 21), com `javac` disponível. Para instalar: `winget install EclipseAdoptium.Temurin.21.JDK`
- **MySQL** rodando (MySQL Server ou XAMPP).
- Opcional: conta Spotify Premium (para controlar a reprodução), aparelhos Xiaomi na mesma rede ou um Home Assistant.

O driver do MySQL (`mysql-connector-j`) é baixado automaticamente pelo `executar.bat` na pasta `lib`, na primeira execução.

## Como executar

1. Deixe o MySQL rodando.
2. Preencha o `config.properties` (veja as seções abaixo).
3. Dê dois cliques no `executar.bat`. Ele baixa o driver (se faltar), compila e inicia o servidor.
4. O navegador abre em `http://127.0.0.1:8080/`. Acesse sempre por esse endereço, e não abrindo os arquivos HTML direto.

## Estrutura do projeto

```
EXPO2026\
├── executar.bat            compila e inicia o servidor
├── config.properties       configurações (não envie para o GitHub)
├── index.html              página inicial
├── lib\                    driver do MySQL (baixado automaticamente)
├── src\                    código Java (package enerza)
│   ├── Servidor.java               servidor HTTP, entrega HTML/CSS/JS/imagens e protege as rotas
│   ├── AuthService.java            cadastro, login e sessões (MySQL)
│   ├── SpotifyService.java         login no Spotify e play/pause/próxima
│   ├── XiaomiService.java          liga/desliga aparelhos Xiaomi
│   ├── MiioClient.java             protocolo miIO (rede local)
│   └── HomeAssistantService.java   integração com o Home Assistant
└── html\
    ├── login2.html, cadastro.html, inicio.html
    ├── css\                style.css e img\
    └── script\
        ├── script.js       tela inicial (chama /api/spotify/* e /api/xiaomi/*)
        ├── login.js        tela de login
        └── cadastro.js     tela de cadastro
```

O `executar.bat` encontra os `.java` em qualquer subpasta.

## Configuração (`config.properties`)

Crie o arquivo na raiz do projeto:

```properties
# Servidor
porta=8080
abrir.navegador=true

# MySQL
db.host=localhost
db.porta=3306
db.nome=enerza
db.usuario=root
db.senha=SUA_SENHA

# Spotify
spotify.client.id=
spotify.client.secret=

# Home Assistant + integração Xiaomi Home (opcional)
ha.url=http://homeassistant.local:8123
ha.token=
ha.integracao=xiaomi_home

# Aparelhos Xiaomi pela rede local (opcional)
xiaomi.dispositivos=luz
xiaomi.luz.nome=Luz da sala
xiaomi.luz.icone=💡
xiaomi.luz.ip=192.168.0.20
xiaomi.luz.token=32_CARACTERES_HEXADECIMAIS
xiaomi.luz.protocolo=miot
xiaomi.luz.siid=2
xiaomi.luz.piid=1
```

- Sem as chaves do Spotify, o player fica desativado. Sem `ha.*` e sem `xiaomi.*`, a lista de aparelhos fica vazia.
- O exemplo `xiaomi.luz.*` usa IP e token fictícios, só para mostrar o formato. Troque pelos seus ou apague o bloco.
- O `config.properties` guarda senhas e tokens. Ele nunca é entregue pelo servidor web, mas não o envie para repositórios públicos.

## MySQL

Não é preciso criar nada à mão: ao iniciar, o servidor cria o banco `enerza` e as tabelas `usuarios` e `sessoes`, se ainda não existirem. O usuário do MySQL precisa de permissão para criar bancos e tabelas.

Opcionalmente, crie um usuário só para o projeto (mais seguro que usar o `root`):

```sql
CREATE DATABASE IF NOT EXISTS enerza CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'enerza'@'localhost' IDENTIFIED BY 'troque_esta_senha';
GRANT ALL PRIVILEGES ON enerza.* TO 'enerza'@'localhost';
FLUSH PRIVILEGES;
```

Se a conexão falhar, o servidor para na inicialização com a mensagem `ERRO MySQL: ...`.

Para conferir as contas cadastradas:

```sql
SELECT id, nome, email, criado_em FROM enerza.usuarios;
```

Se existir um arquivo `.usuarios` da versão anterior (sem MySQL), as contas são importadas uma única vez e o arquivo é renomeado para `.usuarios.migrado`.

## Spotify

1. Acesse https://developer.spotify.com/dashboard → **Create app** → marque **Web API**.
2. Em Redirect URI, cadastre exatamente `http://127.0.0.1:8080/spotify/callback` (o Spotify não aceita mais `localhost`). Se mudar a `porta`, ajuste aqui também. O servidor mostra o endereço certo ao iniciar.
3. Em **User Management**, adicione o e-mail da conta Spotify que vai usar (apps em modo de desenvolvimento só aceitam usuários cadastrados).
4. Copie o Client ID e o Client Secret para `spotify.client.id` e `spotify.client.secret` no `config.properties`.
5. Na página de início, clique no card de música para conectar.

Controlar a música (play/pause/próxima) exige Spotify Premium. Ver o que está tocando funciona em qualquer conta.

## Xiaomi Home oficial (ha_xiaomi_home) - recomendado

A integração oficial da Xiaomi (https://github.com/XiaoMi/ha_xiaomi_home) é um plugin do **Home Assistant**. Por isso o Enerzā conversa com o Home Assistant, e ele conversa com a nuvem Xiaomi.

1. Instale o Home Assistant (no Windows: Home Assistant OS numa máquina virtual VirtualBox/Hyper-V, veja https://www.home-assistant.io/installation/windows, ou num Raspberry Pi).
2. Instale a integração Xiaomi Home: pelo HACS (procure "Xiaomi Home") ou, no terminal do Home Assistant:
   `cd /config && git clone https://github.com/XiaoMi/ha_xiaomi_home.git && cd ha_xiaomi_home && ./install.sh /config`
3. Reinicie o Home Assistant → Configurações → Dispositivos e serviços → Adicionar integração → **Xiaomi Home**. Faça login com a conta do app Mi Home e escolha os aparelhos.
   O login da Xiaomi volta para `http://homeassistant.local:8123`. Se o PC não achar esse endereço, adicione em `C:\Windows\System32\drivers\etc\hosts` a linha `IP_DO_HOME_ASSISTANT homeassistant.local`.
4. No Home Assistant: clique no seu perfil → Segurança → **Tokens de acesso de longa duração** → Criar token.
5. No `config.properties`, preencha `ha.url=http://homeassistant.local:8123` e `ha.token=` com o token criado.

Os aparelhos aparecem sozinhos no card "Xiaomi Home" da página de início, com botão liga/desliga.

## Xiaomi direto pela rede local (alternativa sem Home Assistant)

Não existe API oficial para controlar o app Mi Home. Esta opção fala direto com os aparelhos pela rede local usando o protocolo miIO (o mesmo do Home Assistant). O PC precisa estar na mesma rede Wi-Fi.

Para cada aparelho você precisa de **IP** e **token**:
- **Token:** use o *Xiaomi Cloud Tokens Extractor* (https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor), entrando com a mesma conta do app Mi Home. Ele lista nome, IP, modelo e token de cada aparelho.
- **`protocolo=miot`** serve para a maioria dos aparelhos novos. Liga/desliga costuma ser `siid=2 piid=1`. Confira o modelo em https://home.miot-spec.com se não funcionar.
- **`protocolo=legado`** é para aparelhos antigos e lâmpadas Yeelight.

Aparelhos que só funcionam por Bluetooth/Zigbee (sem Wi-Fi próprio) não respondem pela rede local.

## Rotas da API

| Rota | Descrição | Exige login |
|---|---|---|
| `POST /api/auth/cadastro` | Cria a conta e já inicia a sessão | não |
| `POST /api/auth/login` | Entra com email e senha | não |
| `POST /api/auth/logout` | Encerra a sessão | não |
| `GET /api/auth/eu` | Dados do usuário logado | sim |
| `GET /api/spotify/player` | Estado do player | sim |
| `POST /api/spotify/play`, `pause`, `next`, `previous` | Controles do player | sim |
| `GET /api/xiaomi/dispositivos` | Lista os aparelhos e o estado | sim |
| `POST /api/xiaomi/ligar?id=...&valor=true` | Liga ou desliga um aparelho | sim |

## Segurança

- Senhas guardadas só como hash (PBKDF2-HMAC-SHA256, 120 mil iterações, sal individual).
- No banco fica apenas o hash do token de sessão.
- O servidor escuta somente em `127.0.0.1`, ou seja, só o próprio computador acessa.
- Só são entregues arquivos de tipos web conhecidos (`html`, `css`, `js`, imagens). Arquivos como `config.properties` e `.java` nunca são servidos.

Se for publicar o projeto, adicione ao `.gitignore`: `config.properties`, `.spotify-token`, `.usuarios.migrado`, `lib/` e `out/`.

## Problemas comuns

| Mensagem | O que fazer |
|---|---|
| `O JDK nao foi encontrado` | Instale o JDK e abra um novo terminal. |
| `ERRO MySQL: ...` | Confira se o MySQL está ligado e se `db.usuario` e `db.senha` estão certos. |
| `Nenhum arquivo .java encontrado` | Coloque os `.java` dentro da pasta do projeto (em `src`). |
| `cannot find symbol ... Servidor` | Os `.java` estão misturados: alguns com `package enerza;` e outros sem. Deixe todos iguais. |
| Página sem estilo ou sem funcionar | Confira a pasta `html` (`css` e `script`) e acesse por `http://127.0.0.1:8080`. |
| "Servidor offline" no login | Inicie o `executar.bat` e use o endereço acima. |
| Spotify "recusou (403)" | Confira o app no painel do Spotify e se seu e-mail está em User Management. |
| Aparelho Xiaomi com "⚠" | Confira IP e token (miIO) ou `ha.url` e `ha.token` (Home Assistant). |