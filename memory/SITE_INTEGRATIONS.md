# Integrações de sites por mercado (Araçatuba/SP)

Todas cadastradas via `POST /api/settings/sources` (admin), cidade `"Aracatuba"` (sem
cedilha/til no banco — cuidado com encoding ao criar via curl/shell no Windows, use
ASCII sem acento pra evitar erro de parse UTF-8).

## Supermercados Rondon — ✅ busca dinâmica funcional

- URL: `https://lojaonline.supermercadosrondon.com.br/p/busca/{termo}`
- Tipo: página HTML renderizada via Angular (SSR + hidratação client-side)
- Funciona bem, é o mais confiável dos 4.
- Descoberta importante: bloqueava requests com User-Agent genérico (403 via
  CloudFront/WAF) — resolvido usando um User-Agent de navegador real (Chrome no
  Windows) no `PageFetcherService`.

## Atacadão — ✅ busca dinâmica via API GraphQL direta

- URL: API GraphQL pública via GET, **não é a página HTML**:
  ```
  https://www.atacadao.com.br/api/graphql?operationName=ProductsQuery&variables=%7B%22first%22%3A20%2C%22after%22%3A%220%22%2C%22sort%22%3A%22score_desc%22%2C%22term%22%3A%22{termo}%22%2C%22selectedFacets%22%3A%5B%7B%22key%22%3A%22channel%22%2C%22value%22%3A%22%7B%5C%22salesChannel%5C%22%3A%5C%221%5C%22%2C%5C%22seller%5C%22%3A%5C%22atacadaobr60%5C%22%2C%5C%22regionId%5C%22%3A%5C%22U1cjYXRhY2FkYW9icjYw%5C%22%7D%22%7D%2C%7B%22key%22%3A%22locale%22%2C%22value%22%3A%22pt-BR%22%7D%5D%7D
  ```
- **Por que API e não a página:** a página `/s?q={termo}` do Atacadão não renderiza os
  cards de produto no DOM de forma confiável em sessão headless (a UI real usa uma
  chamada `ProductGalleryQuery` pra facets + `ProductsQuery` pra produtos/preço — foi
  assim que descobrimos essa API, inspecionando o Network tab do navegador).
- `regionId` (`U1cjYXRhY2FkYW9icjYw`) é um valor **fixo, genérico**, encontrado
  navegando o site sem definir CEP algum — representa uma região/catálogo "padrão"
  nacional. Se o Atacadão mudar esse encoding, a integração quebra e precisa
  redescobrir (repetir o processo: abrir o site, filtro de busca, checar Network tab
  por `operationName=ProductsQuery`).
- Resposta é JSON puro — sem "R$" literal (preço é campo numérico `price`/`listPrice`).
  Isso quebrou a estratégia de espera por `waitForSelector("text=R$")` (ver DECISIONS.md item 2).

## Pão de Açúcar — ✅ busca dinâmica funcional, mas com bloqueio anti-bot intermitente sob rajada

- URL: `https://www.paodeacucar.com/busca?terms={termo}`
- Tipo: página HTML, busca simples por query string, sem headers especiais.
- **Cuidado:** demora entre ~4s e ~20s pra carregar dependendo da sessão (decide loja
  padrão via `localStorage`/Redux persist numa sessão nova). A espera adaptativa por
  estabilização de texto (DECISIONS.md item 2) resolve isso — não usar tempo fixo curto.
