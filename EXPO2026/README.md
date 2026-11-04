# EXPO2026 - Enerza

Enerzā é um painel de casa inteligente feito para a EXPO2026. Um servidor Java entrega as páginas HTML e integra login/cadastro (com MySQL), Spotify, aparelhos Xiaomi e tomadas/lâmpadas Smart Life (Tuya), tudo em uma tela só.

## Funcionalidades

- **Cadastro e login de usuários**
  - Contas salvas no MySQL, com a senha protegida (PBKDF2 com sal, nunca em texto puro).
  - Sessão por cookie (`HttpOnly`, `SameSite=Lax`), também guardada no banco. Reiniciar o servidor não derruba o login.
  - Opção "Manter conectado": 30 dias. Sem ela, a sessão dura até fechar o navegador (máximo de 12 horas).
  - Botão de sair (⏻) na tela inicial.
- **Páginas protegidas:** a tela inicial e as APIs do Spotify, da Xiaomi e dos dispositivos só funcionam para quem está logado. Sem login, o usuário é levado para a tela de login.
- **Spotify:** mostra a música atual com capa e progresso, e permite play/pause, próxima e anterior. O token do Spotify fica só no servidor, o navegador nunca o vê.
- **Aparelhos Xiaomi (Mi Home):** lista o estado e liga/desliga lâmpadas, tomadas, ventiladores, umidificadores, ar-condicionado, aspiradores etc. Há duas formas de integrar:
  - pela integração oficial Xiaomi Home, via Home Assistant (recomendado);
  - direto pela rede local, com o protocolo miIO.
- **Dispositivos Smart Life (Tuya):** tomadas e lâmpadas que usam o app Smart Life são controladas pela nuvem da Tuya. Os aparelhos ficam cadastrados no MySQL, por família: todos os membros ligam e desligam, só o administrador adiciona, remove e troca o cômodo. A página **Dispositivos** mostra cada aparelho com o cômodo, o botão liga/desliga e o consumo.
- **Gastos de energia por cômodo:** ao cadastrar um aparelho, você escolhe em qual cômodo ele está (Sala, Cozinha, Quarto ou Banheiro). A caixa de gastos de cada cômodo lista só os aparelhos dele, e a tela inicial mostra os 5 que mais gastam na casa. O consumo vem da medição da tomada (se ela mede) ou da potência em watts informada no cadastro. Aparelho desligado conta 0 W.
- **Timers da cozinha e do banheiro:** você define minutos e segundos (ou usa um atalho), inicia, pausa ou zera. Quando o tempo acaba, toca um aviso sonoro e a tela pisca em vermelho com a frase do cômodo ("A comida está pronta!" na cozinha e "O tempo do seu banho acabou!" no banheiro).
- **Tela inicial:** clima, principais gastos de energia (aparelhos cadastrados), Wi-Fi, cômodos e câmera (parte visual).

## Requisitos

