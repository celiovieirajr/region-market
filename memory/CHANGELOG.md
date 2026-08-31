# Changelog

Histórico cronológico do que foi construído — fonte do Mural (`/mural.html`, admin-only).
Ao adicionar uma feature nova, registre aqui com data, o que mudou, e por quê.

## v1.2.0 — 2026-08-28 — Correção de transação + só pesquisar quando for produto (cache semântico)

**Escopo:** dois problemas achados em uso real (log de produção): chat quebrando com
`UnexpectedRollbackException` numa mensagem simples ("Olá, boa noite"), e a mesma
mensagem disparando scraping em TODOS os sites da cidade mesmo sem ser pedido de
produto/preço (~80s de scraping desnecessário, termo de busca literal "olá").

**Correção de transação (`ChatService`, `CacheWarmerService`):**
- `sendMessage` não fica mais preso numa única transação de banco cobrindo chamada à IA
  e scraping via Playwright (I/O externo de dezenas de segundos) — grava mensagem do
  usuário e resposta da IA em transações curtas separadas, via `TransactionTemplate`
  (evita também um bug de auto-invocação que `@Transactional` num método privado teria).
- `CacheWarmerService.upsertCache`/`upsertProdutoCache` viraram `REQUIRES_NEW` — uma
  falha ali não contamina mais a transação do chat inteiro com rollback-only.
- Logs de erro nesse caminho agora incluem stacktrace real, não só `.toString()`.

**Gate de intenção antes do scraping:**
- `AiService.extractSearchTerm` (sempre devolvia um termo, mesmo pra saudação) foi
  substituído por `AiService.classifyProductQuery`, que decide em uma única chamada JSON
  `{"isProdutoQuery": bool, "termo": string|null}` se a mensagem é de fato um pedido de
  preço/produto. `ChatService.resolveSiteContexts` roda esse check **antes** de tocar em
  qualquer `CitySource` — mensagem que não é sobre produto pula scraping (estático e
  dinâmico) por completo.

**Cache semântico (evita repetir a chamada à IA pra perguntas parecidas):**
- Nova tabela `tb_semantic_query_cache` (mensagem, embedding, resultado, hits).
- `EmbeddingService`: gera embedding local (384 dims) via modelo
  `Xenova/paraphrase-multilingual-MiniLM-L12-v2` (ONNX, PT-BR, ~120MB, baixado sob
  demanda pra `backend/data/models/` — download único, idempotente, mesmo conceito do
  install do Playwright já existente).
- `SemanticCacheService`: compara a mensagem nova por similaridade de cosseno contra
  mensagens já classificadas; bate o threshold (`app.semantic-cache.threshold`, calibrado
  em `0.70` após teste real) → reusa resultado, pula a chamada à IA; senão, chama
  `AiService.classifyProductQuery` normal e grava pro futuro.
- Fail-safe em toda a cadeia: qualquer falha (rede, parse, embedding) degrada pro
  comportamento anterior (chama a IA normalmente) — nunca trava o chat.

**Arquivos-chave:** `backend/.../service/{ChatService,CacheWarmerService,AiService,
EmbeddingService,SemanticCacheService}.java`, `model/SemanticQueryCache.java`,
`repository/SemanticQueryCacheRepository.java`,
`db/changelog/changes/007-create-semantic-query-cache.yaml`, `application.yml`
(`app.semantic-cache.threshold`), `pom.xml` (onnxruntime + DJL tokenizers).

**Validação:** compilação limpa (`mvn compile`) + teste manual do pipeline de embedding
(download do modelo, tokenização, inferência ONNX, similaridade de cosseno). Teste
end-to-end do chat (reiniciar backend, mandar mensagem de produto e depois uma parecida)
ainda pendente de fazer.

## v1.1.0 — 2026-08-27 — Cache inteligente de preços

**Escopo:** cache opcional por mercado (`CitySource`), configurável em Configurações
(liga/desliga, TTL, refresh manual), com fallback transparente pra busca em tempo real
quando o cache não existe/está vencido/falhou. Objetivo: reduzir a latência de ~20-30s
do Pão de Açúcar (o mercado mais lento, ver DECISIONS.md item 2) sem abrir mão da busca
ao vivo como garantia.

