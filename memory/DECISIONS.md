# Decisões técnicas — o porquê

## 1. Playwright em vez de Jsoup/HTTP puro para ler sites

**Decisão:** `PageFetcherService` usa Playwright (Chromium headless real), não Jsoup.

**Por quê:** os sites de mercado (Rondon, Atacadão, Pão de Açúcar) são SPAs que só
mostram preço depois do JavaScript rodar. Um HTTP GET simples (Jsoup) baixa o HTML
antes da hidratação — testamos e o preço nunca aparecia, só a estrutura da página.

**Consequência:** cada consulta agora demora ~20-30s (abrir navegador + renderizar 4
sites), contra <1s de um GET simples. Rodou numa thread dedicada porque Playwright não
é thread-safe entre threads (ver item 3).

## 2. Espera adaptativa em vez de tempo fixo

**Tentativas, em ordem:**
1. `page.waitForTimeout(4000)` fixo — funcionava às vezes, falhava quando o site
   demorava mais (Pão de Açúcar variou de 4s a 20s+ dependendo da sessão).
2. `page.waitForSelector("text=R$", timeout=30s)` — melhor, mas **quebrado para
   sites de API JSON** (Atacadão): preço vem como número no JSON (`"price":9.9`), nunca
   tem a string literal "R$", então sempre estourava o timeout inteiro à toa.
3. **Solução atual:** polling do tamanho do `document.body.innerText` a cada 1s, para
   assim que ele parar de crescer por 2 checagens seguidas (ou bater o teto de 25s).
   Funciona igual bem pra HTML renderizado e pra JSON puro.

**Por quê demora tanto às vezes:** Pão de Açúcar guarda a loja/região padrão num
`localStorage` (Redux persist). Numa sessão nova (sem cookies — sempre o caso do
Playwright, que cria um `BrowserContext` novo a cada fetch), o site precisa decidir
essa loja padrão antes de mostrar produtos, e essa decisão tem latência variável.

## 3. Playwright numa thread dedicada (`ExecutorService` de 1 thread)

**Por quê:** a documentação do Playwright é clara que seus objetos não podem ser usados
de threads diferentes da que os criou. Como o Spring processa requisições em múltiplas
threads (pool do Tomcat), toda chamada ao Playwright precisa ser enfileirada numa única
thread dedicada (`playwright-fetcher`). Chamadas concorrentes esperam na fila —
funciona, mas não escala em paralelo (ver KNOWN_ISSUES.md).

## 4. Busca dinâmica por produto via placeholder `{termo}`

**Decisão:** `CitySource.url` pode conter `{termo}`, substituído antes de abrir o site.
O termo é extraído da pergunta do usuário por uma chamada rápida e separada à IA
(`AiService.classifyProductQuery`, ver item 18), não por regex/heurística de palavras —
motivo: regex não generaliza bem pra linguagem natural variada ("qual o preço de",
"quanto custa", "me fala o valor de" etc.), e a IA já está disponível de qualquer forma.

**Encoding:** usamos `URLEncoder.encode(term, UTF-8)` com `+` trocado por `%20` — isso
funciona tanto em segmento de path (`/busca/{termo}`) quanto embutido num JSON de query
string já url-encoded (API GraphQL do Atacadão). Slugs com hífen (`feijao-preto`) foram
abandonados porque quebravam a busca de API que espera o termo literal.

## 5. Cidade fixada no perfil do usuário, não detectada por texto

**Decisão inicial (abandonada):** detectar a cidade mencionada na mensagem via matching
de texto contra os nomes de cidades cadastradas.

**Por quê mudou:** o usuário pediu explicitamente que a cidade seja "setada" no perfil,
não digitada toda vez. Resultado: UX melhor (não precisa repetir a cidade em cada
pergunta) e a lógica do backend ficou mais simples (`user.getCity()` direto, sem
parsing de texto).

## 6. Sites por cidade são um catálogo global (admin), não por usuário

Depois que Configurações virou admin-only, `CitySource` deixou de ser "dados do
usuário que criou" e passou a ser um catálogo compartilhado por cidade — qualquer
usuário que selecionar aquela cidade usa os mesmos sites. A consulta no repositório é
por cidade (`findByCityIgnoreCase`), não mais filtrada por usuário dono.

