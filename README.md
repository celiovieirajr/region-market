# ChatBot IA

Sistema web MVC: Spring Boot (backend) + HTML/CSS/JS/Tailwind (frontend). Login JWT, chat separado por usuário, opção de limpar contexto.

## Fluxo estratégico: chat público + cadastro obrigatório
`index.html` (raiz do site, `/`) é o **chat público** — qualquer visitante pode digitar sem estar
logado. Ao clicar em enviar a 1ª mensagem, um modal obriga a criar uma **conta grátis** (usuário +
senha) antes da IA responder; a conta é criada, o login é automático (JWT) e a mensagem pendente é
enviada na sequência. Quem já tem conta pode ir direto em "Já tenho conta" → `/login.html`.

## Stack
- Backend (pasta `backend/`): Java 21, Spring Boot 3.3, Maven, Spring Security + JWT (jjwt), Spring Data JPA, Liquibase, H2 (arquivo `data/chatbotdb`). API pura, não serve HTML.
- Frontend (pasta `frontend/`, **fora** de `backend/`): HTML/CSS/JS puro + Tailwind (CDN), servido por um servidor estático próprio, organizado em Model (`js/model`), View (`js/view`), Controller (`js/controller`).

Frontend e backend rodam em **servidores e portas separados** (backend `:8080`, frontend `:5500`)
e conversam via HTTP/CORS — `SecurityConfig` já libera CORS para qualquer origem com credenciais.

## Rodar (backend + frontend em dois terminais)

**Terminal 1 — backend** (dentro de `backend/`):

Na primeira vez, instale o navegador do Playwright (usado para ler sites com preço via JS —
uma vez só, baixa o Chromium ~140MB):

```bash
cd backend
mvn exec:java@install-playwright
```

Depois, toda vez que for rodar:

```bash
cd backend
mvn spring-boot:run
```

Backend em `http://localhost:8080`.

**Terminal 2 — frontend** (dentro de `frontend/`, requer Node.js instalado):

```bash
cd frontend
npm start
```

Frontend em `http://localhost:5500` — abra `http://localhost:5500/index.html` no navegador.

Se mudar a porta/host do backend, ajuste `BASE_URL` em
`frontend/js/model/apiModel.js` (hoje aponta pra `http://localhost:8080/api`).

## Login do admin
Usuário e senha do admin seed (criado no boot, se ainda não existir) vêm de
`ADMIN_USERNAME_MARKET` e `ADMIN_PASSWORD_MARKET` — não há mais credencial fixa no código. Ver
"Variáveis de ambiente obrigatórias" abaixo.

## Estrutura backend (MVC)
- `model/` — entidades JPA (`User`, `ChatMessage`)
- `repository/` — Spring Data repositories
- `service/` — regras de negócio (`AuthService`, `ChatService`, `AiService`)
- `controller/` — endpoints REST (`AuthController`, `ChatController`)
- `security/` — JWT (`JwtUtil`, `JwtAuthenticationFilter`, `CustomUserDetailsService`)
- `config/` — `SecurityConfig`, `DataInitializer` (cria usuário admin no boot)
- `db/changelog/` — migrations Liquibase

## Páginas (servidas pelo frontend, `http://localhost:5500`)
- `/index.html` (`/`) — chat público (visitante ou logado)
- `/login.html` — login para quem já tem conta
- `/settings.html` — sites por cidade (**somente ADMIN**, botão ⚙️ só aparece pra role ADMIN)

## Roles
- Conta criada via cadastro grátis (`/api/auth/register`) sempre nasce com role `USER`.
- Só `admin` (role `ADMIN`, seed no boot) enxerga o botão ⚙️ Configurações e consegue acessar
  `/api/settings/**` — reforçado em duas camadas: `SecurityConfig` (`hasRole("ADMIN")` na API) e
  no front (`ApiModel.isAdmin()` esconde o botão e `settingsController.js` redireciona quem tentar
  acessar `/settings.html` sem ser admin).

## Tema claro/escuro
Botão 🌙/☀️ no header de todas as páginas (chat, login, configurações), disponível para
visitantes e logados. Preferência salva em `localStorage` (`chatbot_theme`), aplicada via
`js/controller/themeController.js` + Tailwind `darkMode: 'class'`. Sem preferência salva, segue
`prefers-color-scheme` do sistema.

## Endpoints
- `POST /api/auth/login` — `{ username, password }` → `{ token, username, role }`
- `POST /api/auth/register` — `{ username, password }` → cria conta grátis e já retorna `{ token, username, role }` (auto-login)
- `GET /api/chat/history` — histórico do usuário logado (JWT obrigatório)
- `POST /api/chat/send` — `{ message }` → resposta da IA
- `DELETE /api/chat/clear` — limpa o contexto/histórico do usuário logado
- `GET /api/settings/sources` — lista os sites cadastrados (**ADMIN**)
- `POST /api/settings/sources` — `{ city, label, url }` → cadastra um site para uma cidade (**ADMIN**)
- `DELETE /api/settings/sources/{id}` — remove um site (**ADMIN**)
- `GET /api/cities` — lista as cidades com sites cadastrados (qualquer usuário logado, pro seletor)
- `GET /api/user/me` — perfil do usuário logado `{ username, role, city }`
- `PUT /api/user/city` — `{ city }` → define a cidade do usuário logado

