# AndCode

<p align="center">
  <a href="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml"><img src="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/v/release/yuga-hashimoto/and-code" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/yuga-hashimoto/and-code" alt="License: MIT" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/downloads/yuga-hashimoto/and-code/total" alt="Downloads" /></a>
</p>

**Execute agentes de programação com IA localmente no Android por meio de uma interface gráfica nativa — sem terminal.**

AndCode é um aplicativo Android nativo com interface gráfica que leva agentes de programação com IA para o seu celular. Converse com [OpenCode](https://github.com/sst/opencode), [Claude Code](https://github.com/anthropics/claude-code), [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) e [OpenAI Codex](https://github.com/openai/codex) por uma interface feita para o toque — sem terminal, sem SSH e sem PC para uso no dispositivo. Ele executa os runtimes dos agentes via PRoot (no dispositivo) ou se conecta remotamente ao seu servidor OpenCode existente no PC/Mac/Linux.

[Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Français](README.fr.md) | [Português (Brasil)](README.pt-BR.md) | [Русский](README.ru.md) | [العربية](README.ar.md)

> [!IMPORTANT]
> O AndCode é uma interface gráfica independente e local-first que instala ou inicia ferramentas de linha de comando de terceiros compatíveis no próprio dispositivo Android do usuário. O AndCode não fornece nem revende os serviços de IA subjacentes, assinaturas, acesso a modelos ou direitos de conta. Autenticação, acesso a modelos, inferência e comunicação com provedores são tratados pela CLI oficial aplicável ou pelo provedor configurado pelo usuário. O AndCode não é afiliado, endossado, patrocinado ou oficialmente suportado pelo OpenCode, Anthropic, Google ou OpenAI. Veja [Legal e software de terceiros](#legal-e-software-de-terceiros) abaixo.

<div align="center">

### Capturas de tela

<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="Gaveta de navegação com agentes, projetos e conversas recentes"><br><em>Gaveta de navegação</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="Seletor de modelo e runtime com modelos favoritos pesquisáveis"><br><em>Seletor de modelo e runtime</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="Conversa com um agente inspecionando o repositório do AndCode"><br><em>Conversa no repositório</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="Imagem gerada pelo agente exibida dentro de uma conversa"><br><em>Geração de imagens</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="Prompt agendado com execução diária ativada"><br><em>Agendamentos</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="Execução agendada concluída com opção de abrir a conversa"><br><em>Resultado da execução</em></td>
  </tr>
</table>

</div>

> [!IMPORTANT]
> O AndCode é um projeto de código aberto independente. Ele **não** é afiliado ao OpenCode, Anthropic, Google ou OpenAI.

---

## Sumário

- [Agentes compatíveis](#agentes-compatíveis)
- [Recursos](#recursos)
- [Antigravity](#antigravity)
- [Codex](#codex)
- [OpenCode remoto](#opencode-remoto)
- [Telas](#telas)
- [Início rápido](#início-rápido)
- [Segurança](#segurança)
- [Detalhes do runtime no dispositivo](#detalhes-do-runtime-no-dispositivo)
- [Handoff (troca de runtime no meio da conversa)](#handoff-troca-de-runtime-no-meio-da-conversa)
- [Conexão com o OpenCode Desktop](#conexão-com-o-opencode-desktop)
- [Compilar a partir do código-fonte](#compilar-a-partir-do-código-fonte)
- [Documentação](#documentação)
- [Contribuindo](#contribuindo)
- [Legal e software de terceiros](#legal-e-software-de-terceiros)
- [Licença](#licença)

## Agentes compatíveis

| Agente | No dispositivo | PC remoto | Estado |
|--------|:--------------:|:---------:|--------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | Estável |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | Beta |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | Beta |
| [OpenAI Codex](https://github.com/openai/codex) | ✓ | — | Beta |

Os agentes no dispositivo são executados em um ambiente Linux via PRoot. OpenCode, Claude Code e Codex usam Alpine Linux; o Google Antigravity instala também um rootfs Debian Bookworm junto ao Alpine, pois o binário oficial `agy` é vinculado ao glibc.

## Recursos

- **Interface Android nativa** — Interface feita para o toque para agentes de programação; nenhuma CLI ou terminal necessária no uso diário
- **Runtime no dispositivo** — Alpine Linux, Git, bash, curl, ripgrep e agentes de programação instalados automaticamente no seu dispositivo Android via PRoot
- **Repositório e workspace** — Abra repositórios git no dispositivo, navegue pela árvore de arquivos e visualize arquivos com destaque de sintaxe
- **Arquivos do dispositivo** — Conceda acesso a todos os arquivos e o celular inteiro (`/sdcard`, cartões SD, unidades USB) fica navegável no seletor de pastas e acessível ao agente, aberto no local em vez de copiado para o aplicativo
- **Suporte a Git** — Veja o status e revise diffs pela interface; use o terminal do runtime integrado para outras operações Git
- **Visualizador de diffs** — Revise alterações de código em linha antes de aceitar
- **Terminal integrado** — Execute comandos de shell limitados dentro do runtime no dispositivo
- **Selos de pull request** — Pull requests abertas em uma conversa ficam fixadas acima do compositor com o tamanho do diff e o estado (rascunho, aberta, conflito, mesclada, fechada); toque para abri-las no GitHub
- **Aprovação de ferramentas** — Aprove ou rejeite operações perigosas das ferramentas
- **Gerenciamento de sessões** — Crie, retome, renomeie e exclua sessões
- **Tarefas agendadas** — Execute prompts automaticamente em um agendamento único ou recorrente (cron), com histórico de execuções
- **Modelos dinâmicos** — Modelos, provedores e agentes obtidos em tempo real da instância de agente conectada
- **Streaming em tempo real** — Respostas, execução de ferramentas e solicitações de aprovação ao vivo via SSE
- **Linha do tempo estruturada** — Raciocínio, chamadas de ferramentas e saída de comandos recolhíveis
- **Voz + palavra de ativação** — Push-to-talk com reconhecimento de voz do Android e detecção de palavra de ativação
- **Texto para fala** — Leia as respostas em voz alta
- **Assistente digital** — Registre como assistente padrão do Android (gesto de início / deslize do canto)
- **Widget da tela inicial** — Dispare um prompt direto do launcher sem abrir o aplicativo
- **Armazenamento seguro** — Credenciais de conexão criptografadas com o Android Keystore
- **Interface localizada** — Inglês, japonês, chinês (simplificado), russo, espanhol, francês, português (Brasil) e árabe, alternáveis em Configurações

## Antigravity

O Google Antigravity (`agy`) é executado no dispositivo dentro do mesmo ambiente PRoot. Diferente do OpenCode e do Claude Code, que rodam no Alpine Linux, o Antigravity usa um rootfs Debian Bookworm por compatibilidade glibc com o binário oficial da CLI.

- **Login OAuth** — Autenticação pelo fluxo de URL no navegador + código de uso único; as credenciais ficam apenas no rootfs Linux (`~/.gemini`), nunca nas preferências do Android
- **Seleção de modelo** — Catálogo de modelos em tempo real obtido da instância `agy` autenticada (variantes Gemini, Claude e GPT-OSS)
- **Modos de permissão** — Controle em três níveis: Plan, Accept Edits e Full Access (`--dangerously-skip-permissions`)
- **Servidores MCP** — Leia e grave diretamente `~/.gemini/config/mcp_config.json`, no mesmo formato de configuração da CLI oficial
- **Lançador de sandbox** — Processo baseado em PTY com geometria de terminal explícita; `AGY_CLI_DISABLE_AUTO_UPDATE=1` mantém as atualizações sob controle do aplicativo

## Codex

A CLI Codex da OpenAI é executada no dispositivo dentro do mesmo sandbox Alpine/PRoot do OpenCode e do Claude Code, comunicando-se com o aplicativo por seu protocolo JSON-RPC `app-server`.

- **Login** — Conta ChatGPT (fluxo no navegador) ou uma chave de API da OpenAI, pela mesma caixa de diálogo de login dos outros provedores
- **Servidores MCP** — Gerenciados com o subcomando oficial `codex mcp`, de modo que o aplicativo nunca edita `~/.codex/config.toml` manualmente
- **Geração de imagens** — Imagens da ferramenta integrada `image_gen` são exibidas em linha na conversa
- **Configuração** — Instalável pelo guia de configuração ou Configurações → Agentes → Codex; sem rootfs separado, pois o binário do Codex vinculado a musl roda diretamente no ambiente Alpine compartilhado

Veja [docs/CODEX.md](docs/CODEX.md) para detalhes de implementação.

## OpenCode remoto

Além dos agentes no dispositivo, o AndCode pode se conectar ao OpenCode em execução no seu PC/Mac/Linux como recurso adicional:

- **Conexão remota** — Conecte por LAN ou Tailscale
- **Troca de runtime** — Alterne perfeitamente entre execução local e remota, mesmo no meio da conversa (handoff)
- **Descoberta** — Encontre PCs por código QR ou mDNS (descoberta em LAN sem configuração)

## Telas

| Tela | Descrição |
|------|-----------|
| Conversa | Tela inicial e visualização da conversa — sessões recentes, raciocínio/ferramentas recolhíveis, entrada de voz, troca de modelo, aprovações, handoff |
| Workspaces | Configuração do runtime local, conexões com PC, pastas de trabalho, navegador de arquivos, visualizador de código, terminal integrado |
| Agendamentos | Crie, edite, ative/desative e revise o histórico de execuções de prompts únicos ou recorrentes (cron) |
| Configurações | Agentes e provedores, GitHub, servidores MCP, visibilidade de modelos, voz/TTS, assistente digital, idioma |

## Início rápido

### Opção A: no dispositivo (sem PC)

1. Instale o APK em [Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)
2. Abra o aplicativo → toque em **Workspaces** → **Este dispositivo Android** → **Configurar neste dispositivo**
3. Aguarde o download e a instalação do runtime (~2 min com uma boa conexão)
4. Selecione seu agente de programação e comece a conversar

### Opção B: PC remoto

1. Inicie o OpenCode no seu PC:

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. Instale o APK no seu dispositivo Android
3. Abra o aplicativo → **Workspaces** → **Adicionar conexão**
4. Insira o IP do seu PC (ou use **Busca na LAN** / **Código QR** para descoberta automática)

```text
Name:     Mac mini
URL:      http://192.168.1.10:4096
Username: opencode
Password: your-strong-password
```

> O Tailscale também funciona: `http://100.x.y.z:4096` ou `http://your-mac.tailnet-name.ts.net:4096`

### Configuração por código QR

Gere um código QR no seu PC:

```bash
npx qrcode "opencode://connect?name=Mac%20mini&url=http%3A%2F%2F192.168.1.10%3A4096&username=opencode&password=your-password&insecure=true"
```

Depois escaneie em **Workspaces** → **Adicionar por QR** no aplicativo.

## Segurança

- **Nunca** exponha a porta 4096 diretamente à internet
- Use LAN ou Tailscale para conectividade
- Use um proxy reverso HTTPS em redes públicas
- O aplicativo nunca aprova automaticamente operações perigosas
- HTTP em texto simples na LAN exige aceitação explícita por conexão
- O PRoot é um runtime de compatibilidade, não um sandbox de segurança completo. Os modos de acesso total / ignorar permissões permitem que a CLI oficial execute comandos e edite arquivos sem perguntar; o aplicativo avisa antes de ativá-los, mas ainda assim faça backup dos arquivos importantes e aponte um agente apenas para repositórios, prompts e servidores MCP confiáveis
- Códigos QR gerados para o fluxo `opencode://connect` podem incluir uma senha de conexão em texto simples — escaneie apenas códigos de uma fonte confiável e trate um código QR impresso ou exibido como se estivesse compartilhando essa senha

## Detalhes do runtime no dispositivo

O processo de configuração (iniciado em Workspaces):

1. Verifica o runner PRoot nativo incluído no APK
2. Baixa o minirootfs Alpine Linux do CDN oficial
3. Baixa o binário do agente do GitHub Releases
4. Valida as somas de verificação SHA-256 de ambos
5. Extrai para um diretório privado do aplicativo
6. Instala Git, bash, curl, ripgrep e certificados de CA dentro do Alpine
7. Inicia o servidor do agente em `127.0.0.1:4097`
8. Alterna o aplicativo para o runtime local

Versões fixadas (atualizáveis por releases do aplicativo sem alterar o agente; veja [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)):

- Alpine Linux 3.24.1
- OpenCode 1.18.5
- Google Antigravity CLI 1.1.7 (rootfs Debian Bookworm)
- Arquiteturas: arm64-v8a, x86_64

## Handoff (troca de runtime no meio da conversa)

No menu do cabeçalho da conversa → **Continuar em outro runtime** — o aplicativo gera um prompt de resumo da conversa e o envia ao runtime selecionado, permitindo retomar de onde parou (por exemplo, começar no dispositivo no trajeto e continuar no PC em casa).

## Conexão com o OpenCode Desktop

Adicione a configuração do servidor em `~/.config/opencode/opencode.json`:

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "mdns": true
  }
}
```

Reinicie o aplicativo de desktop e depois descubra-o pelo aplicativo Android via **Busca na LAN**.

## Compilar a partir do código-fonte

Requisitos: JDK 17, Android SDK, Python 3, acesso à rede (apenas na primeira compilação)

```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

APKs gerados:

```text
app/build/outputs/apk/github/debug/app-github-debug.apk
app/build/outputs/apk/github/release/app-github-release-unsigned.apk
```

Instalar no dispositivo:

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

## Documentação

- [Design do runtime no dispositivo](docs/LOCAL_RUNTIME.md)
- [Runtime local do Antigravity](docs/ANTIGRAVITY.md)
- [Design de paridade do agente Antigravity](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [Runtime local do Codex](docs/CODEX.md)
- [Guia de CI](docs/CI.md)
- [Guia de release](docs/RELEASE.md)
- [Guia de tradução](docs/TRANSLATION.md)
- [Matriz de validação de dispositivos](docs/device-matrix.md)

## Contribuindo

Contribuições são bem-vindas — código, relatos de bugs e traduções! Veja [CONTRIBUTING.md](CONTRIBUTING.md) para configuração e fluxo de trabalho, e [docs/TRANSLATION.md](docs/TRANSLATION.md) se quiser ajudar a traduzir o aplicativo.

## Legal e software de terceiros

- **As CLIs oficiais são executadas no dispositivo.** OpenCode, Claude Code, Google Antigravity e OpenAI Codex são os binários oficiais não modificados de seus respectivos projetos, baixados de seus canais oficiais de distribuição e executados dentro do ambiente Linux PRoot do seu dispositivo (ou, para o OpenCode, na sua própria máquina PC/Mac/Linux). O AndCode não faz fork, patch ou reimplementação da lógica dos agentes.
- **Você fornece sua própria conta ou acesso à API.** O AndCode não vende, revende nem fornece de outra forma acesso ao Claude, Antigravity, Codex, modelos do OpenCode ou GitHub. Você se autentica com sua própria conta ou configuração de provedor, sujeita aos termos de serviço vigentes desse provedor.
- **O AndCode não retransmite prompts nem tokens por seus próprios servidores.** Não existe backend do AndCode por onde passem prompts, arquivos ou tokens OAuth. As solicitações vão diretamente da CLI no dispositivo (ou do servidor OpenCode do seu PC) ao provedor que você configurou. Veja [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md).
- **Tokens OAuth não são reutilizados entre ferramentas.** Claude Code, Antigravity e Codex mantêm cada um suas próprias credenciais OAuth dentro do rootfs Linux/Debian como sua CLI oficial normalmente faria; o AndCode nunca as copia para as configurações do aplicativo Android, para o armazenamento de credenciais de outro agente ou para um serviço externo.
- **Veja também:** [PRIVACY.md](PRIVACY.md), [TERMS.md](TERMS.md), [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md), [TRADEMARKS.md](TRADEMARKS.md) e [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (licenças de OSS dos componentes de runtime incluídos e das dependências). Todos também podem ser acessados offline em **Configurações → Legal e privacidade** no aplicativo.

A geração do runtime reutiliza lógica genérica de resolução e extração de pacotes do Termux, redesenhada para agentes de programação, inspirada na implementação Android do Hermes Agent sob licença MIT. Veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) para a lista completa de componentes de terceiros incluídos e suas licenças.

## Licença

O **código-fonte do AndCode** neste repositório é [licenciado sob MIT](LICENSE). Essa licença cobre apenas o código Kotlin/Android próprio do AndCode — ela **não** se estende às CLIs, runtimes ou pacotes de terceiros que o AndCode instala ou inicia (Claude Code, Google Antigravity, OpenAI Codex, OpenCode, PRoot, pacotes Alpine/Debian e outros), cada um distribuído sob sua própria licença upstream. Veja [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [TRADEMARKS.md](TRADEMARKS.md).