## 7. IA nunca deve mencionar sites sem informação

O prompt da IA (`AiService.generateReply`) instrui explicitamente a **ignorar
silenciosamente** sites sem o dado pedido — não dizer "tal site não trouxe
informações" ou "não pôde ser acessado". Isso foi pedido explicitamente pelo usuário
depois de ver respostas poluídas com esse tipo de ruído. Complementarmente,
`ChatService` já filtra sites que falharam no fetch (erro de rede) antes mesmo de
chegarem no prompt.

## 8. Resposta da IA sempre separada por loja

O prompt pede explicitamente pra organizar a resposta com um subtítulo por loja (ex:
`**Supermercados Rondon**` seguido da lista de produtos daquela loja), e indicar o
menor preço geral no final.

## 9. Frontend e backend em processos/portas separados

Decisão tomada a pedido do usuário, pensando em escalar pra produção (frontend na
Vercel, backend em host com processo persistente). CORS liberado via
`SecurityConfig.corsConfigurationSource()` com `AllowedOriginPatterns("*")` +
`allowCredentials(true)`.

## 10. Cache de scraping OPCIONAL por `CitySource`, não global

**Decisão:** `cacheEnabled`/`cacheTtlHours` vivem no próprio `CitySource`, ligados por
enquanto só no Pão de Açúcar (mercado mais lento, ~4-20s — ver item 2), não num
mecanismo global de cache pra todos os sites.

**Por quê:** cada site tem uma latência e um perfil de "quão dinâmico é o preço" bem
diferentes (Rondon é rápido e confiável, Atacadão é API JSON rápida, Pão de Açúcar é o
gargalo real). Cache por site permite ligar só onde compensa, sem arriscar servir preço
desatualizado em sites que já respondem rápido. O mecanismo é genérico — qualquer
`CitySource` pode ligar depois via `PUT /api/settings/sources/{id}`.

**TTL configurável + refresh manual:** TTL por source (default 6h) porque a frequência
de variação de preço não é igual em todo mercado. Além do job automático (`@Scheduled`
a cada 30 min, revalida quando o cache mais antigo do source venceu), existe
`POST /api/settings/sources/{id}/cache/refresh` pro admin forçar uma revalidação
imediata (útil logo depois de habilitar o cache pela primeira vez, ou pra debug) — é
síncrono e pode demorar (dezenas de segundos a poucos minutos, um fetch por termo
monitorado), aceitável porque é ação manual e pontual do admin, não parte do fluxo de
chat de um usuário comum.

**Fallback transparente:** cache ausente/vencido/com status `FALHOU` cai direto no
fetch ao vivo de sempre — sources sem cache habilitado (Rondon, Atacadão, Amigão,
Assaí) não mudam de comportamento em nada.

## 11. Warm-up do cache reaproveita a thread única do Playwright, sem paralelizar

`CacheWarmerService` **não** cria nenhum `ExecutorService` novo — todo warm-up (job
agendado, refresh manual do admin, ou upsert oportunista a partir de uma busca ao vivo
do chat) passa pelo mesmo `PageFetcherService.fetchAll`, que já enfileira tudo na thread
dedicada `playwright-fetcher` (ver item 3). Motivo: paralelizar aqui violaria a mesma
restrição de thread-safety do Playwright documentada pro fluxo de chat — não faz
sentido abrir uma exceção só porque é um job em background. Consequência aceita: o
warm-up de um source com 10 termos monitorados é sequencial e pode levar bem mais que
uma consulta normal (que busca só 1 termo por vez) — tudo bem, ele roda fora do caminho
crítico de uma pergunta do usuário.

## 13. Guarda de conteúdo anti-bot — limite absoluto: NUNCA burlar, só detectar e falhar

**O que aconteceu:** numa rodada anterior, `CacheWarmerService` fez o warm-up do Pão de Açúcar
disparando os 10 termos monitorados em rajada (sem pausa nenhuma entre eles, cada um numa
`BrowserContext` nova via `browser.newContext()` — ver item 3, Playwright não reaproveita cookie
entre fetches). O site respondeu com uma página de verificação "não sou um robô" em vez do
catálogo real, e essa página **foi cacheada como se fosse conteúdo válido** (o `upsertCache` não
tinha nenhuma validação de conteúdo, só de erro de rede). Confirmamos manualmente que um acesso
único e espaçado ao mesmo site retorna produto/preço reais sem desafio nenhum — o bloqueio foi
por padrão de rajada, não permanente.