Cada usuário só acessa suas próprias mensagens (filtradas por `user_id`). Sites por cidade são um
catálogo global mantido pelo admin — qualquer usuário que selecionar aquela cidade usa os mesmos sites.

## IA e sites por cidade (⚙️ Configurações, só ADMIN)
O admin cadastra sites por cidade (mercado, farmácia, posto etc.) em `/settings.html`, ex:
`Araçatuba` → `https://lojaonline.supermercadosrondon.com.br/p/busca/fraldinha`.

Cada usuário escolhe **sua cidade** no seletor do header do chat (`GET/PUT /api/user/city`) — não
precisa mais digitar a cidade na mensagem. Ao mandar uma mensagem, o `ChatService` busca os sites
cadastrados pra cidade do perfil do usuário e passa o conteúdo como contexto pra IA.

`PageFetcherService` usa **Playwright (Chromium headless)** para abrir cada site num navegador de
verdade, esperar o JavaScript rodar e ler o texto já renderizado — funciona em sites
React/Angular/Vue que só mostram preço depois de uma chamada JS (o que um HTTP GET simples não
capturaria). Todo o trabalho roda numa thread dedicada (Playwright não é thread-safe entre
threads); chamadas concorrentes esperam na fila. Sem cidade selecionada no perfil, a IA responde
normalmente, sem contexto de site.

`AiService` chama a API de chat da **DeepSeek** (compatível com o formato OpenAI). Configuração em
`application.yml` (`app.ai.*`) — a API key vem **obrigatoriamente** da variável de ambiente
`DEEPSEEK_API_KEY` (sem valor padrão no arquivo; o backend não sobe sem ela definida).

## Variáveis de ambiente obrigatórias

O backend **não inicia** sem essas cinco variáveis (sem fallback hardcoded no `application.yml`,
de propósito — evita vazar segredo se o repositório for público no GitHub):

```bash
export DEEPSEEK_API_KEY=sua-chave-deepseek-aqui
export TOKEN_MASTER_MARKET=uma-chave-secreta-longa-e-aleatoria-para-assinar-jwt
export H2_DB_PASSWORD_MARKET=uma-senha-para-o-banco-h2
export ADMIN_USERNAME_MARKET=escolha-um-usuario-admin
export ADMIN_PASSWORD_MARKET=uma-senha-forte-para-o-admin
```

- `DEEPSEEK_API_KEY` — chave da API DeepSeek (`app.ai.api-key`).
- `TOKEN_MASTER_MARKET` — segredo usado por `JwtUtil` pra assinar/validar os tokens JWT
  (`app.jwt.secret`). Gere um valor forte e aleatório (ex: `openssl rand -base64 48`) — **nunca
  reuse** o valor de exemplo que já esteve neste repositório, ele deve ser considerado
  comprometido.
- `H2_DB_PASSWORD_MARKET` — senha da conexão com o banco H2 (`spring.datasource.password`),
  usuário fixo `sa`. Antes era `""` (vazio) — defina algo não trivial.
- `ADMIN_USERNAME_MARKET` / `ADMIN_PASSWORD_MARKET` — credenciais do usuário ADMIN criado por
  `DataInitializer` no boot (só roda se esse usuário ainda não existir). Antes era hardcoded
  `admin`/`admin123` e documentado neste README — **considere essa combinação comprometida** se
  o backend já rodou com o default antigo; troque a senha desse usuário no banco ou apague-o e
  deixe o seed recriar com a env var nova. **Use usuário e senha diferentes entre si** — o
  `username` vai em claro (não criptografado, só assinado) no payload de todo JWT emitido; se a
  senha for igual ao usuário, um token vazado ou interceptado já entrega a credencial completa.

No Windows (PowerShell), pra sessão atual:

```powershell
$env:DEEPSEEK_API_KEY = "sua-chave-deepseek-aqui"
$env:TOKEN_MASTER_MARKET = "uma-chave-secreta-longa-e-aleatoria-para-assinar-jwt"
$env:H2_DB_PASSWORD_MARKET = "uma-senha-para-o-banco-h2"
$env:ADMIN_USERNAME_MARKET = "escolha-um-usuario-admin"
$env:ADMIN_PASSWORD_MARKET = "uma-senha-forte-para-o-admin"
```

Pra persistir entre sessões, defina como variável de ambiente do usuário/sistema no Windows, ou
use um `.env`/gerenciador de segredos do seu ambiente de deploy (Railway, Render, Fly.io etc.) —
**nunca** commite essas chaves de volta no `application.yml`.
