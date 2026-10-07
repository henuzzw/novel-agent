# web

## Prompt Settings

`/settings/prompts?template=MANUSCRIPT` opens the global prompt editor. The top bar and project settings link to it.
System instructions and optional stage guidance are persisted through `/api/v1/settings/prompts` with optimistic versions.
Import adaptation and continuation have separate templates. Project inputs and output schemas are not editable here.
Saving affects future model requests only; reset records a new version. Historical versions can be loaded as an unsaved draft.
Run `npx playwright test --config playwright.ux.config.ts prompt-settings.spec.ts` for mocked browser regression tests.
Backend restart/Flyway V049 is required. See `../../NOVEL_AGENT_PROMPT_MANAGEMENT.md`.

## Workspace Interaction

The workspace stores navigation in the URL, not in project data:

- `section`: main navigation (`writing`, `outline`, `materials`, `experience`, `relations`, `imports`, `runs`, `settings`).
- `planning`: planning view (`directions`, `bible`, `outline`, `style`, `preparation`).
- `writing`: chapter view (`contract`, `contractReview`, `manuscript`, `quality`, `opening`, `review`, `memory`).
- `chapter`: a positive chapter number.
- `materials`: materials view (`profiles`, `entities`, `timeline`, `foreshadows`, `style`).

Example: `/projects/<projectId>?section=writing&chapter=2&writing=manuscript`.
Refresh and browser history restore the selected views. Invalid query values fall back safely.
Navigation never generates content. Unsaved bible, outline, manuscript, review and character edits prompt before leaving their work area; a browser refresh also warns about unsaved edits.

Generation controls use native radio groups; long lists such as historical versions remain selects.
Generation status starts compact, retaining running/failed stages and stop controls; expand it to see all stages.

Run the interaction regression tests with an installed Google Chrome:

```sh
npm run test:e2e:ux
```

The suite mocks backend/model requests, verifies navigation and edit protection, and captures desktop/mobile screenshots. It does not call a model or write project data.

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Run Unit Tests with [Vitest](https://vitest.dev/)

```sh
npm run test:unit
```

### Run End-to-End Tests with [Playwright](https://playwright.dev)

```sh
# Install browsers for the first run
npx playwright install

# When testing on CI, must build the project first
npm run build

# Runs the end-to-end tests
npm run test:e2e
# Runs the tests only on Chromium
npm run test:e2e -- --project=chromium
# Runs the tests of a specific file
npm run test:e2e -- tests/example.spec.ts
# Runs the tests in debug mode
npm run test:e2e -- --debug
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