**Decisão:** `PageFetcherService.isSuspiciousBotCheckContent(text)` valida TODO conteúdo obtido
(não só de sources com cache habilitado) antes de devolver como sucesso — procura sinais
textuais (`"não sou um robô"`, `"confirme seu acesso"`, `"captcha"`, `"unusual traffic"` etc.,
case-insensitive) ou conteúdo suspeitosamente curto (< 300 caracteres). Quando detecta, trata
como falha de fetch (mesmo mecanismo de `FETCH_ERROR_PREFIX` já usado pra erro de rede) — nunca
grava isso em `site_cache` nem em `tb_produto_cache`.

**Limite absoluto, sem exceção, pra sempre:** essa guarda só DETECTA e DESCARTA. Em nenhuma
circunstância o código deste projeto deve tentar resolver, clicar ou burlar um desafio de
verificação anti-bot/CAPTCHA de nenhum site — nada de clicar no checkbox via Playwright, nada de
stealth scripts pra esconder `navigator.webdriver`, nada de spoofar fingerprint especificamente
pra enganar detecção de bot. Se um site continuar bloqueando mesmo depois de espaçar as
requisições (item 14) e usar um User-Agent real (já existente, ver SITE_INTEGRATIONS.md), a
resposta correta é: marcar aquele termo/source como `FALHOU`, não cachear, deixar cair no
fallback ao vivo já existente, e documentar a limitação em SITE_INTEGRATIONS.md.

## 14. Espaçamento entre termos no warm-up (`CacheWarmerService`)

**Por quê:** consequência direta do item 13 — 10 requisições em rajada, sem pausa, cada uma
abrindo uma sessão nova sem cookie, é exatamente o padrão que dispara detecção de bot por
comportamento (não é sobre o conteúdo de cada requisição individual, é sobre o RITMO). A solução
não é fingir ser mais "humano" de forma enganosa — é literalmente ir mais devagar, o que é
verdade (o warm-up não tem pressa nenhuma, roda em background).

**Decisão:** `CacheWarmerService.warmCache` busca um termo por vez (não manda a lista inteira
pro `PageFetcherService.fetchAll` de uma vez) e dá um `Thread.sleep` de ~4s entre um termo e o
próximo do MESMO source. Continua tudo serial na mesma thread única do Playwright (ver item 3 e
item 11) — não paraleliza nada, só espaça no tempo. Consequência aceita: o warm-up de um source
com 10 termos agora leva ainda mais tempo (10 fetches + 9 pausas de 4s ≈ +36s sobre o que já
levava) — tudo bem, roda fora do caminho crítico de uma pergunta do usuário (mesmo racional do
item 11).

## 15. `tb_produto_cache` — cache estruturado separado do `site_cache` raw

**Por quê existe separado:** `site_cache` guarda o texto/JSON bruto da página inteira (útil como
fallback universal, funciona pra qualquer site sem precisar de parser específico), mas mandar
esse blob bruto pra IA é ineficiente (gasta tokens com nav menu, breadcrumbs, JSON aninhado
irrelevante) e não dá pra fazer nada programático com ele (listar/comparar preços fora da IA no
futuro, por exemplo). `tb_produto_cache` guarda uma linha por produto já parseado (nome, preço,
categoria, loja), então `ChatService` pode montar uma lista limpa `"Nome — R$ preço"` pra IA em
vez do bruto.

**Por que não substituir o `site_cache` raw:** o parse estruturado é uma heurística (regex pra
Rondon/Pão de Açúcar, ver item 16) que pode falhar sem o fetch em si ter falhado (site mudou
layout, por exemplo). Quando o parse não encontra nada mas o conteúdo bruto é válido, mantemos o
`site_cache` raw como fallback e só não gravamos nada em `tb_produto_cache` pra esse termo — não
tratamos como erro fatal do termo inteiro. As duas tabelas coexistem, com `tb_produto_cache`
tendo prioridade de leitura em `ChatService` quando disponível (raw é o fallback do fallback).

