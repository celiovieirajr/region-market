# Memory — base de conhecimento do ChatBot IA

Esta pasta é a fonte de contexto para qualquer IA (ou desenvolvedor) que continue este
projeto. Antes de implementar algo novo, leia os arquivos relevantes aqui — eles
documentam decisões já tomadas, o porquê delas, e limitações conhecidas, para evitar
retrabalho ou reverter escolhas que já foram testadas e validadas.

## Índice

- [ARCHITECTURE.md](ARCHITECTURE.md) — visão geral do sistema: stack, camadas, como as peças se conectam.
- [DECISIONS.md](DECISIONS.md) — decisões técnicas importantes e o porquê de cada uma.
- [SITE_INTEGRATIONS.md](SITE_INTEGRATIONS.md) — detalhes específicos de cada mercado integrado (URLs, padrões de busca, particularidades).
- [KNOWN_ISSUES.md](KNOWN_ISSUES.md) — limitações conhecidas e o que falta resolver.
- [CHANGELOG.md](CHANGELOG.md) — histórico cronológico de tudo que foi construído (fonte do Mural/v1.0.0 no admin).

## Como usar

- Ao adicionar um novo mercado/site: leia `SITE_INTEGRATIONS.md` primeiro — o padrão de
  integração (URL com `{termo}`, API direta, ou referência estática) já está descrito ali,
  não precisa redescobrir do zero.
- Ao mexer no `PageFetcherService` (scraping via Playwright): leia `DECISIONS.md` sobre a
  espera adaptativa — já tentamos tempo fixo e `waitForSelector` por texto, os dois
  falharam por motivos específicos documentados lá.
- Ao adicionar uma feature nova: registre em `CHANGELOG.md` com data, o que mudou, e por
  quê — isso também alimenta o Mural (`/mural.html`, admin-only).
