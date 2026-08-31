# Arquitetura

## Visão geral

```
7 - PESQUISA MERCADO/
├── backend/         ← backend: API REST pura, Spring Boot (não serve HTML)
├── frontend/        ← frontend: HTML/CSS/JS estático, servidor próprio (porta 5500)
├── memory/          ← esta pasta: base de conhecimento pro desenvolvimento contínuo
└── .claude/agents/  ← agentes especialistas (Backend, Frontend, QA)
```

Frontend e backend rodam em **processos e portas separados** (5500 e 8080) e conversam
via HTTP/CORS. Isso foi uma escolha deliberada para permitir deploy independente (ex:
frontend na Vercel, backend num host com processo persistente — ver DECISIONS.md).

## Backend (`backend/`) — MVC

- `model/` — entidades JPA: `User` (username, password, role, city), `ChatMessage`, `CitySource`,
  `SiteCache` (cache de scraping RAW por site, texto/JSON bruto), `ProdutoCache` (cache
  ESTRUTURADO — um produto/preço por linha, ver "Cache de scraping opcional" abaixo)
- `repository/` — Spring Data JPA repositories
- `service/`:
  - `AuthService` — login/registro, geração de JWT
  - `ChatService` — orquestra: salva mensagem, resolve cidade do usuário → busca sites (cache
    se habilitado, senão ao vivo) → monta contexto → chama IA
  - `AiService` — chama a API de chat da DeepSeek; também extrai o termo de busca de produto de uma pergunta (chamada secundária, rápida)
  - `PageFetcherService` — abre sites num Chromium headless (Playwright) e lê o texto renderizado;
    também é o ponto único de guarda de conteúdo anti-bot (ver abaixo)
  - `CitySourceService` — CRUD dos sites por cidade (admin) + configurações/consulta de cache
  - `CacheWarmerService` — popula/revalida o `site_cache` (raw) E o `tb_produto_cache`
    (estruturado, via `ProdutoParserService`) de um `CitySource` com cache habilitado, buscando
    ao vivo uma lista fixa de termos monitorados, um de cada vez com pausa entre eles; job
    `@Scheduled` a cada 30 min + acionável manualmente pelo admin
  - `ProdutoParserService` — extrai produto+preço do conteúdo já obtido (sem fetch novo): JSON
    direto pro Atacadão, heurística por regex pro Rondon/Pão de Açúcar (ver DECISIONS.md)
  - `EmbeddingService` — gera embeddings de frase (384 dims, modelo
    `Xenova/paraphrase-multilingual-MiniLM-L12-v2` em ONNX) usados pelo cache semântico; baixa o
    modelo sob demanda pra `backend/data/models/` (ver DECISIONS.md item 19)
  - `SemanticCacheService` — cache semântico na frente de `AiService.classifyProductQuery`:
    compara o embedding da mensagem nova contra mensagens já classificadas (`tb_semantic_query_cache`)
    e reusa o resultado se a similaridade de cosseno bater o threshold, pulando a chamada à IA
    (ver DECISIONS.md item 19)
- `controller/` — `AuthController`, `ChatController`, `SettingsController` (sites, admin-only),
  `CityController` (lista cidades, qualquer logado), `UserController` (perfil/cidade do usuário)
- `security/` — `JwtUtil`, `JwtAuthenticationFilter`, `CustomUserDetailsService`
- `config/` — `SecurityConfig` (CORS + regras de acesso), `DataInitializer` (seed do admin +
  seed do cache habilitado no Pão de Açúcar)
- `db/changelog/` — migrations Liquibase (8 changesets: users, chat_messages, city_sources,
  +city em users, +cache_enabled/cache_ttl_hours em city_sources, tabela site_cache, tabela
  tb_produto_cache, tabela tb_semantic_query_cache)

### Guarda de conteúdo anti-bot (proteção geral, todos os sites)

`PageFetcherService.isSuspiciousBotCheckContent(text)` roda logo depois de ler o texto
renderizado de QUALQUER site (não só os com cache habilitado) e detecta se o conteúdo obtido é,
na verdade, uma página de verificação anti-bot/CAPTCHA em vez do catálogo real — por sinais
textuais (`"não sou um robô"`, `"confirme seu acesso"`, `"captcha"` etc., case-insensitive) ou
por ser suspeitosamente curto (< 300 caracteres). Quando detecta, trata como falha de fetch
(mesmo prefixo `FETCH_ERROR_PREFIX` usado pra erro de rede, com um marcador extra
`BOT_CHECK_MARKER` pra quem recebe poder logar o alerta específico) — **nunca** escreve isso em
`site_cache` nem em `tb_produto_cache`. Ver o limite absoluto documentado em DECISIONS.md: essa
guarda só detecta e descarta, nunca tenta resolver/burlar o desafio.