**Upsert com delete-then-insert por (source, termo):** diferente do `site_cache` (uma linha por
`(source, termo)`, atualizada in-place), `tb_produto_cache` tem N linhas por `(source, termo)` —
uma por produto. Pra não acumular produtos desatualizados de rodadas anteriores, cada
revalidação apaga todas as linhas antigas desse `(source, termo)` antes de gravar as novas.

**Pegadinha de transação encontrada e corrigida:** a primeira versão desse delete usava um
método `@Modifying @Query("delete from ProdutoCache ...")` no `ProdutoCacheRepository`. Isso
quebrou com `TransactionRequiredException`/"No EntityManager with actual transaction available"
quando havia de fato linhas pra apagar — porque `CacheWarmerService.warmCache` chama
`this.upsertProdutoCache(...)` (auto-invocação DENTRO da mesma classe), e chamadas internas
assim não passam pelo proxy do Spring que aplicaria o `@Transactional` do método — então nenhuma
transação real era aberta pra sustentar o `@Modifying`. A correção: trocar por
`findByCitySourceIdAndTermoBuscaIgnoreCase(...)` (leitura simples) seguido de
`produtoCacheRepository.deleteAll(existing)` — `deleteAll` é um método BASE do
`SimpleJpaRepository` (não um derivado/custom), com `@Transactional` garantido no proxy do
PRÓPRIO repositório, então funciona independente de quem chama. Lição geral: qualquer método
`@Transactional` numa classe de serviço que possa ser chamado por auto-invocação (`this.algo()`)
dentro da mesma classe NÃO deve depender de operações que exigem transação explícita
(`@Modifying`, `EntityManager.remove()` direto) — prefira sempre os métodos base do repositório
(`save`, `saveAll`, `delete`, `deleteAll`), que são transacionais no proxy do repositório em si.

## 16. Parser estruturado por site: JSON direto (Atacadão) vs. regex heurístico (Rondon/Pão de Açúcar)

**Atacadão:** a resposta já é JSON da API GraphQL (`ProductsQuery`) — parse direto via Jackson
dos campos `data.search.products.edges[].node.name` (nome) e
`node.offers.offers[0].price` (preço da oferta pra quantidade mínima 1, o que o cliente comum
paga por unidade — o array `offers.offers[]` também tem ofertas com preço menor pra quantidade
mínima maior, que não usamos aqui). Categoria vem de `node.breadcrumbList.itemListElement`,
penúltimo item (o último é o próprio produto). Sem regex chutado — é dado estruturado de
verdade.

**Consequência técnica:** como o parse precisa do JSON completo e válido, `PageFetcherService`
NÃO trunca conteúdo que começa com `{` ou `[` pelo limite de caracteres
(`app.fetch.max-chars-per-site`, pensado pra texto HTML/prompt da IA) — truncar por contagem de
caracteres cortaria a estrutura JSON no meio e quebraria todo parse. Isso significa que, quando
não há cache estruturado disponível e o fallback é o `site_cache` raw do Atacadão, o prompt da
IA pode ficar maior que o de outros sites — aceito porque, na prática, uma vez que
`tb_produto_cache` populou, é ele (a lista limpa) que vai pro prompt, não o JSON bruto.

**Rondon e Pão de Açúcar:** conteúdo é texto renderizado da página (`document.body.innerText`),
sem estrutura de dados — regex/heurística baseada no padrão visual observado:
- Rondon: bloco repetido é `<sku numérico>\n<nome>\n[De R$ X,XX por\n]R$ Y,YY un\n[Preço por
  quilo...\n]Adicionar` — captura nome e o preço "de verdade" (linha `R$ Y,YY un`, nunca a linha
  `De R$... por`, que é o preço riscado antes do desconto).