**O que mudou:**

- `site_cache` (raw): conteúdo bruto da página cacheado por `(city_source_id, termo)`.
- `tb_produto_cache` (estruturado): produto/preço/categoria/loja extraídos do conteúdo
  bruto por parser dedicado por site — JSON direto pro Atacadão, regex heurística pra
  Rondon e Pão de Açúcar. `ChatService` prefere o dado estruturado quando disponível.
- `CacheWarmerService`: job `@Scheduled` a cada 30min revalida mercados com cache vencido,
  buscando 10 termos monitorados (arroz, feijão, leite, óleo de soja, açúcar, café, carne,
  frango, macarrão, papel higiênico), com ~4s de pausa entre cada termo pro mesmo site.
- Guarda anti-bot (`PageFetcherService.isSuspiciousBotCheckContent`): qualquer fetch
  (cache ou ao vivo) que pareça página de verificação humana ou seja suspeitosamente curto
  é descartado — nunca salvo em cache nem usado como contexto pra IA.
- Endpoints novos: `PUT /api/settings/sources/{id}` (liga/desliga cache, ajusta TTL),
  `POST /api/settings/sources/{id}/cache/refresh` (refresh manual, admin).
- UI (`settings.html`): toggle de cache, campo TTL, botão "Atualizar agora", status de
  última atualização, por mercado.
- Habilitado em: Supermercados Rondon, Atacadão, Pão de Açúcar. Amigão e Assaí ficam de
  fora (sem busca dinâmica real).
- **Bug corrigido (achado em QA, não relacionado ao cache):** login com senha errada não
  mostrava erro e redirecionava silenciosamente pro chat visitante — 401 de `/auth/*`
  agora tratado separado do interceptor de "sessão expirada" (`apiModel.js`).

**Limite absoluto respeitado no desenvolvimento:** em nenhum momento foi implementado ou
tentado código pra resolver, clicar ou contornar o desafio "não sou um robô"/CAPTCHA do
Pão de Açúcar. Quando o bloqueio aconteceu durante testes, o comportamento correto
ocorreu: nada foi cacheado, termo ficou `FALHOU`, chat caiu no fetch ao vivo. Regra
permanente do projeto, não pontual desta versão — ver DECISIONS.md.

**Limitação conhecida:** Pão de Açúcar pode sofrer bloqueio anti-bot intermitente sob
rajada — dependente do lado do site, não 100% eliminável só com espaçamento. Quando
acontece, fica sem cache naquela rodada (sem prejuízo, busca ao vivo cobre) até o próximo
ciclo do job ou refresh manual funcionar. Ver SITE_INTEGRATIONS.md.

**Validação:** QA independente, 2 rodadas (implementação + reteste pós-correção de um bug
onde conteúdo cacheado incorretamente escapou da guarda), casos `TC-044` a `TC-050` em
`teste.xlsx` (raiz do projeto) — veredito **APROVADO**.

**Arquivos-chave:** `backend/.../service/{ChatService,CacheWarmerService,PageFetcherService,ProdutoParserService}.java`,
`model/{CitySource,SiteCache,ProdutoCache}.java`, `repository/ProdutoCacheRepository.java`,
`db/changelog/changes/{005-add-site-cache,006-create-produto-cache}.yaml`,
`controller/SettingsController.java`, `frontend/js/model/apiModel.js`,
`frontend/js/view/settingsView.js`, `frontend/js/controller/settingsController.js`,
`frontend/mural.html`.

## v1.0.0 — Lançamento inicial

Chat com IA (DeepSeek), login/cadastro com JWT e cadastro grátis auto-serviço, roles
ADMIN/USER, pesquisa de preços em tempo real em mercados de Araçatuba/SP via Playwright
(Supermercados Rondon, Atacadão, Pão de Açúcar, Amigão), tema claro/escuro, painéis de
administração (Configurações, Mural). Detalhes completos em ARCHITECTURE.md e
DECISIONS.md.