### Cache de scraping opcional por `CitySource`

Cada `CitySource` tem `cacheEnabled` (default `false`, hoje ligado em Rondon, Atacadão e Pão de
Açúcar) e `cacheTtlHours` (default `6`). Quando habilitado, `ChatService` procura, nessa ordem:

1. `tb_produto_cache` — cache ESTRUTURADO (produto/preço já parseados, um por linha) com
   `status=OK` dentro do TTL pro termo. Se achar, formata uma lista limpa
   `"Nome do produto — R$ preço"` pra IA (em vez do texto/JSON bruto da página) e **nem abre o
   Playwright**.
2. Se não achar nada estruturado, cai pro `site_cache` — cache RAW (texto/JSON bruto), mesma
   lógica de TTL/status.
3. Se nenhum dos dois tiver entrada válida (ausente, vencida ou `FALHOU`), cai no fluxo normal
   de busca ao vivo — e se o fetch funcionar e o cache estiver habilitado, o resultado já
   alimenta os DOIS caches (oportunista, sem esperar o próximo ciclo do job).

Sources sem cache habilitado (Amigão, Assaí) continuam se comportando exatamente como antes —
fallback é 100% transparente.

`CacheWarmerService.warmCache(source)` faz o warm-up de uma lista fixa de termos monitorados
(arroz, feijão, leite etc.), termo por termo (não em lote), com uma pausa de ~4s entre cada um
pro mesmo source — reaproveitando o mesmo `PageFetcherService`/thread única do Playwright (nunca
cria executor paralelo). Pra cada termo, grava o `site_cache` raw e chama `ProdutoParserService`
pra extrair produto+preço estruturado e gravar em `tb_produto_cache` (se o parse não encontrar
nada, o termo não vira erro — só fica sem linha estruturada, com o raw servindo de fallback). Um
`@Scheduled` a cada 30 min revalida sources com cache vencido; `POST
/api/settings/sources/{id}/cache/refresh` (admin) força isso na hora, ignorando TTL.

### Fluxo de uma pergunta com contexto de cidade

1. `ChatController.send` recebe a mensagem, chama `ChatService.sendMessage`
2. `ChatService` salva a mensagem do usuário, busca `user.getCity()`
3. `ChatService.resolveSiteContexts` chama `SemanticCacheService.classify(mensagem)` (cache
   semântico por embedding na frente de `AiService.classifyProductQuery` — ver DECISIONS.md item
   19) pra decidir se a mensagem é pedido de preço/produto; só então, se a cidade tem
   `CitySource`s cadastrados, segue pro fluxo de busca por `{termo}` na URL
4. Monta as URLs finais (`ChatService.resolveUrl`, substitui `{termo}` com URL-encoding)
5. `PageFetcherService.fetchAll` abre cada site (sequencial, thread dedicada do Playwright)
6. Sites que falharam (erro de rede) são filtrados — não viram contexto pra IA
7. `AiService.generateReply` monta o prompt final (mensagem + trechos dos sites) e chama a DeepSeek
8. Resposta salva como `ChatMessage` do assistente, retornada ao frontend

## Frontend (`frontend/`) — MVC

- `index.html` — chat público (visitante ou logado); modal de cadastro obrigatório ao enviar 1ª msg
- `login.html` — login pra quem já tem conta
- `settings.html` — sites por cidade (**admin only**)
- `mural.html` — changelog/versão do sistema (**admin only**)
- `js/model/apiModel.js` — toda comunicação HTTP com o backend + sessão (localStorage)
- `js/view/*.js` — manipulação de DOM pura, sem lógica de negócio
- `js/controller/*.js` — liga view ↔ model, cada página tem seu controller

### Sessão / autenticação no frontend

- Token JWT + username + role guardados em `localStorage` (`chatbot_token`, `chatbot_user`, `chatbot_role`)
- `ApiModel.isAdmin()` checa a role local — usado só pra decidir o que **mostrar** na UI
- A autorização de verdade sempre é reforçada no backend (`SecurityConfig`,
  `hasRole("ADMIN")`) — o frontend nunca é a única barreira

## Banco de dados

H2 em arquivo (`backend/data/chatbotdb.mv.db`) — **só serve para desenvolvimento**.
Ver DECISIONS.md e KNOWN_ISSUES.md sobre troca pra Postgres em produção.