- Pão de Açúcar: bloco repetido é `R$ X,XX` seguido de LINHA(S) EM BRANCO (não uma quebra
  simples) e então o `<nome>`. Itens em promoção têm um badge de desconto no meio do bloco (ex:
  `R$ 11,79\n\n-5%\n\nR$ 12,49\n\n<nome>`) — dois lookaheads negativos fazem o preço "solto"
  (seguido de outro `R$` ou de um badge `-N%`) ser pulado, then só o par (preço, nome)
  realmente adjacente é capturado. Isso cobre certo a maioria dos itens (sem desconto); pra
  itens COM desconto pode capturar o preço "errado" dos dois mostrados (o heurístico não sabe
  com certeza qual dos dois é o vigente) — aceito, é aproximação razoável, não afeta o
  fallback raw (que sempre tem os dois valores visíveis pra IA interpretar quando o parse
  estruturado não é usado).

**Fragilidade aceita:** essas duas heurísticas são amarradas ao layout ATUAL de cada site. É
esperado que quebrem se Rondon ou Pão de Açúcar mudarem a estrutura da página — nesse caso o
parse simplesmente retorna lista vazia (sem lançar erro pro chamador), loga aviso, e o
`site_cache` raw continua servindo de fallback pra IA. Isso já é o padrão do projeto pra
integrações baseadas em scraping (ver item 4 sobre a API do Atacadão: "se mudar, quebra e precisa
redescobrir").

**Categoria pra Rondon/Pão de Açúcar:** nenhum dos dois expõe categoria por produto de forma
fácil de extrair do texto renderizado (não vale a pena tentar inferir da nav lateral, é
ambíguo). Decisão pragmática: usar o próprio `termo_busca` como valor de `categoria` nesses
casos — sempre preenchido, nunca `null`, mesmo que não seja uma "categoria" no sentido de
taxonomia do site.

## 17. Vercel não serve para o backend

Vercel é serverless, sem runtime Java, com timeout curto e filesystem efêmero —
incompatível com manter um processo Chromium (Playwright) de pé. Backend precisa de
host com container/processo persistente (Railway, Render, Fly.io, VM/Docker).

## 18. Classificar a mensagem ANTES de decidir se roda scraping (não só extrair termo)

**O que aconteceu:** com cidade selecionada, TODA mensagem do usuário disparava scraping em
TODOS os sites da cidade, mesmo mensagens que não eram pedido de preço/produto (ex: "Olá, boa
noite" virou busca literal por "olá" em 5+ sites, ~80s de scraping desnecessário — confirmado em
log real). Causa raiz: `resolveSiteContexts` sempre buscava `CitySource` da cidade e sempre
raspava sites SEM `{termo}` na URL incondicionalmente; e pros sites COM `{termo}`,
`AiService.extractSearchTerm` sempre devolvia "um termo" (mesmo que fosse a mensagem inteira ou
uma palavra literal tipo "olá"), sem nunca poder dizer "isso não é sobre produto".

**Decisão:** substituir `extractSearchTerm` por `AiService.classifyProductQuery`, que numa única
chamada à IA (JSON estrito `{"isProdutoQuery": bool, "termo": string|null}`) decide se a mensagem
é de fato um pedido de preço/produto de mercado. `ChatService.resolveSiteContexts` chama esse
classificador **logo no início**, antes até de consultar `citySourceRepository` — se
`isProdutoQuery == false`, retorna `List.of()` imediatamente, pulando TODO scraping (estático e
dinâmico) pra aquela mensagem. Isso é diferente da lógica antiga (`needsSearchTerm`), que só
evitava a chamada à IA quando nenhum site usava `{termo}`, mas nunca evitava o scraping em si.

**Fail-safe:** se a resposta da IA não vier em JSON válido ou o parse falhar (rede, formato
inesperado, etc.), trata como `isProdutoQuery=false` (loga warn) — prefere errar pro lado de NÃO
raspar a travar o chat ou raspar por engano.

**Consequência aceita:** mensagens que são pedido de produto ainda pagam o custo de uma chamada
extra à IA antes do scraping (como já acontecia antes pra sites com `{termo}`), mas agora essa
chamada acontece sempre (mesmo custo pra decidir "sim/não" + termo, numa única chamada). O ganho
é eliminar completamente os ~80s de scraping em mensagens que não são sobre produto.

## 19. Cache semântico (embedding) na frente de `AiService.classifyProductQuery`

**Por quê:** o item 18 garantiu que só mensagens de fato sobre produto disparam scraping, mas
introduziu uma chamada à IA em TODA mensagem (mesmo as que não são sobre produto), só pra
decidir "sim/não". Usuários tendem a repetir a mesma intenção com fraseado diferente (ex: "quanto
custa o arroz" vs "qual o preço do arroz hoje" vs "arroz tá quanto"), e cada uma dessas variações
pagava o custo/latência de uma chamada nova à IA mesmo já tendo visto uma pergunta
"essencialmente igual" antes.

**Decisão:** `SemanticCacheService.classify(mensagem)` fica entre `ChatService` e `AiService`:
gera o embedding da mensagem nova (`EmbeddingService`, modelo
`Xenova/paraphrase-multilingual-MiniLM-L12-v2`, 384 dimensões, multilíngue/PT-BR, exportado em
ONNX quantizado, ~120MB) e compara por similaridade de cosseno contra os embeddings de mensagens
já classificadas, guardados em `tb_semantic_query_cache`. Se a maior similaridade encontrada bater
o threshold (`app.semantic-cache.threshold`, default `0.90`), reusa o `isProdutoQuery`/`termo`
salvo e PULA a chamada à IA; senão, chama `AiService.classifyProductQuery` normalmente e grava uma
linha nova no cache (embedding + resultado) pra próximas vezes.

**Por que embedding local em vez de outra chamada à IA pra comparar semântica:** rodar
inferência local (ONNX Runtime, `ai.djl.huggingface:tokenizers` só pra tokenizar) é ordens de
magnitude mais rápido e sem custo por chamada, comparado a pedir pra própria IA "essas duas frases
significam a mesma coisa?" — que seria trocar uma chamada à IA por outra, sem ganho nenhum de
custo/latência.

**Modelo baixado sob demanda, nunca no boot:** `EmbeddingService` baixa `tokenizer.json` e
`onnx/model_quantized.onnx` do Hugging Face Hub (`resolve/main/...`) pra `backend/data/models/` só
no primeiro `embed()` chamado (lazy), não em `@PostConstruct` — não faz sentido atrasar a subida
do Spring esperando ~120MB de download; download é idempotente (não baixa de novo se o arquivo já
existe local), mesmo conceito do install do Chromium do Playwright (`mvn
exec:java@install-playwright`) já existente no projeto.

**Fail-safe, sem exceção:** qualquer falha ao gerar embedding (rede fora do ar na primeira vez,
modelo corrompido, erro de inferência) é tratada como cache MISS — cai direto no fallback de
chamar `AiService.classifyProductQuery` normalmente, sem gravar linha nenhuma no cache (uma linha
sem embedding válido não serviria pra nada) e sem nunca propagar erro pro usuário do chat. Segue
o mesmo racional do item 18 (fail-safe da própria classificação por IA): prefere sempre degradar
pro comportamento anterior (sem cache) a travar o chat.

**Threshold calibrado em 0.70 (não o 0.90 inicial):** primeira implementação usou 0.90 como valor
conservador de partida, mas um teste manual rápido no mesmo modelo mostrou que é alto demais na
prática: o par claramente equivalente "quanto custa o arroz" vs "qual o preco do arroz hoje" deu
similaridade de cosseno ≈ **0.725** (abaixo de 0.90, ou seja, nunca bateria cache), enquanto um par
claramente diferente ("boa noite") deu ≈ 0.048. Ajustado pra **0.70** — cobre o par-equivalente
testado com folga confortável sobre o ruído do par-diferente. Configurável via
`app.semantic-cache.threshold` sem precisar recompilar. **Ainda vale recalibrar** observando pares
reais do domínio em produção (nomes de produto variam bastante em fraseado) — 0.70 é o melhor
palpite com 1 par de teste, não uma calibração estatística.

**Volume pequeno, carregado inteiro em memória:** `SemanticQueryCacheRepository.findAll()` traz
todas as linhas pra calcular similaridade contra a mensagem nova — aceitável porque o volume
esperado é de "perguntas únicas" (centenas, não milhares), bem menor que o total de mensagens de
chat (que crescem por usuário/dia). Se isso crescer muito, o próximo passo seria um índice vetorial
de verdade (ex: pgvector), não algo a se preocupar agora com H2 em desenvolvimento.