- **JDK 17 ou superior** (recomendado o 21), com `javac` disponível. Para instalar: `winget install EclipseAdoptium.Temurin.21.JDK`
- **MySQL** rodando (MySQL Server ou XAMPP).
- Opcional: conta Spotify Premium (para controlar a reprodução), aparelhos Xiaomi na mesma rede ou um Home Assistant.
- Opcional: conta gratuita na plataforma de desenvolvedores da Tuya (platform.tuya.com) e o app Smart Life, para as tomadas e lâmpadas Smart Life.

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
│   ├── DispositivoService.java     aparelhos Smart Life por família e cômodo (tabela dispositivos)
│   ├── TuyaClient.java             cliente da API da Tuya (assinatura, token, comandos, estado)
│   ├── MiioClient.java             protocolo miIO (rede local)
│   └── HomeAssistantService.java   integração com o Home Assistant
└── html\
    ├── login2.html, cadastro.html, inicio.html, dispositivos.html
    ├── sala.html, cozinha.html, quarto.html, banheiro.html
    ├── css\                style.css e img\
    └── script\
        ├── script.js       tela inicial (chama /api/spotify/* e /api/xiaomi/*)
        ├── login.js        tela de login
        ├── cadastro.js     tela de cadastro
        ├── dispositivos.js página Dispositivos (lista, liga/desliga, cadastro, cômodo)
        ├── gastos.js       caixas de gastos dos cômodos e da tela inicial
        └── timer.js        timers da cozinha e do banheiro
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

# Tuya / Smart Life (opcional)
# Endpoint do data center do seu projeto: openapi.tuyaus.com (Western America, comum no Brasil),
# openapi-ueaz.tuyaus.com, openapi.tuyaeu.com, openapi.tuyain.com ou openapi.tuyacn.com
tuya.endpoint=https://openapi.tuyaus.com
tuya.client.id=
tuya.client.secret=
tuya.uid=
tuya.atualizar.segundos=10

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

- `tuya.uid` é opcional: com ele, o botão "+ Adicionar aparelho" lista os aparelhos da sua conta Smart Life para escolher. Sem ele, você cola o Device ID.
- `tuya.atualizar.segundos` define de quantos em quantos segundos as páginas atualizam estado e consumo (mínimo 2, padrão 10). Menos segundos deixa o consumo mais "ao vivo", mas gasta mais chamadas da cota da Tuya.
- Sem as chaves do Spotify, o player fica desativado. Sem `ha.*` e sem `xiaomi.*`, a lista de aparelhos Xiaomi fica vazia. Sem `tuya.client.*`, a página Dispositivos avisa que a Tuya não está configurada.
- O exemplo `xiaomi.luz.*` usa IP e token fictícios, só para mostrar o formato. Troque pelos seus ou apague o bloco.
- O `config.properties` guarda senhas e tokens. Ele nunca é entregue pelo servidor web, mas não o envie para repositórios públicos.

## MySQL

Não é preciso criar nada à mão: ao iniciar, o servidor cria o banco `enerza` e as tabelas (`usuarios`, `sessoes` e a tabela de famílias), além da tabela `dispositivos` dos aparelhos Smart Life, se ainda não existirem. Se a tabela `dispositivos` já existir de uma versão anterior sem a coluna `comodo`, a coluna é acrescentada sozinha (os aparelhos antigos ficam como "sala"). O usuário do MySQL precisa de permissão para criar bancos e tabelas.

Opcionalmente, crie um usuário só para o projeto (mais seguro que usar o `root`):

```sql
CREATE DATABASE IF NOT EXISTS enerza CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'enerza'@'localhost' IDENTIFIED BY 'troque_esta_senha';
GRANT ALL PRIVILEGES ON enerza.* TO 'enerza'@'localhost';
FLUSH PRIVILEGES;
```

Se a conexão falhar, o servidor para na inicialização com a mensagem `ERRO MySQL: ...`.

A tabela `dispositivos` guarda, por família: nome, ícone, cômodo, o Device ID da Tuya, o código do interruptor (detectado automaticamente) e a potência em watts. Para conferir:

```sql
SELECT id, familia_id, nome, comodo, tuya_id, potencia_w FROM enerza.dispositivos;
```

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

## Aparelhos Smart Life (Tuya)

Tomadas e lâmpadas genéricas que se conectam pelo app **Smart Life** usam a nuvem da Tuya. O Enerzā chama a API da Tuya pelo servidor Java (o Client ID e o Secret nunca chegam ao navegador). Como o controle passa pela nuvem, o aparelho só precisa estar online no Wi-Fi dele, não importa em qual rede o servidor está.

### Configuração (uma vez só)

1. Crie uma conta de desenvolvedor em https://platform.tuya.com.
2. Vá em **Cloud → Development → Create Cloud Project** com método **Smart Home**. Escolha o data center da sua conta Smart Life (no Brasil, normalmente **Western America**, endpoint `https://openapi.tuyaus.com`).
3. Na aba **Service API** do projeto, autorize o **IoT Core** (gratuito). Sem isso aparece o erro `28841001`.
4. Copie o **Access ID** (`tuya.client.id`) e o **Access Secret** (`tuya.client.secret`) da visão geral do projeto.
5. Em **Devices → Link Tuya App Account → Add App Account**, escaneie o QR code pelo app Smart Life (aba **Eu**, ícone de scan no canto superior direito). Seus aparelhos passam a aparecer no painel.
6. Opcional: copie o **UID** da conta vinculada para `tuya.uid`.
7. Reinicie o servidor.

### Adicionar um aparelho

1. Entre como **administrador** e abra a página **Dispositivos**.
2. Clique em **+ Adicionar aparelho**. Escolha o aparelho na lista (se você configurou `tuya.uid`) ou cole o **Device ID** (painel da Tuya → Devices → All Devices).
3. Informe o nome, o ícone e o **cômodo**.
4. O campo **Watts** é opcional. Se a tomada mede o consumo, o valor real aparece sozinho e esse campo é ignorado. Se ela só liga e desliga, o valor digitado é usado como estimativa quando o aparelho está ligado.
5. Salvar. O servidor confirma com a Tuya que enxerga o aparelho e descobre sozinho qual é o interruptor dele.

O administrador pode trocar o cômodo de um aparelho depois pelo seletor no card, e remover pelo ✕.

### Consumo e gastos

- Aparelho ligado com tomada que mede: usa o consumo real, marcado como "medido".
- Aparelho ligado sem medição: usa os watts do cadastro, marcado como "estimado".
- Aparelho desligado: 0 W.
- As páginas Sala, Cozinha, Quarto e Banheiro mostram os aparelhos daquele cômodo, do que mais gasta para o que menos gasta, com o total do cômodo. A tela inicial mostra os 5 que mais gastam e o total da casa.
- O valor mostrado é o último que a tomada enviou à Tuya, então pode ter alguns segundos de atraso. Para algo realmente instantâneo seria preciso o protocolo local da Tuya, que não está implementado.

### Cota da Tuya

A assinatura gratuita do IoT Core é um período de teste com limite de chamadas por mês, e precisa ser renovada de graça em **Cloud → My Services → Extend Trial Period** (a extensão vale por até 6 meses). Confira o limite real do seu plano no painel da Tuya. Cada atualização de estado e cada liga/desliga conta como uma chamada. Para economizar:

- as páginas só atualizam sozinhas com a aba visível;
- o servidor reaproveita a resposta de um aparelho por alguns segundos, mesmo com várias abas abertas;
- aumente `tuya.atualizar.segundos` se a cota apertar.

## Timers (cozinha e banheiro)

Nas páginas **Cozinha** e **Banheiro**, digite minutos e segundos (ou clique em um atalho) e clique em **Iniciar**. Dá para **Pausar**, **Continuar** e **Zerar**.

Quando o tempo acaba, toca um aviso sonoro (três bipes) e a tela inteira pisca em vermelho com a frase do cômodo, até você clicar em **Parar alarme** ou apertar Esc.

- O timer guarda a hora de término no navegador. Se você trocar de página, ele continua contando. Se o tempo acabar com o site fechado, o alarme aparece quando você abrir a página.
- Os navegadores só liberam som depois de um clique na página. O clique em **Iniciar** libera o áudio. Se você recarregar a página com o timer rodando e não clicar em nada, o som pode não tocar, mas a tela vermelha aparece.
- O aviso só dispara com a página aberta (aba em segundo plano funciona).
- As frases e os atalhos ficam nos atributos `data-msg` e `data-presets` do timer em `cozinha.html` e `banheiro.html`.

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
| `GET /api/dispositivos/lista` | Aparelhos Smart Life da família, com estado, cômodo e consumo | sim |
| `GET /api/dispositivos/descobrir` | Aparelhos da conta Smart Life ainda não cadastrados (admin, exige `tuya.uid`) | sim |
| `POST /api/dispositivos/adicionar` | Cadastra um aparelho: `nome`, `icone`, `comodo`, `tuyaId`, `potencia` (admin) | sim |
| `POST /api/dispositivos/editar` | Troca o cômodo: `id`, `comodo` (admin) | sim |
| `POST /api/dispositivos/remover` | Remove um aparelho: `id` (admin) | sim |
| `POST /api/dispositivos/ligar` | Liga ou desliga: `id`, `valor=true\|false` | sim |

## Segurança

- Senhas guardadas só como hash (PBKDF2-HMAC-SHA256, 120 mil iterações, sal individual).
- No banco fica apenas o hash do token de sessão.
- O Client ID e o Secret da Tuya ficam só no servidor (`config.properties`); o navegador só fala com `/api/dispositivos/*`. Só o administrador da família adiciona, remove ou altera aparelhos, e cada família só enxerga os próprios.
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
| Dispositivos: `IoT Core service is not subscribed` (28841001) | No projeto da Tuya, aba **Service API**, autorize o **IoT Core**. |
| Dispositivos: assinatura recusada (1004) | Confira `tuya.client.id`, `tuya.client.secret` e se o `tuya.endpoint` é o do data center do projeto. |
| Dispositivos: sem permissão para este aparelho (1106) | Vincule a conta Smart Life ao projeto (Devices → Link Tuya App Account) e confira o data center. |
| Dispositivos: assinatura IoT Core expirada | Renove em **Cloud → My Services → Extend Trial Period** na plataforma da Tuya. |
| Dispositivos: "offline" no card | A tomada está sem energia ou sem Wi-Fi. Confira se ela aparece online no app Smart Life. |
| Dispositivos: "Tuya não configurada" | Preencha `tuya.client.id` e `tuya.client.secret` no `config.properties` e reinicie. |
| Timer sem som | Clique em **Iniciar** (o clique libera o áudio) e confira o volume. A tela vermelha aparece de qualquer jeito. |
| Aparelho Xiaomi com "⚠" | Confira IP e token (miIO) ou `ha.url` e `ha.token` (Home Assistant). |