- **Bloqueio anti-bot sob rajada (mitigado, não eliminado):** o warm-up do cache
  (`CacheWarmerService`) fez 10 requisições em rajada (sem pausa nenhuma) numa rodada
  anterior e o site respondeu com uma página de verificação humana ("não sou um robô")
  em vez do catálogo — e essa página chegou a ser cacheada por engano (bug corrigido,
  ver DECISIONS.md item 13: guarda de conteúdo anti-bot). Confirmamos manualmente que um
  acesso único e espaçado ao site funciona (retorna produto/preço reais sem desafio).
  Depois de implementar a guarda (item 13) e o espaçamento de ~4s entre termos (item 14),
  uma primeira revalidação do warm-up completo (10 termos) ainda bateu bloqueio nas 10
  tentativas — mas o comportamento correto aconteceu: nada foi cacheado, tudo ficou
  `FALHOU`, sem nenhuma página de verificação salva em lugar nenhum. Numa segunda
  revalidação (mesma sessão, um pouco depois, tráfego de teste mais baixo), as 10
  tentativas espaçadas passaram sem bloqueio nenhum e o cache (raw + estruturado)
  populou normalmente. Conclusão: o espaçamento ajuda mas não elimina 100% o risco de
  bloqueio (pode depender de estado momentâneo do lado do site/rede) — **por decisão
  explícita do projeto, nunca se deve tentar investigar/contornar isso mais a fundo**
  (nada de resolver o desafio, stealth scripts, ou spoofing de fingerprint — ver limite
  absoluto em DECISIONS.md item 13). Se acontecer de novo: o termo fica `FALHOU`, sem
  cache, e o chat cai no fetch ao vivo normal pra esse source — comportamento aceitável
  e já implementado, não precisa de mais nada.

## Amigão Supermercados — ⚠️ só referência estática (sem busca dinâmica)

- URL atual: `https://onlinesim.com.br/supermercado-oamigao/` (a URL antiga,
  `loja.vrsoft.com.br/supermercado-oamigao/`, redireciona pra essa)
- **Não conseguimos** fazer busca por produto funcionar: digitar no campo de busca do
  site não muda a URL nem dispara uma requisição de API visível — provavelmente exige
  informar CEP/loja antes de liberar a busca.
- Se quiser tentar de novo no futuro: abrir o site manualmente, checar se aparece um
  modal de "informar CEP" antes da busca, e inspecionar Network tab por chamadas
  fetch/XHR ao digitar — mesmo processo que revelou a API do Atacadão.

## Assaí Atacadista — ⚠️ só referência estática (sem catálogo/busca)

- URL cadastrada: `https://www.assai.com.br/loja/assai-aracatuba` (loja confirmada: Av.
  Waldemar Alves 230, São Vicente, Araçatuba)
- Site é institucional — sem carrinho, sem catálogo pesquisável, sem preço exposto.
  Compra é só por televendas (telefone) ou app "Meu Assaí". Não dá pra extrair `{termo}`.
- Cadastrado mesmo assim (a pedido do usuário) só como referência — IA não vai achar
  preço de produto aqui, mas segue a regra de ignorar silenciosamente (DECISIONS.md item 7).

## Mercados pesquisados e descartados (sem site com preço, não cadastrados)

- **Stock Atacadista** (`stockatacadista.com.br`) — maior empresa de alimentos de
  Araçatuba, mas site é só institucional/encartes; delivery é via iFood (parceiro), sem
  URL de busca própria. Se quiser tentar via iFood no futuro, é um mecanismo diferente
  (cardápio de loja, não busca por termo) — precisaria investigação separada.
- **Supermercados Moderno** (2 lojas em Araçatuba) — sem site funcional, atendimento só
  via WhatsApp/telefone. Nada pra raspar.

## Como adicionar um novo mercado

1. Abra o site manualmente, pesquise um produto (ex: "feijão").
2. Verifique se a URL muda com um padrão (`?q=`, `/busca/`, `?terms=` etc.) — se sim,
   provável candidato a `{termo}` na URL cadastrada.
3. Se a URL não muda mas os resultados aparecem (SPA com estado interno), abra o
   Network tab e procure uma chamada GET com o termo de busca nos parâmetros — pode ser
   uma API GraphQL/REST direta (mais rápida e confiável que raspar a página).
4. Teste a URL final navegando direto nela (sem estar logado/com cookies) — se o
   conteúdo relevante aparecer no HTML puro (ou JSON), é um bom candidato.
5. Cadastre via `POST /api/settings/sources` com `{city, label, url}` (url com
   `{termo}` se aplicável).
6. Teste pelo chat perguntando por um produto que você já sabe que existe lá, e
   confira o log do backend (`grep "lido com sucesso"`) pra ver quantos caracteres
   vieram — se for muito pouco (dezenas de caracteres), provavelmente não carregou
   o conteúdo real.
